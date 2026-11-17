#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'HELP'
Usage: perf/scenarios/run-ticketing.sh SCENARIO USERS

SCENARIO:
  spread, simultaneous       좌석 조회 (3초 폴링)
  hot, overlap               인기 좌석 또는 겹치는 좌석 선점 경쟁
  distributed, replay        서로 다른 좌석 선점 또는 멱등성 재요청
  mixed, mixed-distributed   조회 80% + 선점 20% (인기 좌석 또는 분산)

USERS는 전체 테스트 사용자 수다. 예: run-ticketing.sh spread 100
필수 환경변수: BASE_URL, PERFORMANCE_ID
인증: USERS_CSV 또는 SETUP=self (시드 계정 로그인)
선택 환경변수: DURATION, MAX_DURATION, HOT_SEATS, HOLD_DELAY_SECONDS,
              POLL_ENTRY (mixed만), MANAGEMENT_URL, K6_BIN
HELP
}
if [[ "${1:-}" == '-h' || "${1:-}" == '--help' ]]; then usage; exit 0; fi
[[ $# == 2 ]] || { usage >&2; exit 2; }
scenario="$1"
case "$scenario" in spread|simultaneous|hot|overlap|distributed|replay|mixed|mixed-distributed) ;; *) echo "알 수 없는 시나리오: $scenario" >&2; usage >&2; exit 2;; esac
[[ "$2" =~ ^[1-9][0-9]{0,5}$ ]] || { echo 'USERS는 1~999999 사이의 양의 정수여야 합니다.' >&2; exit 2; }
users="$2"
for name in BASE_URL PERFORMANCE_ID; do
  [[ -n "${!name:-}" ]] || { echo "필수 환경변수가 비어 있습니다: $name" >&2; exit 2; }
done
[[ "$PERFORMANCE_ID" =~ ^[1-9][0-9]*$ ]] || { echo 'PERFORMANCE_ID는 양의 정수여야 합니다.' >&2; exit 2; }
if [[ "${SETUP:-csv}" != self ]]; then
  [[ -n "${USERS_CSV:-}" && -f "$USERS_CSV" && -r "$USERS_CSV" ]] || { echo 'USERS_CSV 파일을 읽을 수 없습니다.' >&2; exit 2; }
fi
K6_BIN="${K6_BIN:-k6}"
command -v "$K6_BIN" >/dev/null || { echo "k6를 찾을 수 없습니다: $K6_BIN" >&2; exit 2; }
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${SETUP:-csv}" != self ]]; then
  export USERS_CSV="$(cd "$(dirname "$USERS_CSV")" && pwd)/$(basename "$USERS_CSV")"
fi
export PARTICIPANTS="$users" VUS="$users"
export HOT_SEATS="${HOT_SEATS:-1}" MAX_DURATION="${MAX_DURATION:-5m}"
export HOLD_DELAY_SECONDS="${HOLD_DELAY_SECONDS:-3}" MIXED_HOLD=hot
case "$scenario" in
  spread|simultaneous) export MODE=poll POLL_ENTRY="$scenario" DURATION="${DURATION:-2m}" ;;
  hot|overlap|distributed|replay) export MODE="$scenario" POLL_ENTRY=spread DURATION="${DURATION:-2m}" ;;
  mixed|mixed-distributed)
    readers=$(( users * 4 / 5 )); holders=$(( users - readers ))
    (( readers > 0 && holders > 0 )) || { echo '혼합 시나리오는 최소 2명이 필요합니다.' >&2; exit 2; }
    export MODE=mixed PARTICIPANTS="$readers" HOLD_PARTICIPANTS="$holders" POLL_ENTRY="${POLL_ENTRY:-spread}" DURATION="${DURATION:-30s}"
    [[ "$scenario" == mixed-distributed ]] && export MIXED_HOLD=distributed
    ;;
esac
mkdir -p "$root/.ai-output/perf"
run_dir="$(mktemp -d "$root/.ai-output/perf/ticketing-XXXXXXXX")"
export RUN_ID="$(basename "$run_dir")" SUMMARY_PATH="$run_dir/summary.json"
printf '시나리오: %s | 사용자: %s | 실행 ID: %s\n' "$scenario" "$users" "$RUN_ID"
if [[ "$MODE" == mixed ]]; then printf '조회: %s명 | 선점: %s명\n' "$PARTICIPANTS" "$HOLD_PARTICIPANTS"; fi
if [[ "$MODE" != poll ]]; then printf '선점 요청으로 테스트 데이터가 변경됩니다. 실험 간 초기 상태를 확인하세요.\n'; fi
printf '결과: %s\n' "$SUMMARY_PATH"
if [[ -n "${TICKETING_K6_CONFIG:-}" ]]; then
  [[ -r "$TICKETING_K6_CONFIG" ]] || { echo 'k6 설정 파일을 읽을 수 없습니다.' >&2; exit 2; }
  "$K6_BIN" run --config "$TICKETING_K6_CONFIG" "$root/perf/scenarios/ticketing/${scenario}.js"
else
  "$K6_BIN" run "$root/perf/scenarios/ticketing/${scenario}.js"
fi
