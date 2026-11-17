#!/usr/bin/env bash
# 팬미팅 S1 한 회: 행사 하나로 측정 흐름 전체를 명령 하나로 돈다. 사용법은 perf/scenarios/fanmeeting.md.
#
#   지표 기록 시작 → 행사 생성 → 응모 몰림(A, START_OFFSET=-2s면 B) → 점검 1
#   → 마감 앞뒤 응모(C) → 추첨 확정 대기 → 점검 2(D) → 결과 조회 폭주 → 점검 3 → 지표 요약(단계별)
#
# 결과는 RUN_DIR 한 폴더에 모인다. 측정한 사람은 이 폴더를 통째로 넘기면 된다(토큰·비밀번호는 들어가지 않는다).
# 약 15~20분 걸린다(OPEN_IN 3분 + 응모 기간 10분 + 추첨 + 조회).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
die() { echo "fanmeeting-s1: $*" >&2; exit 2; }
say() { echo "[$(date -u +%H:%M:%S)] $*"; }
now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# ---------------------------------------------------------------- 입력
export BASE_URL="${BASE_URL:-http://perf-app:8080}"
export MANAGEMENT_URL="${MANAGEMENT_URL:-http://perf-app:8081}"
: "${PERF_ALLOWED_APP_HOSTS:?PERF_ALLOWED_APP_HOSTS is required (comma separated, e.g. perf-app)}"
export PERF_ALLOWED_APP_HOSTS
SEED_OUT="$(cd "${SEED_OUT:-$REPO/perf/seed/out}" 2>/dev/null && pwd)" || die "SEED_OUT not found (perf/seed/out)"
export SEED_OUT
[[ -f "$SEED_OUT/users.csv" && -f "$SEED_OUT/staff.csv" ]] || die "$SEED_OUT needs users.csv and staff.csv (from perf/seed/seed.sh)"

PARTICIPANTS="${PARTICIPANTS:-1000}"
PEAK_RATE="${PEAK_RATE:-1000}"
RAMP="${RAMP:-5s}"
HOLD="${HOLD:-}"                     # 비우면 참여자 수와 도착률로 계산
START_OFFSET="${START_OFFSET:-2s}"   # A: 2s(Open Worker 뒤), B: -2s(오픈 직전부터)
CLOSE_PARTICIPANTS="${CLOSE_PARTICIPANTS:-500}"   # C. 0이면 건너뜀
CLOSE_RATE="${CLOSE_RATE:-}"            # 비우면 마감 3초 전부터 약 6초에 걸쳐 보내도록 자동
RESULT="${RESULT:-true}"             # 결과 조회 폭주. false면 건너뜀
RESULT_RATE="${RESULT_RATE:-$PEAK_RATE}"
DRAW_TIMEOUT="${DRAW_TIMEOUT:-600}"  # 마감 뒤 추첨 확정을 기다리는 최대 초
METRICS_INTERVAL="${METRICS_INTERVAL:-5}"
K6="${K6:-docker}"                   # docker 또는 local(설치된 k6)
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.4.0}"
INVARIANTS_SQL="${INVARIANTS_SQL:-$REPO/backend/src/test/resources/harness/fanmeeting-invariants.sql}"
RUN_DIR="${RUN_DIR:-$REPO/.ai-output/perf/s1-$(date -u +%m%d-%H%M)-p${PARTICIPANTS}-r${PEAK_RATE}}"
for n in PARTICIPANTS PEAK_RATE CLOSE_PARTICIPANTS RESULT_RATE DRAW_TIMEOUT METRICS_INTERVAL; do
  [[ "${!n}" =~ ^[0-9]+$ ]] || die "$n must be a whole number: ${!n}"
done
[[ $PARTICIPANTS -ge 1 && $PEAK_RATE -ge 1 && $RESULT_RATE -ge 1 ]] || die "PARTICIPANTS, PEAK_RATE, RESULT_RATE must be >= 1"
[[ -e "$RUN_DIR" ]] && die "RUN_DIR already exists: $RUN_DIR"

# 호스트 규칙은 각 스크립트가 다시 검사한다. 여기서는 docker에 넘길 호스트 이름과 주소만 정한다.
host_of() { local h="${1#*://}"; h="${h%%/*}"; echo "${h%%:*}"; }
HOSTS=("$(host_of "$BASE_URL")")
[[ "$(host_of "$MANAGEMENT_URL")" != "${HOSTS[0]}" ]] && HOSTS+=("$(host_of "$MANAGEMENT_URL")")
# K6_ADD_HOST: 컨테이너 안에서 perf- 이름이 가리킬 주소. 비우면 PERF_APP_ADDR, 그것도 없으면 이 머신의 /etc/hosts로 찾는다.
if [[ "$K6" == "docker" ]]; then
  K6_ADD_HOST="${K6_ADD_HOST:-${PERF_APP_ADDR:-}}"
  if [[ -z "$K6_ADD_HOST" ]]; then
    K6_ADD_HOST="$(python3 -c 'import socket,sys; print(socket.gethostbyname(sys.argv[1]))' "${HOSTS[0]}" 2>/dev/null)" \
      || die "cannot resolve ${HOSTS[0]}. Add it to /etc/hosts or set PERF_APP_ADDR (IP)"
  fi
  command -v docker >/dev/null || die "docker not found (or set K6=local)"
else
  command -v k6 >/dev/null || die "k6 not found (or set K6=docker)"
fi

CURL=(curl -sS --max-time 10)
if [[ -n "${PERF_APP_ADDR:-}" ]]; then
  hp="${BASE_URL#*://}"; hp="${hp%%/*}"
  CURL+=(--connect-to "${hp}:${PERF_APP_ADDR}:${hp##*:}")
fi

# 참여자 계정: A는 USER 행 앞에서 PARTICIPANTS개, C는 그 다음부터. 모자라면 C를 줄이거나 건너뛴다.
available="$(awk -F',' 'NR == 1 { for (i = 1; i <= NF; i++) if ($i == "role") r = i; next } $r == "USER" { n++ } END { print n + 0 }' "$SEED_OUT/users.csv")"
[[ $PARTICIPANTS -le $available ]] || die "users.csv has $available USER rows, PARTICIPANTS is $PARTICIPANTS"
if [[ $((PARTICIPANTS + CLOSE_PARTICIPANTS)) -gt $available ]]; then
  CLOSE_PARTICIPANTS=$((available - PARTICIPANTS))
  say "C: only $CLOSE_PARTICIPANTS unused accounts left, C uses that many"
fi

# C는 마감을 가로질러야 한다: 마감 3초 전에 시작해 마감 3초 뒤쯤 계정이 바닥나게 도착률을 정한다.
if [[ -z "$CLOSE_RATE" ]]; then CLOSE_RATE=$(( (CLOSE_PARTICIPANTS + 5) / 6 )); [[ $CLOSE_RATE -ge 1 ]] || CLOSE_RATE=1; fi
[[ "$CLOSE_RATE" =~ ^[0-9]+$ && $CLOSE_RATE -ge 1 ]] || die "CLOSE_RATE must be a whole number >= 1"

# 총 도착 수가 참여자보다 많도록 유지 시간을 정한다(램프 절반 + 유지 + 내려가는 5초의 절반).
secs() { python3 -c 'import re,sys; m=re.fullmatch(r"(\d+)(ms|s|m)?", sys.argv[1]); print(int(m.group(1)) * {"ms": 0.001, "s": 1, "m": 60, None: 1}[m.group(2)])' "$1"; }
if [[ -z "$HOLD" ]]; then
  HOLD="$(python3 -c 'import math,sys; p,r,ramp=map(float,sys.argv[1:]); print(f"{max(5, math.ceil(p / r - ramp / 2 - 2.5) + 3)}s")' "$PARTICIPANTS" "$PEAK_RATE" "$(secs "$RAMP")")"
fi
RESULT_HOLD="${RESULT_HOLD:-$(python3 -c 'import math,sys; p,r=map(float,sys.argv[1:]); print(f"{max(10, math.ceil(p / r) + 10)}s")' "$PARTICIPANTS" "$RESULT_RATE")}"

DB_CHECK=0
if [[ -n "${DB_HOST:-}" ]]; then
  [[ -f "$INVARIANTS_SQL" ]] || die "INVARIANTS_SQL not found: $INVARIANTS_SQL (pull latest dev, or give the path)"
  DB_CHECK=1
fi

mkdir -p "$RUN_DIR"
export EVENT_FILE="$RUN_DIR/event.env"
TIMELINE="$RUN_DIR/timeline.tsv"
printf 'phase\tstart\tend\texit\n' > "$TIMELINE"
exec > >(tee -a "$RUN_DIR/run.log") 2>&1

cat > "$RUN_DIR/conditions.txt" <<EOF
started_at=$(now_iso)
base_url=$BASE_URL management_url=$MANAGEMENT_URL k6=$K6 ${K6_IMAGE}
participants=$PARTICIPANTS peak_rate=$PEAK_RATE ramp=$RAMP hold=$HOLD start_offset=$START_OFFSET
close_participants=$CLOSE_PARTICIPANTS close_rate=$CLOSE_RATE
result=$RESULT result_rate=$RESULT_RATE result_hold=$RESULT_HOLD
winner_limit=${WINNER_LIMIT:-300} reserve_limit=${RESERVE_LIMIT:-100} fee_amount=${FEE_AMOUNT:-50000} open_in=${OPEN_IN:-3m}
db_check=$DB_CHECK metrics_interval=${METRICS_INTERVAL}s users_available=$available
EOF
say "run dir $RUN_DIR"
cat "$RUN_DIR/conditions.txt"

# ---------------------------------------------------------------- 지표 기록 (끝까지 백그라운드)
metrics_pid=""
stop_metrics() {
  if [[ -n "$metrics_pid" ]] && kill -0 "$metrics_pid" 2>/dev/null; then
    kill -TERM "$metrics_pid" 2>/dev/null || true
    wait "$metrics_pid" 2>/dev/null || true
  fi
  metrics_pid=""
}
trap 'stop_metrics' EXIT
OUT_DIR="$RUN_DIR/metrics" INTERVAL="$METRICS_INTERVAL" "$ROOT/fanmeeting-metrics.sh" > "$RUN_DIR/metrics.log" 2>&1 &
metrics_pid=$!
sleep 2
kill -0 "$metrics_pid" 2>/dev/null || die "metrics recorder stopped: $(tail -3 "$RUN_DIR/metrics.log")"

# ---------------------------------------------------------------- 단계 실행 도우미
phase_start=""
begin() { phase_start="$(now_iso)"; say "== $1"; }
finish() { printf '%s\t%s\t%s\t%s\n' "$1" "$phase_start" "$(now_iso)" "$2" >> "$TIMELINE"; }

run_k6() {  # run_k6 <phase> <script> [KEY=VALUE ...]
  local phase="$1" script="$2"; shift 2
  local envs=(BASE_URL="$BASE_URL" MANAGEMENT_URL="$MANAGEMENT_URL" PERF_ALLOWED_APP_HOSTS="$PERF_ALLOWED_APP_HOSTS"
              SUMMARY_PATH="@OUT@/$phase.json" "$@")
  local code=0
  if [[ "$K6" == "docker" ]]; then
    local args=(run --rm -v "$REPO/perf:/perf:ro" -v "$SEED_OUT:/seed:ro" -v "$RUN_DIR:/out")
    for h in "${HOSTS[@]}"; do args+=(--add-host "$h:$K6_ADD_HOST"); done
    args+=(-e SEED_OUT=/seed -e EVENT_FILE=/out/event.env)
    for e in "${envs[@]}"; do args+=(-e "${e//@OUT@//out}"); done
    docker "${args[@]}" "$K6_IMAGE" run -q "/perf/scenarios/$script" || code=$?
  else
    local kv=(SEED_OUT="$SEED_OUT" EVENT_FILE="$EVENT_FILE")
    for e in "${envs[@]}"; do kv+=("${e//@OUT@/$RUN_DIR}"); done
    env "${kv[@]}" k6 run -q "$ROOT/$script" || code=$?
  fi
  return $code
}

run_check() {  # run_check <phase> [SUMMARY_PATH]
  if [[ $DB_CHECK -eq 0 ]]; then
    say "check skipped (DB_HOST not set). Run fanmeeting-check.sh later with EVENT_FILE=$EVENT_FILE"
    return 0
  fi
  local code=0
  INVARIANTS_SQL="$INVARIANTS_SQL" SUMMARY_PATH="${2:-}" "$ROOT/fanmeeting-check.sh" > "$RUN_DIR/$1.txt" 2>&1 || code=$?
  cat "$RUN_DIR/$1.txt"
  return $code
}

failures=()
step() {  # step <phase> <command...>: 실패해도 흐름은 계속 가고 끝에 모아서 보여 준다
  local phase="$1"; shift
  begin "$phase"
  local code=0
  "$@" || code=$?
  finish "$phase" "$code"
  [[ $code -eq 0 ]] || { failures+=("$phase(exit $code)"); say "$phase exited $code"; }
}

# ---------------------------------------------------------------- 1. 행사 생성
begin prepare
"$ROOT/fanmeeting-prepare.sh" || { finish prepare $?; die "prepare failed"; }
finish prepare 0
# shellcheck disable=SC1090
source "$EVENT_FILE"

# ---------------------------------------------------------------- 2. 응모 몰림 (A, 또는 START_OFFSET<0이면 B)
apply_phase="apply-A"; [[ "$START_OFFSET" == -* ]] && apply_phase="apply-B"
say "k6 waits until $FM_ENTRY_OPENS_AT ${START_OFFSET} (participants $PARTICIPANTS, ${PEAK_RATE}/s, ramp $RAMP, hold $HOLD)"
step "$apply_phase" run_k6 "$apply_phase" fanmeeting-apply.js MODE=open START_OFFSET="$START_OFFSET" \
  PARTICIPANTS="$PARTICIPANTS" USER_OFFSET=0 PEAK_RATE="$PEAK_RATE" RAMP="$RAMP" HOLD="$HOLD" \
  ${PRE_VUS:+PRE_VUS=$PRE_VUS} ${MAX_VUS:+MAX_VUS=$MAX_VUS}
step check-1-apply run_check check-1-apply "$RUN_DIR/$apply_phase.json"

# ---------------------------------------------------------------- 3. 마감 앞뒤 (C)
if [[ $CLOSE_PARTICIPANTS -gt 0 ]]; then
  say "k6 waits until $FM_ENTRY_CLOSES_AT -3s (C: $CLOSE_PARTICIPANTS unused accounts, ${CLOSE_RATE}/s)"
  step apply-C run_k6 apply-C fanmeeting-apply.js MODE=close START_OFFSET=-3s \
    PARTICIPANTS="$CLOSE_PARTICIPANTS" USER_OFFSET="$PARTICIPANTS" PEAK_RATE="$CLOSE_RATE" RAMP=1s HOLD=8s
else
  say "C skipped (no unused accounts or CLOSE_PARTICIPANTS=0)"
fi

# ---------------------------------------------------------------- 4. 추첨 확정 대기 (D)
wait_draw() {
  local closes_ms deadline status=""
  closes_ms="$(python3 -c 'import sys,datetime as d; print(int(d.datetime.fromisoformat(sys.argv[1].replace("Z","+00:00")).timestamp()*1000))' "$FM_ENTRY_CLOSES_AT")"
  deadline=$(( closes_ms / 1000 + DRAW_TIMEOUT ))
  while :; do
    status="$("${CURL[@]}" "$BASE_URL/api/fan-meetings/$FM_EVENT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)"
    case "$status" in
      PAYMENT_IN_PROGRESS|RECRUITMENT_COMPLETED) say "event $FM_EVENT_ID is $status"; return 0 ;;
    esac
    [[ $(date +%s) -ge $deadline ]] && { say "draw not finalized ${DRAW_TIMEOUT}s after close (status ${status:-?})"; return 1; }
    sleep 5
  done
}
step wait-draw wait_draw
step check-2-draw run_check check-2-draw

# ---------------------------------------------------------------- 5. 결과 조회 폭주
if [[ "$RESULT" == "true" ]] && grep -q $'^wait-draw\t.*\t0$' "$TIMELINE"; then
  step result run_k6 result fanmeeting-result.js PARTICIPANTS="$PARTICIPANTS" USER_OFFSET=0 \
    PEAK_RATE="$RESULT_RATE" RAMP=5s HOLD="$RESULT_HOLD"
  step check-3-result run_check check-3-result
else
  say "result read storm skipped"
fi

# ---------------------------------------------------------------- 6. 지표 요약 (전체, 단계별)
stop_metrics
cat "$RUN_DIR/metrics.log"
while IFS=$'\t' read -r phase start end _; do
  [[ "$phase" == apply-* || "$phase" == result ]] || continue
  FROM="$start" UNTIL="$end" "$ROOT/fanmeeting-metrics.sh" summary "$RUN_DIR/metrics" > "$RUN_DIR/metrics-$phase.txt" 2>&1 || true
  mv -f "$RUN_DIR/metrics/summary.json" "$RUN_DIR/metrics-$phase.json" 2>/dev/null || true
done < <(tail -n +2 "$TIMELINE")
"$ROOT/fanmeeting-metrics.sh" summary "$RUN_DIR/metrics" > "$RUN_DIR/metrics-all.txt" 2>&1 || true

# ---------------------------------------------------------------- 7. 한 장 요약
python3 - "$RUN_DIR" > "$RUN_DIR/summary.txt" <<'PY'
import json, os, sys
d = sys.argv[1]
def load(name):
    p = os.path.join(d, name)
    return json.load(open(p, encoding='utf-8')) if os.path.exists(p) else None
env = dict(l.strip().split('=', 1) for l in open(os.path.join(d, 'event.env')) if '=' in l)
print(f"S1 event {env['FM_EVENT_ID']}  opens {env['FM_ENTRY_OPENS_AT']}  closes {env['FM_ENTRY_CLOSES_AT']}")
print(open(os.path.join(d, 'conditions.txt')).read().strip())
print('\nphases (UTC)')
for line in list(open(os.path.join(d, 'timeline.tsv')))[1:]:
    p, s, e, c = line.rstrip('\n').split('\t')
    print(f"  {p:16} {s} → {e}  exit {c}")
for name in ('apply-A', 'apply-B', 'apply-C'):
    s = load(f'{name}.json')
    if not s:
        continue
    o, b = s['outcomes'], s['byTimeSinceReference']
    print(f"\n{name}: applied {o['fm_applied']}, window_closed {o['fm_window_closed']}, busy {o['fm_busy']}, "
          f"5xx {o['fm_server_error']}, transport {o['fm_transport_error']}, dropped {o['dropped_iterations']}, "
          f"requests {s['requestsTotal']}/{s['conditions']['participants']}")
    print('  applied by time  ' + '  '.join(f"{k[11:]} {v}" for k, v in b.items() if k.startswith('fm_applied_')))
    print('  closed by time   ' + '  '.join(f"{k[17:]} {v}" for k, v in b.items() if k.startswith('fm_window_closed_')))
    if s['failedThresholds']:
        print('  thresholds FAILED: ' + ', '.join(s['failedThresholds']))
r = load('result.json')
if r:
    f = r['firstPass']
    print(f"\nresult: first pass winner {f['fm_result_first_winner']}, reserve {f['fm_result_first_reserve']}, "
          f"not_selected {f['fm_result_first_not_selected']}, pending {f['fm_result_first_pending']}, "
          f"with_order {f['fm_result_first_with_order']} (complete {f['complete']}); "
          f"order mismatch {r['outcomes']['fm_order_mismatch']}, not_found {r['outcomes']['fm_order_not_found']}, "
          f"5xx {r['outcomes']['fm_server_error']}")
    print("  (A participants only. With C, DB WINNER/RESERVE/orders also count C applicants, so first pass is lower)")
    if r['failedThresholds']:
        print('  thresholds FAILED: ' + ', '.join(r['failedThresholds']))
pct = lambda v: '-' if v is None else f'{v * 100:.0f}%'
for name in ('apply-A', 'apply-B', 'apply-C', 'result'):
    m = load(f'metrics-{name}.json')
    if not m:
        continue
    print(f"\nserver {name} ({m['from']} → {m['until']})")
    for e in m['endpoints']:
        fmt = lambda v: '-' if v is None else f'{v:.1f}'
        print(f"  {e['endpoint']}: {int(e['requests'])} req, peak {fmt(e['peakRate'])}/s, "
              f"p95 {fmt(e['p95Ms'])}ms, p99 {fmt(e['p99Ms'])}ms, max {fmt(e['maxMs'])}ms")
    res = m['resources']
    print(f"  hikari active max {res['hikariActiveMax']}/{res['hikariMax']}, pending max {res['hikariPendingMax']}, "
          f"timeouts {res['hikariTimeouts']}; cpu process max {pct(res['processCpuMax'])}, system max {pct(res['systemCpuMax'])}")
for name in ('check-1-apply', 'check-2-draw', 'check-3-result'):
    p = os.path.join(d, f'{name}.txt')
    if os.path.exists(p):
        lines = [l.rstrip() for l in open(p) if l.strip()]
        keep = [l for l in lines if any(k in l for k in ('FAILED', 'applications', 'k6 201', 'started→completed', 'selections', 'fanmeeting-check'))]
        print(f"\n{name}\n  " + '\n  '.join(keep))
PY
echo
cat "$RUN_DIR/summary.txt"
echo
if [[ ${#failures[@]} -gt 0 ]]; then
  say "finished with problems: ${failures[*]}"
  say "send this folder: $RUN_DIR"
  exit 1
fi
say "finished. send this folder: $RUN_DIR"
