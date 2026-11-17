#!/usr/bin/env bash
# 팬미팅 측정 중 서버 지표 기록. Grafana 없이 서버 지연·처리량·연결 풀·CPU를 남긴다. 사용법은 perf/scenarios/fanmeeting.md.
#
#   fanmeeting-metrics.sh                 # 기록 시작. Ctrl-C(또는 DURATION 경과)로 끝내면 요약을 출력한다
#   fanmeeting-metrics.sh summary <dir>   # 이미 기록한 폴더를 다시 요약한다. FROM, UNTIL로 구간을 자를 수 있다
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
    sys.exit(f'metrics: need at least 2 snapshots in range, found {len(snaps)}')

first_ts, first = snaps[0]
last_ts, last = snaps[-1]
elapsed = (last_ts - first_ts) / 1000

# 앱이 재시작되면 누적 카운터가 0부터 다시 시작해 구간 차이가 틀린다.
starts = {m.get(('process_start_time_seconds', ())) for _, m in snaps} - {None}
restarted = len(starts) > 1

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

# 스냅샷 사이 구간별 초당 처리량의 최댓값(엔드포인트별).
def peak_rate(ep):
    best = 0.0
    for (t0, m0), (t1, m1) in zip(snaps, snaps[1:]):
        dt = (t1 - t0) / 1000
        if dt <= 0:
            continue
        c = sum(v - m0.get((n, l), 0) for (n, l), v in m1.items()
                if n == 'http_server_requests_seconds_count' and endpoint(l) == ep)
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
lines = ['',
         f'server metrics {iso(first_ts)} → {iso(last_ts)} ({elapsed:.0f}s, {len(snaps)} snapshots)'
         + ('  ⚠ counters went down: app restarted in this range, numbers are wrong' if restarted else ''),
         '  endpoint (server-side, from http_server_requests histogram)']
endpoints = []
for ep in sorted(counts, key=lambda e: -counts[e]):
    row = {'endpoint': ep, 'requests': counts[ep], 'avgRate': counts[ep] / elapsed if elapsed else None,
           'peakRate': peak_rate(ep), 'p50Ms': percentile(ep, 0.5), 'p95Ms': percentile(ep, 0.95),
           'p99Ms': percentile(ep, 0.99), 'maxMs': max_seen(ep), 'byStatus': by_status.get(ep, {})}
    endpoints.append(row)
    status = ' '.join(f'{k}:{int(v)}' for k, v in sorted(row['byStatus'].items()))
    lines.append(f"    {ep}")
    lines.append(f"      requests {int(row['requests'])} ({status})  avg {fmt(row['avgRate'])}/s  peak {fmt(row['peakRate'])}/s")
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
    json.dump({'from': iso(first_ts), 'until': iso(last_ts), 'elapsedSeconds': elapsed, 'snapshots': len(snaps),
               'appRestarted': restarted, 'endpoints': endpoints, 'resources': resources, 'workers': workers},
              fh, ensure_ascii=False, indent=2)
print(f'summary: {os.path.join(d, "summary.json")}')
PY
}

if [[ "${1:-}" == "summary" ]]; then
  summarize "${2:?usage: fanmeeting-metrics.sh summary <dir>}"
  exit 0
fi
[[ $# -eq 0 ]] || die "unknown argument: $1 (no argument = record, or: summary <dir>)"

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
  now_ms=$(python3 -c 'import time; print(int(time.time() * 1000))')
  if scrape "$OUT_DIR/$now_ms.prom.tmp"; then
    mv "$OUT_DIR/$now_ms.prom.tmp" "$OUT_DIR/$now_ms.prom"
    fails=0
  else
    rm -f "$OUT_DIR/$now_ms.prom.tmp"
    fails=$((fails + 1))
    echo "metrics: scrape failed at $(date -u +%H:%M:%S) ($fails in a row)" >&2
  fi
  [[ $DURATION -gt 0 && $(( $(date +%s) - start )) -ge $DURATION ]] && break
  for ((i = 0; i < INTERVAL * 10 && stop == 0; i++)); do sleep 0.1; done
done
trap - INT TERM
# 끝내는 순간의 값도 남긴다.
now_ms=$(python3 -c 'import time; print(int(time.time() * 1000))')
scrape "$OUT_DIR/$now_ms.prom" || rm -f "$OUT_DIR/$now_ms.prom"
echo "metrics: stopped. $(ls "$OUT_DIR"/*.prom 2>/dev/null | wc -l | xargs) snapshots"
summarize "$OUT_DIR"
