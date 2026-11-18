#!/usr/bin/env bash
# 팬미팅 측정 뒤 점검: 불변식, 응모 정합성, 추첨 소요 시간. 사용법은 perf/scenarios/fanmeeting.md.
#
#   fanmeeting-check.sh [행사 번호 ...]
#     행사 번호를 주면 그 행사들을 차례로 본다. 없으면 EVENT_ID 또는 EVENT_FILE(fanmeeting-prepare.sh 출력)의 행사 하나.
#     불변식은 DB 전체 검사라 한 번만 하고, 응모·추첨은 행사마다 본다.
#
# DB 접속: DB_HOST, DB_NAME, DB_USERNAME, DB_PASSWORD, PERF_ALLOWED_DB_HOSTS (seed.sh와 같은 값).
#   DB_HOST가 비어 있고 PERF_ENV_FILE(기본 /opt/restless/perf.env, EC2의 perf 앱 설정)이 있으면 거기의
#   DB_URL·DB_USERNAME·DB_PASSWORD를 읽는다. 값은 화면에 남기지 않는다. EC2에서는 행사 번호만 주면 된다.
# 선택: DB_PORT(3306), INVARIANTS_SQL(기본: 레포의 harness/fanmeeting-invariants.sql),
#       MYSQL(기본: mysql, 없으면 mysql:8.4 컨테이너. 예: "docker exec -i -e MYSQL_PWD perf-mysql mysql"),
#       SUMMARY_PATH(k6 요약 JSON. 행사 하나일 때만. 201 수와 응모 수를 비교한다. 연결 오류·5xx 수만큼은 DB가 더 많아도 된다)
# 종료 코드: 불변식 위반, 중복 응모, 응모 수 불일치가 있으면 1. 입력·접속 문제는 2.
set -euo pipefail

die() { echo "fanmeeting-check: $*" >&2; exit 2; }
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# ---------------------------------------------------------------- EC2 perf.env에서 DB 정보 읽기
# docker env 파일 형식(KEY=VALUE 한 줄씩)이라 source하지 않고 필요한 키만 한 줄씩 읽는다. 따옴표·특수문자가 그대로 보존된다.
PERF_ENV_FILE="${PERF_ENV_FILE:-/opt/restless/perf.env}"
if [[ -z "${DB_HOST:-}" && -e "$PERF_ENV_FILE" ]]; then
  if [[ -r "$PERF_ENV_FILE" ]]; then env_text="$(cat "$PERF_ENV_FILE")"; else env_text="$(sudo cat "$PERF_ENV_FILE")"; fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      DB_URL=*) DB_URL="${line#DB_URL=}" ;;
      DB_USERNAME=*) DB_USERNAME="${line#DB_USERNAME=}" ;;
      DB_PASSWORD=*) DB_PASSWORD="${line#DB_PASSWORD=}" ;;
    esac
  done <<< "$env_text"
  unset env_text
  # jdbc:mysql://host:port/db?params
  [[ "${DB_URL:-}" =~ ^jdbc:mysql://([^:/]+)(:([0-9]+))?/([^?]+) ]] || die "cannot parse DB_URL in $PERF_ENV_FILE"
  DB_HOST="${BASH_REMATCH[1]}"; DB_PORT="${BASH_REMATCH[3]:-3306}"; DB_NAME="${BASH_REMATCH[4]}"
  PERF_ALLOWED_DB_HOSTS="$DB_HOST"
  echo "fanmeeting-check: DB settings from $PERF_ENV_FILE ($DB_HOST/$DB_NAME)"
  if [[ -z "${MYSQL:-}" ]] && ! command -v mysql >/dev/null; then
    MYSQL="docker run --rm -i --network host -e MYSQL_PWD mysql:8.4 mysql"
  fi
fi

# ---------------------------------------------------------------- 안전장치 (seed.sh와 같은 규칙)
[[ -n "${DB_HOST:-}" ]] || die "DB_HOST is required (or run on the EC2 where $PERF_ENV_FILE exists)"
for v in DB_NAME DB_USERNAME DB_PASSWORD PERF_ALLOWED_DB_HOSTS; do [[ -n "${!v:-}" ]] || die "$v is required"; done
DB_PORT="${DB_PORT:-3306}"
MYSQL="${MYSQL:-mysql}"
allowed=0
IFS=',' read -ra hosts <<< "$PERF_ALLOWED_DB_HOSTS"
for host in "${hosts[@]}"; do [[ "$(echo "$host" | xargs)" == "$DB_HOST" ]] && allowed=1; done
[[ $allowed -eq 1 ]] || die "DB_HOST $DB_HOST is not in PERF_ALLOWED_DB_HOSTS"
[[ "$DB_NAME" == perf-* ]] || die "DB_NAME must start with perf- (e.g. perf-restless): $DB_NAME"
shopt -s nocasematch
[[ "$DB_HOST $DB_NAME" =~ prod ]] && die "DB_HOST or DB_NAME looks like production: $DB_HOST/$DB_NAME"
shopt -u nocasematch

INVARIANTS_SQL="${INVARIANTS_SQL:-$REPO/backend/src/test/resources/harness/fanmeeting-invariants.sql}"
[[ -f "$INVARIANTS_SQL" ]] || die "INVARIANTS_SQL not found: $INVARIANTS_SQL"

# 볼 행사: 인자 > EVENT_ID > EVENT_FILE
if [[ $# -gt 0 ]]; then
  EVENT_IDS=("$@")
else
  if [[ -z "${EVENT_ID:-}" && -n "${EVENT_FILE:-}" ]]; then
    EVENT_ID="$(sed -n 's/^FM_EVENT_ID=//p' "$EVENT_FILE")"
  fi
  EVENT_IDS=("${EVENT_ID:-}")
fi
for id in "${EVENT_IDS[@]}"; do
  [[ "$id" =~ ^[0-9]+$ ]] || die "event id must be a number (give ids as arguments, or EVENT_ID / EVENT_FILE): '$id'"
done
[[ -n "${SUMMARY_PATH:-}" && ${#EVENT_IDS[@]} -gt 1 ]] && die "SUMMARY_PATH compares one event; give a single event id"

# 비밀번호는 환경 변수로만 넘긴다. 명령줄·파일·로그에 남기지 않는다.
export MYSQL_PWD="$DB_PASSWORD"
# shellcheck disable=SC2086 # MYSQL may be a multi-word command
mysql_run() { $MYSQL -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" --default-character-set=utf8mb4 "$DB_NAME" "$@"; }
query() { mysql_run --batch --raw --skip-column-names -e "$1"; }

for id in "${EVENT_IDS[@]}"; do
  [[ "$(query "SELECT COUNT(*) FROM fan_meeting_events WHERE event_id = $id")" == "1" ]] \
    || die "event $id not found in $DB_NAME"
done
failed=0

# ---------------------------------------------------------------- 1. 불변식 (DB 전체, 한 번)
echo "== invariants (whole database, violations then name, 0 = ok)"
inv="$(awk '
  /^-- name: / { name = substr($0, 10); q = ""; next }
  /^--/ { next }
  name != "" {
    q = q " " $0
    if ($0 ~ /;[[:space:]]*$/) {
      sub(/;[[:space:]]*$/, "", q); gsub(/\047/, "\047\047", name)
      printf "SELECT \047%s\047, (%s);\n", name, q
      name = ""
    }
  }' "$INVARIANTS_SQL" | mysql_run --batch --skip-column-names)"
[[ -n "$inv" ]] || die "no \"-- name:\" blocks in $INVARIANTS_SQL"
echo "$inv" | awk -F'\t' '{ printf "  %s  %s\n", $2, $1 }'
violations="$(echo "$inv" | awk -F'\t' '$2 != 0' | wc -l | xargs)"
if [[ "$violations" != "0" ]]; then echo "  FAILED: $violations invariant(s) violated"; failed=1; fi

check_event() {  # check_event <event id>
  local EVENT_ID="$1"
  # ---------------------------------------------------------------- 2. C46 응모 정합성
  echo "== C46 applications (event $EVENT_ID)"
  local total applied withdrawn users dup
  read -r total applied withdrawn users dup <<< "$(query "
    SELECT COUNT(*), COALESCE(SUM(status = 'APPLIED'), 0), COALESCE(SUM(status = 'WITHDRAWN'), 0),
           COUNT(DISTINCT user_id),
           (SELECT COUNT(*) FROM (SELECT user_id FROM fan_meeting_applications WHERE event_id = $EVENT_ID
                                  GROUP BY user_id HAVING COUNT(*) > 1) d)
      FROM fan_meeting_applications WHERE event_id = $EVENT_ID" | tr '\t' ' ')"
  echo "  applications $total (APPLIED $applied, WITHDRAWN $withdrawn), distinct users $users, duplicate users $dup"
  if [[ "$dup" != "0" ]]; then echo "  FAILED: duplicate applications per user"; failed=1; fi
  if [[ -n "${SUMMARY_PATH:-}" ]]; then
    [[ -f "$SUMMARY_PATH" ]] || die "SUMMARY_PATH not found: $SUMMARY_PATH"
    local k6_event k6_applied k6_uncertain
    read -r k6_event k6_applied k6_uncertain <<< "$(python3 -c 'import json,sys; s=json.load(open(sys.argv[1])); o=s["outcomes"]
print(s["conditions"]["eventId"], o["fm_applied"], o["fm_transport_error"] + o["fm_server_error"])' "$SUMMARY_PATH")"
    [[ "$k6_event" == "$EVENT_ID" ]] || die "summary is for event $k6_event, not $EVENT_ID"
    # 연결 오류·5xx는 응답을 못 받았을 뿐 커밋됐을 수 있다. 그 수만큼은 DB가 k6 201보다 많아도 된다(DB가 정답).
    if [[ "$k6_applied" == "$applied" ]]; then
      echo "  k6 201 $k6_applied = APPLIED $applied (ok)"
    elif [[ $k6_uncertain -gt 0 && $applied -gt $k6_applied && $applied -le $((k6_applied + k6_uncertain)) ]]; then
      echo "  k6 201 $k6_applied < APPLIED $applied: $((applied - k6_applied)) of $k6_uncertain responses without 201 (transport error or 5xx) committed. DB count is used (ok)"
    else
      echo "  FAILED: k6 201 $k6_applied != APPLIED $applied (responses without 201: $k6_uncertain)"
      failed=1
    fi
  fi

  # ---------------------------------------------------------------- 3. 시나리오 D: 마감 뒤 추첨
  echo "== draw (scenario D, event $EVENT_ID)"
  local draw ev_status closed_at run_status started completed run_ms close_ms attempts candidates winners reserves orders
  draw="$(query "
    SELECT e.status, COALESCE(DATE_FORMAT(e.closed_at, '%Y-%m-%dT%H:%i:%s.%fZ'), '-'),
           COALESCE(r.status, '-'), COALESCE(DATE_FORMAT(r.started_at, '%Y-%m-%dT%H:%i:%s.%fZ'), '-'),
           COALESCE(DATE_FORMAT(r.completed_at, '%Y-%m-%dT%H:%i:%s.%fZ'), '-'),
           COALESCE(TIMESTAMPDIFF(MICROSECOND, r.started_at, r.completed_at) DIV 1000, '-'),
           COALESCE(TIMESTAMPDIFF(MICROSECOND, e.closed_at, r.completed_at) DIV 1000, '-'),
           COALESCE(r.attempt_count, '-'), COALESCE(r.candidate_count, '-'),
           (SELECT COUNT(*) FROM fan_meeting_selections s WHERE s.event_id = e.event_id AND s.selection_type = 'WINNER'),
           (SELECT COUNT(*) FROM fan_meeting_selections s WHERE s.event_id = e.event_id AND s.selection_type = 'RESERVE'),
           (SELECT COUNT(*) FROM fan_meeting_orders o WHERE o.event_id = e.event_id)
      FROM fan_meeting_events e LEFT JOIN fan_meeting_draw_runs r ON r.event_id = e.event_id
     WHERE e.event_id = $EVENT_ID")"
  IFS=$'\t' read -r ev_status closed_at run_status started completed run_ms close_ms attempts candidates winners reserves orders <<< "$draw"
  echo "  event $ev_status, closed_at $closed_at"
  echo "  draw run $run_status, started_at $started, completed_at $completed"
  echo "  started→completed ${run_ms}ms, closed→completed ${close_ms}ms, attempt_count $attempts, candidate_count $candidates"
  echo "  selections WINNER $winners, RESERVE $reserves, orders $orders"
  [[ "$completed" == "-" ]] && echo "  (draw not completed yet: run again after the entry window closes and the Draw Worker runs)"
  return 0
}

for id in "${EVENT_IDS[@]}"; do check_event "$id"; done

if [[ $failed -ne 0 ]]; then echo "fanmeeting-check: FAILED"; exit 1; fi
echo "fanmeeting-check: ok"
