#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'HELP'
Usage: perf/scenarios/run-ticketing.sh SCENARIO USERS

SCENARIO:
  simultaneous               좌석 조회 1회 (동시 진입)
  page                       좌석 화면 진입: 좌석·구매 가능 정보 병렬 조회 (POLL_ENTRY로 분산/동시)
  hot, overlap               인기 좌석 또는 겹치는 좌석 선점 경쟁 (동시 진입)
  distributed, replay        서로 다른 좌석 선점 또는 멱등성 재요청 (동시 진입)
  mixed, mixed-distributed   화면 진입 80% + 선점 20% (인기 좌석 또는 분산)
  checkout                   서로 다른 좌석 선점 → checkout → 결제 준비 (승인 없음, 동시 진입)

USERS는 전체 테스트 사용자 수다. 예: run-ticketing.sh page 100
필수 환경변수: BASE_URL, PERFORMANCE_ID, MANAGEMENT_URL, PERF_ALLOWED_APP_HOSTS
인증: USERS_CSV 또는 SETUP=self (시드 계정 로그인)
선택 환경변수: MAX_DURATION, REFRESH_COUNT, REFRESH_INTERVAL_SECONDS, HOT_SEATS,
              POLL_ENTRY (page/mixed), START_DELAY_SECONDS (simultaneous/page/mixed), CHECKOUT_PAGE_ENTRY (checkout),
              ARRIVAL_RATE·ARRIVAL_SECONDS (simultaneous/page 도착률, USERS = 둘의 곱),
              SEAT_CHECK=full|light (light: 좌석 본문 파싱 생략),
              RELOAD_COUNT·RELOAD_INTERVAL_SECONDS (page 다시 진입(F5) 반복), SEAT_ENCODING, MANAGEMENT_URL, K6_BIN
HELP
}
if [[ "${1:-}" == '-h' || "${1:-}" == '--help' ]]; then usage; exit 0; fi
[[ $# == 2 ]] || { usage >&2; exit 2; }
scenario="$1"
case "$scenario" in simultaneous|page|hot|overlap|distributed|replay|mixed|mixed-distributed|checkout) ;; *) echo "알 수 없는 시나리오: $scenario" >&2; usage >&2; exit 2;; esac
[[ "$2" =~ ^[1-9][0-9]{0,5}$ ]] || { echo 'USERS는 1~999999 사이의 양의 정수여야 합니다.' >&2; exit 2; }
users="$2"
for name in BASE_URL PERFORMANCE_ID MANAGEMENT_URL PERF_ALLOWED_APP_HOSTS; do
  [[ -n "${!name:-}" ]] || { echo "필수 환경변수가 비어 있습니다: $name" >&2; exit 2; }
done
[[ "$PERFORMANCE_ID" =~ ^[1-9][0-9]*$ ]] || { echo 'PERFORMANCE_ID는 양의 정수여야 합니다.' >&2; exit 2; }
if [[ "${SETUP:-csv}" != self ]]; then
  [[ -n "${USERS_CSV:-}" && -f "$USERS_CSV" && -r "$USERS_CSV" ]] || { echo 'USERS_CSV 파일을 읽을 수 없습니다.' >&2; exit 2; }
fi
[[ "${SETUP:-csv}" != self ]] || { echo '계정 중복 방지를 위해 USERS_CSV를 사용하세요. SETUP=self는 지원하지 않습니다.' >&2; exit 2; }
K6_BIN="${K6_BIN:-k6}"
command -v "$K6_BIN" >/dev/null || { echo "k6를 찾을 수 없습니다: $K6_BIN" >&2; exit 2; }
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${SETUP:-csv}" != self ]]; then
  export USERS_CSV="$(cd "$(dirname "$USERS_CSV")" && pwd)/$(basename "$USERS_CSV")"
fi
export PARTICIPANTS="$users" VUS="$users"
export HOT_SEATS="${HOT_SEATS:-1}" MAX_DURATION="${MAX_DURATION:-5m}"
export MIXED_HOLD=hot
case "$scenario" in
  simultaneous) export MODE=poll ;;
  page) export MODE=page POLL_ENTRY="${POLL_ENTRY:-spread}" ;;
  hot|overlap|distributed|replay|checkout) export MODE="$scenario" ;;
  mixed|mixed-distributed)
    readers=$(( users * 4 / 5 )); holders=$(( users - readers ))
    (( readers > 0 && holders > 0 )) || { echo '혼합 시나리오는 최소 2명이 필요합니다.' >&2; exit 2; }
    export MODE=mixed PARTICIPANTS="$readers" HOLD_PARTICIPANTS="$holders" POLL_ENTRY="${POLL_ENTRY:-spread}"
    [[ "$scenario" == mixed-distributed ]] && export MIXED_HOLD=distributed
    ;;
esac
mkdir -p "$root/.ai-output/perf"
run_dir="$root/.ai-output/perf/ticketing-$scenario"
mkdir -p "$run_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
summary_path="$run_dir/summary-$timestamp.json"
while [[ -e "$summary_path" ]]; do
  sleep 1
  timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
  summary_path="$run_dir/summary-$timestamp.json"
done
export RUN_ID="${scenario}-${timestamp//[^a-zA-Z0-9_-]/}"
export SUMMARY_PATH="$summary_path"
k6_log="$run_dir/k6-$timestamp.log"
(umask 077; : > "$k6_log")
export K6_WEB_DASHBOARD="${K6_WEB_DASHBOARD:-true}"
export K6_WEB_DASHBOARD_PORT="${K6_WEB_DASHBOARD_PORT:-5665}"
export K6_WEB_DASHBOARD_EXPORT="${K6_WEB_DASHBOARD_EXPORT:-$run_dir/report-$timestamp.html}"
printf '시나리오: %s | 사용자: %s | 실행 ID: %s\n' "$scenario" "$users" "$RUN_ID"
if [[ "$MODE" == mixed ]]; then printf '조회: %s명 | 선점: %s명\n' "$PARTICIPANTS" "$HOLD_PARTICIPANTS"; fi
if [[ "$MODE" != poll && "$MODE" != page ]]; then printf '선점 요청으로 테스트 데이터가 변경됩니다. 실험 간 초기 상태를 확인하세요.\n'; fi
printf '요약 JSON: %s\nHTML 보고서: %s\nk6 로그: %s\n실행 중 대시보드: http://localhost:%s\n' "$SUMMARY_PATH" "$K6_WEB_DASHBOARD_EXPORT" "$k6_log" "$K6_WEB_DASHBOARD_PORT"
command -v python3 >/dev/null || { echo 'python3를 찾을 수 없습니다.' >&2; exit 2; }
if [[ -n "${TICKETING_K6_CONFIG:-}" && ! -r "$TICKETING_K6_CONFIG" ]]; then
  echo 'k6 설정 파일을 읽을 수 없습니다.' >&2; exit 2
fi
ready_file="$(mktemp "$run_dir/.prometheus-ready-XXXXXXXX")"
rm -f "$ready_file"
mac_collector_pid=""
PROMETHEUS_READY_FILE="$ready_file" python3 "$root/perf/scenarios/ticketing/collect-prometheus-csv.py" "$scenario" &
collector_pid=$!
cleanup_collector() {
  if [[ -n "$mac_collector_pid" ]]; then
    kill -TERM "$mac_collector_pid" 2>/dev/null || true
    wait "$mac_collector_pid" 2>/dev/null || true
  fi
  if kill -0 "$collector_pid" 2>/dev/null; then kill -TERM "$collector_pid" 2>/dev/null || true; fi
  wait "$collector_pid" 2>/dev/null || true
  rm -f "$ready_file"
}
trap cleanup_collector EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
for ((i=0; i<120; i++)); do
  [[ -f "$ready_file" ]] && break
  if ! kill -0 "$collector_pid" 2>/dev/null; then
    echo 'Prometheus 수집을 시작하지 못해 테스트를 중단합니다.' >&2; exit 1
  fi
  sleep 0.1
done
[[ -f "$ready_file" ]] || { echo 'Prometheus 수집 시작 시간이 초과되었습니다.' >&2; exit 1; }
printf 'Prometheus CSV: %s\n' "$(cat "$ready_file")"
ROW_OFFSET="$(python3 "$root/perf/scenarios/ticketing/allocate-users.py" "$USERS_CSV" "$users" "$BASE_URL" "$PERFORMANCE_ID" "$RUN_ID")"
export ROW_OFFSET
printf 'CSV 계정 행: %s~%s (헤더 제외)\n' "$((ROW_OFFSET + 1))" "$((ROW_OFFSET + users))"
if [[ "$(uname -s)" == Darwin ]]; then
  mac_metrics_path="$run_dir/mac-$timestamp.csv"
  python3 "$root/perf/scenarios/ticketing/collect-mac-csv.py" "$mac_metrics_path" "$$" "$BASE_URL" &
  mac_collector_pid=$!
  printf 'Mac 지표 CSV: %s\n' "$mac_metrics_path"
else
  printf 'Mac 지표 수집은 macOS에서만 실행됩니다.\n' >&2
fi
if [[ -n "${TICKETING_K6_CONFIG:-}" ]]; then
  "$K6_BIN" run --config "$TICKETING_K6_CONFIG" "$root/perf/scenarios/ticketing/${scenario}.js" 2>&1 | tee -a "$k6_log"
else
  "$K6_BIN" run "$root/perf/scenarios/ticketing/${scenario}.js" 2>&1 | tee -a "$k6_log"
fi
