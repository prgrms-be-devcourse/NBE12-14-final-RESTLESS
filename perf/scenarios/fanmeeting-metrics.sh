#!/usr/bin/env bash
# 팬미팅 측정 중 서버 지표 기록. Grafana 없이 서버 지연·처리량·연결 풀·CPU를 남긴다. 사용법은 perf/scenarios/fanmeeting.md.
#
#   fanmeeting-metrics.sh                 # 기록 시작. Ctrl-C(또는 DURATION 경과)로 끝내면 요약을 출력한다
#   fanmeeting-metrics.sh summary <dir>   # 이미 기록한 폴더를 다시 요약한다. FROM, UNTIL로 구간을 자를 수 있다
#   fanmeeting-metrics.sh snap            # OUT_DIR에 지금 한 장만 저장한다(단계 경계용). 저장한 이름(epoch ms)을 출력한다
#
# 같은 OUT_DIR의 수집(기록 루프, snap)은 잠금(OUT_DIR/.scrape.lock)으로 한 번에 하나만 한다. 파일 이름은 응답을 다 받은
# 시각(epoch ms)이라, 이름 순서가 서버가 응답을 만든 순서와 같다. 잠금을 LOCK_WAIT초(기본 15) 안에 못 얻으면 그 수집은 실패다.
#
# summary 구간 지정:
#   FROM_SNAPSHOT, UNTIL_SNAPSHOT  경계 스냅샷 이름(epoch ms). 정확히 이 두 장 사이를 센다. 없으면 INVALID
#   FROM, UNTIL                    시각(ISO-8601 또는 epoch ms). 그 안의 첫·마지막 스냅샷 사이를 센다
#   BOUNDARY_MISSING               값이 있으면 경계 스냅샷이 없다는 뜻으로 INVALID 처리한다(fanmeeting-s1.sh가 넘긴다)
# 앱 재시작, 누적 카운터 역행, 경계 누락이 있으면 요약 첫머리에 INVALID를 표시하고 종료 코드 3으로 끝난다.
#
# 관리 포트(MANAGEMENT_URL)의 /actuator/prometheus를 INTERVAL마다 파일로 저장한다. 앱에는 읽기 요청만 보낸다.
# 필요: curl, python3(표준 라이브러리만).
set -euo pipefail

die() { echo "metrics: $*" >&2; exit 1; }

summarize() {
  local dir="$1"
  [[ -d "$dir" ]] || die "no such directory: $dir"
  python3 - "$dir" <<'PY'
import json, os, re, sys
from datetime import datetime, timezone

d = sys.argv[1]
def parse_time(text):
    if not text:
        return None
    if re.fullmatch(r'\d+', text):
        return int(text)
    return int(datetime.fromisoformat(text.replace('Z', '+00:00')).timestamp() * 1000)
since, until = parse_time(os.environ.get('FROM')), parse_time(os.environ.get('UNTIL'))
problems = []
from_snap, until_snap = os.environ.get('FROM_SNAPSHOT') or None, os.environ.get('UNTIL_SNAPSHOT') or None
for name, v in (('FROM_SNAPSHOT', from_snap), ('UNTIL_SNAPSHOT', until_snap)):
    if v is not None and not re.fullmatch(r'\d+', v):
        sys.exit(f'metrics: {name} must be a snapshot name (epoch ms): {v}')
if from_snap or until_snap:
    since, until = (int(from_snap) if from_snap else since), (int(until_snap) if until_snap else until)
    for label, v in (('start', from_snap), ('end', until_snap)):
        if v and not os.path.exists(os.path.join(d, f'{v}.prom')):
            problems.append(f'{label} boundary snapshot {v}.prom is missing')
if os.environ.get('BOUNDARY_MISSING'):
    problems.append(os.environ['BOUNDARY_MISSING'])

files = sorted(f for f in os.listdir(d) if re.fullmatch(r'\d+\.prom', f))
snaps = []
line_re = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)')
label_re = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')
for f in files:
    ts = int(f[:-5])
    if (since and ts < since) or (until and ts > until):
        continue
    metrics = {}
    with open(os.path.join(d, f), encoding='utf-8') as fh:
        for line in fh:
            if line.startswith('#'):
                continue
            m = line_re.match(line)
            if not m:
                continue
            try:
                value = float(m.group(4))
            except ValueError:
                continue
            labels = tuple(sorted(label_re.findall(m.group(3) or '')))
            metrics[(m.group(1), labels)] = value
    if metrics:
        snaps.append((ts, metrics))
if len(snaps) < 2:
    problems.append(f'need at least 2 snapshots in range, found {len(snaps)}')
    print('\n'.join(['', f'server metrics  ⚠ INVALID: {"; ".join(problems)}', '']))
    with open(os.path.join(d, 'summary.json'), 'w', encoding='utf-8') as fh:
        json.dump({'valid': False, 'problems': problems, 'fromSnapshot': from_snap, 'untilSnapshot': until_snap,
                   'requestedFrom': os.environ.get('FROM') or None, 'requestedUntil': os.environ.get('UNTIL') or None,
                   'snapshots': len(snaps), 'endpoints': [], 'resources': {}, 'workers': {}}, fh, indent=2)
    sys.exit(3)

first_ts, first = snaps[0]
last_ts, last = snaps[-1]
elapsed = (last_ts - first_ts) / 1000

# 앱이 재시작되면 누적 카운터가 0부터 다시 시작해 구간 차이가 틀린다.
starts = {m.get(('process_start_time_seconds', ())) for _, m in snaps} - {None}
restarted = len(starts) > 1
if restarted:
    problems.append('app restarted in this range (process_start_time_seconds changed); counters restarted from 0')

# 누적 카운터는 이름(시각) 순서대로 줄어들면 안 된다. 줄어들면 스냅샷 순서가 응답 순서와 다르거나(수집 겹침)
# 재시작이다. 어느 쪽이든 차이·처리량을 믿을 수 없다.
CUMULATIVE = ('_count', '_sum', '_bucket', '_total')
backwards = []
for (t0, m0), (t1, m1) in zip(snaps, snaps[1:]):
    for (n, l), v1 in m1.items():
        if not n.endswith(CUMULATIVE):
            continue
        v0 = m0.get((n, l))
        if v0 is not None and v1 < v0:
            backwards.append(f'{n}{{{",".join(f"{k}={v}" for k, v in l)}}} {v0:g} at {t0} -> {v1:g} at {t1}')
if backwards and not restarted:
    problems.append(f'cumulative counters went backwards between snapshots ({len(backwards)} series, first: {backwards[0]})')

def lab(labels, key):
    return dict(labels).get(key, '')

def gauge_max(name, keep=lambda labels: True, combine=max):
    """스냅샷마다 조건에 맞는 값을 combine(합 또는 최대)으로 묶고, 그중 최댓값."""
    best = None
    for _, m in snaps:
        vals = [v for (n, l), v in m.items() if n == name and keep(l)]
        if vals:
            v = combine(vals)
            best = v if best is None else max(best, v)
    return best

# 요청: method + uri별로 구간 증가분을 모은다. /api/ 밖(actuator 등)과 UNKNOWN은 뺀다.
def endpoint(labels):
    uri = lab(labels, 'uri')
    if not uri.startswith('/api/'):
        return None
    return f"{lab(labels, 'method')} {uri}"

def delta(name):
    out = {}
    for (n, l), v in last.items():
        if n != name:
            continue
        out[l] = v - first.get((n, l), 0)
    return out

# API별 성공 응답. 응모(POST .../applications)는 201, 조회(GET)는 200만 성공으로 센다.
# 거절(409·503)과 오류도 서버는 처리하므로 전체 요청 수와 성공 수를 따로 본다.
def success_status(ep):
    return '201' if ep.startswith('POST ') else '200'

counts = {}
by_status = {}
for l, v in delta('http_server_requests_seconds_count').items():
    ep = endpoint(l)
    if not ep or v <= 0:
        continue
    counts[ep] = counts.get(ep, 0) + v
    st = by_status.setdefault(ep, {})
    st[lab(l, 'status')] = st.get(lab(l, 'status'), 0) + v

buckets = {}
for l, v in delta('http_server_requests_seconds_bucket').items():
    ep = endpoint(l)
    if not ep:
        continue
    le = lab(l, 'le')
    le = float('inf') if le == '+Inf' else float(le)
    b = buckets.setdefault(ep, {})
    b[le] = b.get(le, 0) + v

def percentile(ep, q):
    b = buckets.get(ep)
    if not b:
        return None
    items = sorted(b.items())
    total = items[-1][1]
    if total <= 0:
        return None
    target = q * total
    prev_le, prev_c = 0.0, 0.0
    for le, c in items:
        if c >= target:
            if le == float('inf'):
                return prev_le * 1000
            if c == prev_c:
                return le * 1000
            return (prev_le + (le - prev_le) * (target - prev_c) / (c - prev_c)) * 1000
        prev_le, prev_c = le, c
    return None

def max_seen(ep):
    best = None
    for _, m in snaps:
        for (n, l), v in m.items():
            if n == 'http_server_requests_seconds_max' and endpoint(l) == ep:
                best = v if best is None else max(best, v)
    return None if best is None else best * 1000

# 스냅샷 사이 구간별 초당 처리량의 최댓값(엔드포인트별). status를 주면 그 응답만 센다.
def peak_rate(ep, status=None):
    best = 0.0
    for (t0, m0), (t1, m1) in zip(snaps, snaps[1:]):
        dt = (t1 - t0) / 1000
        if dt <= 0:
            continue
        c = sum(v - m0.get((n, l), 0) for (n, l), v in m1.items()
                if n == 'http_server_requests_seconds_count' and endpoint(l) == ep
                and (status is None or lab(l, 'status') == status))
        best = max(best, c / dt)
    return best

hikari_timeouts = sum(v for v in delta('hikaricp_connections_timeout_total').values())
heap = gauge_max('jvm_memory_used_bytes', lambda l: lab(l, 'area') == 'heap', sum)

# Worker(@Scheduled) 실행: 메서드별 구간 실행 수, 평균, 기록된 최대.
workers = {}
d_count = delta('tasks_scheduled_execution_seconds_count')
d_sum = delta('tasks_scheduled_execution_seconds_sum')
def worker_name(l):
    cls = lab(l, 'code_namespace') or lab(l, 'code_class')
    method = lab(l, 'code_function') or lab(l, 'method')
    return f"{cls.rsplit('.', 1)[-1]}.{method}" if cls else method

for l, c in d_count.items():
    name = worker_name(l)
    if 'FanMeeting' not in name or c <= 0:
        continue
    w = workers.setdefault(name, {'runs': 0, 'sumMs': 0.0, 'maxMs': 0.0, 'errors': 0})
    w['runs'] += c
    w['sumMs'] += d_sum.get(l, 0) * 1000
    if lab(l, 'outcome') not in ('', 'SUCCESS'):
        w['errors'] += c
for name, w in workers.items():
    mx = gauge_max('tasks_scheduled_execution_seconds_max', lambda x, name=name: worker_name(x) == name)
    w['maxMs'] = (mx or 0) * 1000
    w['avgMs'] = w.pop('sumMs') / w['runs']

def iso(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat().replace('+00:00', 'Z')

fmt = lambda v: '-' if v is None else f'{v:.1f}'
requested = (f" (requested {os.environ.get('FROM') or '-'} → {os.environ.get('UNTIL') or '-'})"
             if os.environ.get('FROM') or os.environ.get('UNTIL') else '')
boundary = f" (boundary {from_snap or '-'}.prom → {until_snap or '-'}.prom)" if from_snap or until_snap else ''
lines = ['',
         f'server metrics counted {iso(first_ts)} → {iso(last_ts)} ({elapsed:.1f}s, {len(snaps)} snapshots){boundary}{requested}']
if problems:
    lines.append(f'  ⚠ INVALID: {"; ".join(problems)}. Do not read the numbers below as throughput.')
lines.append('  endpoint (server-side, from http_server_requests histogram)')
endpoints = []
for ep in sorted(counts, key=lambda e: -counts[e]):
    ok = success_status(ep)
    ok_count = by_status.get(ep, {}).get(ok, 0)
    row = {'endpoint': ep, 'requests': counts[ep], 'avgRate': counts[ep] / elapsed if elapsed else None,
           'peakRate': peak_rate(ep), 'successStatus': ok, 'successRequests': ok_count,
           'successAvgRate': ok_count / elapsed if elapsed else None, 'successPeakRate': peak_rate(ep, ok),
           'p50Ms': percentile(ep, 0.5), 'p95Ms': percentile(ep, 0.95),
           'p99Ms': percentile(ep, 0.99), 'maxMs': max_seen(ep), 'byStatus': by_status.get(ep, {})}
    endpoints.append(row)
    status = ' '.join(f'{k}:{int(v)}' for k, v in sorted(row['byStatus'].items()))
    lines.append(f"    {ep}")
    lines.append(f"      all requests {int(row['requests'])} ({status})  avg {fmt(row['avgRate'])}/s  peak {fmt(row['peakRate'])}/s")
    lines.append(f"      success ({ok}) {int(ok_count)}  avg {fmt(row['successAvgRate'])}/s  peak {fmt(row['successPeakRate'])}/s")
    lines.append(f"      p50 {fmt(row['p50Ms'])}ms  p95 {fmt(row['p95Ms'])}ms  p99 {fmt(row['p99Ms'])}ms  max {fmt(row['maxMs'])}ms")
if not endpoints:
    lines.append('    (no /api requests in range)')

resources = {
    'hikariActiveMax': gauge_max('hikaricp_connections_active'),
    'hikariMax': gauge_max('hikaricp_connections_max'),
    'hikariPendingMax': gauge_max('hikaricp_connections_pending'),
    'hikariTimeouts': hikari_timeouts,
    'hikariAcquireMaxMs': (gauge_max('hikaricp_connections_acquire_seconds_max') or 0) * 1000,
    'tomcatBusyMax': gauge_max('tomcat_threads_busy_threads'),
    'tomcatMaxThreads': gauge_max('tomcat_threads_config_max_threads'),
    'processCpuMax': gauge_max('process_cpu_usage'),
    'systemCpuMax': gauge_max('system_cpu_usage'),
    'cpuCount': gauge_max('system_cpu_count'),
    'heapUsedMaxMb': None if heap is None else heap / 1024 / 1024,
    'liveThreadsMax': gauge_max('jvm_threads_live_threads'),
}
pct = lambda v: '-' if v is None else f'{v * 100:.0f}%'
lines += [
    '  resources (max over snapshots)',
    f"    hikari active {fmt(resources['hikariActiveMax'])}/{fmt(resources['hikariMax'])}  pending {fmt(resources['hikariPendingMax'])}"
    f"  timeouts {int(hikari_timeouts)}  acquire max {fmt(resources['hikariAcquireMaxMs'])}ms",
    f"    tomcat busy {fmt(resources['tomcatBusyMax'])}/{fmt(resources['tomcatMaxThreads'])}"
    f"  cpu process {pct(resources['processCpuMax'])} system {pct(resources['systemCpuMax'])} ({fmt(resources['cpuCount'])} cores)",
    f"    heap used {fmt(resources['heapUsedMaxMb'])}MB  threads {fmt(resources['liveThreadsMax'])}",
    '  fanmeeting workers (@Scheduled, runs in range)',
]
for name, w in sorted(workers.items()):
    lines.append(f"    {name.ljust(36)} runs {int(w['runs'])}  errors {int(w['errors'])}  avg {w['avgMs']:.1f}ms  max {w['maxMs']:.1f}ms")
if not workers:
    lines.append('    (none recorded)')
lines.append('  note: max values are per-scrape gauges; a spike shorter than INTERVAL can be missed. EC2 CPU credits are in CloudWatch.')
lines.append('')
print('\n'.join(lines))

with open(os.path.join(d, 'summary.json'), 'w', encoding='utf-8') as fh:
    json.dump({'valid': not problems, 'problems': problems, 'fromSnapshot': from_snap, 'untilSnapshot': until_snap,
               'from': iso(first_ts), 'until': iso(last_ts), 'requestedFrom': os.environ.get('FROM') or None,
               'requestedUntil': os.environ.get('UNTIL') or None, 'elapsedSeconds': elapsed, 'snapshots': len(snaps),
               'appRestarted': restarted, 'endpoints': endpoints, 'resources': resources, 'workers': workers},
              fh, ensure_ascii=False, indent=2)
print(f'summary: {os.path.join(d, "summary.json")}')
if problems:
    sys.exit(3)
PY
}

if [[ "${1:-}" == "summary" ]]; then
  summarize "${2:?usage: fanmeeting-metrics.sh summary <dir>}"
  exit $?
fi
SNAP=0
if [[ "${1:-}" == "snap" ]]; then SNAP=1; shift; fi
[[ $# -eq 0 ]] || die "unknown argument: $1 (no argument = record, or: snap, summary <dir>)"

MANAGEMENT_URL="${MANAGEMENT_URL:-http://perf-app:8081}"
MANAGEMENT_URL="${MANAGEMENT_URL%/}"
INTERVAL="${INTERVAL:-5}"
DURATION="${DURATION:-0}"   # 초. 0이면 Ctrl-C까지
OUT_DIR="${OUT_DIR:-.ai-output/perf/metrics-$(date -u +%Y%m%dT%H%M%SZ)}"
: "${PERF_ALLOWED_APP_HOSTS:?PERF_ALLOWED_APP_HOSTS is required (comma separated, e.g. perf-app)}"
[[ "$INTERVAL" =~ ^[0-9]+$ && "$INTERVAL" -ge 1 ]] || die "INTERVAL must be whole seconds >= 1"
[[ "$DURATION" =~ ^[0-9]+$ ]] || die "DURATION must be whole seconds"

# fanmeeting-apply.js와 같은 호스트 규칙.
host="${MANAGEMENT_URL#*://}"; host="${host%%[:/]*}"
[[ "$host" =~ ^[Pp][Ee][Rr][Ff]- ]] || die "host $host must start with perf- (e.g. perf-app)"
allowed=0
IFS=',' read -ra hosts <<< "$PERF_ALLOWED_APP_HOSTS"
for h in "${hosts[@]}"; do [[ "$(echo "$h" | xargs)" == "$host" ]] && allowed=1; done
[[ $allowed -eq 1 ]] || die "host $host is not in PERF_ALLOWED_APP_HOSTS"
[[ "$host" =~ [Pp][Rr][Oo][Dd] ]] && die "host $host looks like production"

resolve=()
if [[ -n "${PERF_APP_ADDR:-}" ]]; then
  port="${MANAGEMENT_URL##*:}"; port="${port%%/*}"
  resolve=(--resolve "$host:$port:$PERF_APP_ADDR")
fi
scrape() { curl -sf --max-time 4 ${resolve[@]+"${resolve[@]}"} "$MANAGEMENT_URL/actuator/prometheus" -o "$1"; }

mkdir -p "$OUT_DIR"

# 수집 잠금. mkdir은 원자적이라 macOS·Linux 모두에서 쓴다. 주인이 죽은 잠금(pid가 없는 프로세스, 또는 pid를
# 쓰기 전에 죽어 LOCK_WAIT보다 오래된 빈 잠금)은 치우고 다시 잡는다.
LOCK_WAIT="${LOCK_WAIT:-15}"
[[ "$LOCK_WAIT" =~ ^[0-9]+$ && "$LOCK_WAIT" -ge 1 ]] || die "LOCK_WAIT must be whole seconds >= 1"
lock_dir="$OUT_DIR/.scrape.lock"
have_lock=0
lock_age() { python3 -c 'import os,sys,time; print(int(time.time() - os.path.getmtime(sys.argv[1])))' "$lock_dir" 2>/dev/null || echo 0; }
acquire_lock() {
  local deadline=$(( $(date +%s) + LOCK_WAIT )) owner
  until mkdir "$lock_dir" 2>/dev/null; do
    owner="$(cat "$lock_dir/pid" 2>/dev/null || true)"
    if [[ -n "$owner" ]] && ! kill -0 "$owner" 2>/dev/null; then rm -rf "$lock_dir"; continue; fi
    if [[ -z "$owner" && $(lock_age) -gt $LOCK_WAIT ]]; then rm -rf "$lock_dir"; continue; fi
    [[ $(date +%s) -lt $deadline ]] || return 1
    sleep 0.05
  done
  echo $$ > "$lock_dir/pid"
  have_lock=1
}
release_lock() {
  if [[ $have_lock -eq 1 ]]; then rm -rf "$lock_dir"; have_lock=0; fi
}
trap release_lock EXIT

# 잠금 안에서 한 장 저장하고 이름(epoch ms)을 LAST_SNAPSHOT에 둔다. 0 성공, 1 수집 실패, 2 잠금 대기 실패.
# 잠금 상태(have_lock)를 이 셸이 알아야 하므로 $(...) 하위 셸에서 부르지 않는다.
LAST_SNAPSHOT=""
scrape_one() {
  acquire_lock || { echo "metrics: could not get the scrape lock within ${LOCK_WAIT}s ($lock_dir)" >&2; return 2; }
  local tmp="$OUT_DIR/.scrape.$$.tmp" ms
  if scrape "$tmp"; then
    # 응답을 다 받은 시각으로 이름을 붙인다. 서버는 응답을 만들 때의 값을 주므로, 느린 수집도 실제 시각에 가깝다.
    # 잠금 안이라 이름 순서 = 수집 순서다. 같은 ms가 이미 있으면 1ms씩 민다.
    ms=$(python3 -c 'import time; print(int(time.time() * 1000))')
    while [[ -e "$OUT_DIR/$ms.prom" ]]; do ms=$((ms + 1)); done
    mv "$tmp" "$OUT_DIR/$ms.prom"
    release_lock
    LAST_SNAPSHOT="$ms"
    return 0
  fi
  rm -f "$tmp"
  release_lock
  return 1
}

if [[ $SNAP -eq 1 ]]; then
  # 단계 경계에서 한 장만 저장한다(fanmeeting-s1.sh가 단계 부하 시작 전과 끝난 뒤에 부른다).
  trap 'release_lock; exit 143' TERM
  trap 'release_lock; exit 130' INT
  scrape_one || die "snapshot failed at $(date -u +%H:%M:%S)"
  echo "metrics: snapshot $LAST_SNAPSHOT $OUT_DIR/$LAST_SNAPSHOT.prom"
  exit 0
fi
probe="$OUT_DIR/.probe"
scrape "$probe" || die "$MANAGEMENT_URL/actuator/prometheus did not answer 200 (perf profile app?)"
grep -q '^http_server_requests_seconds_bucket' "$probe" \
  || echo "metrics: warning: no http_server_requests_seconds_bucket yet (histogram off or no requests). p95/p99 need it." >&2
rm -f "$probe"

stop=0
trap 'stop=1' INT TERM
echo "metrics: recording $MANAGEMENT_URL every ${INTERVAL}s into $OUT_DIR $( [[ $DURATION -gt 0 ]] && echo "for ${DURATION}s" || echo "until Ctrl-C")"
start=$(date +%s)
fails=0
while [[ $stop -eq 0 ]]; do
  if scrape_one; then
    fails=0
  else
    fails=$((fails + 1))
    echo "metrics: scrape failed at $(date -u +%H:%M:%S) ($fails in a row)" >&2
  fi
  [[ $DURATION -gt 0 && $(( $(date +%s) - start )) -ge $DURATION ]] && break
  for ((i = 0; i < INTERVAL * 10 && stop == 0; i++)); do sleep 0.1; done
done
trap - INT TERM
# 끝내는 순간의 값도 남긴다.
scrape_one || echo "metrics: final scrape failed" >&2
echo "metrics: stopped. $(ls "$OUT_DIR"/*.prom 2>/dev/null | wc -l | xargs) snapshots"
summarize "$OUT_DIR"
