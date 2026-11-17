#!/usr/bin/env bash
# 팬미팅 S1(C46) 측정 뒤 점검: 불변식, 응모 정합성, 추첨 소요 시간. 사용법은 perf/scenarios/fanmeeting.md.
#
# 필요: DB_HOST, DB_NAME, DB_USERNAME, DB_PASSWORD, PERF_ALLOWED_DB_HOSTS (seed.sh와 같은 값)
#       INVARIANTS_SQL  "-- name:" 블록으로 나뉜 불변식 SQL 경로 (fanmeeting-invariants.sql)
#       EVENT_FILE(fanmeeting-prepare.sh 출력) 또는 EVENT_ID
# 선택: DB_PORT(3306), MYSQL(예: "docker exec -i -e MYSQL_PWD perf-mysql mysql"),
#       SUMMARY_PATH(k6 요약 JSON. 있으면 201 수와 응모 수를 비교한다. 연결 오류·5xx 수만큼은 DB가 더 많아도 된다)
# 종료 코드: 불변식 위반이 있거나 응모 수가 맞지 않으면 1.
set -euo pipefail

die() { echo "fanmeeting-check: $*" >&2; exit 2; }

# ---------------------------------------------------------------- 안전장치 (seed.sh와 같은 규칙)
: "${DB_HOST:?DB_HOST is required}" "${DB_NAME:?DB_NAME is required}"
: "${DB_USERNAME:?DB_USERNAME is required}" "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${PERF_ALLOWED_DB_HOSTS:?PERF_ALLOWED_DB_HOSTS is required (comma separated)}"
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

: "${INVARIANTS_SQL:?INVARIANTS_SQL is required (path to fanmeeting-invariants.sql)}"
[[ -f "$INVARIANTS_SQL" ]] || die "INVARIANTS_SQL not found: $INVARIANTS_SQL"
if [[ -z "${EVENT_ID:-}" && -n "${EVENT_FILE:-}" ]]; then
  EVENT_ID="$(sed -n 's/^FM_EVENT_ID=//p' "$EVENT_FILE")"
fi
[[ "${EVENT_ID:-}" =~ ^[0-9]+$ ]] || die "EVENT_ID or EVENT_FILE is required"

# 비밀번호는 환경 변수로만 넘긴다. 명령줄·파일·로그에 남기지 않는다.
export MYSQL_PWD="$DB_PASSWORD"
# shellcheck disable=SC2086 # MYSQL may be a multi-word command
mysql_run() { $MYSQL -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" --default-character-set=utf8mb4 "$DB_NAME" "$@"; }
query() { mysql_run --batch --raw --skip-column-names -e "$1"; }

[[ "$(query "SELECT COUNT(*) FROM fan_meeting_events WHERE event_id = $EVENT_ID")" == "1" ]] \
  || die "event $EVENT_ID not found in $DB_NAME"
failed=0

# ---------------------------------------------------------------- 1. 불변식 (DB 전체)
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

# ---------------------------------------------------------------- 2. C46 응모 정합성
echo "== C46 applications (event $EVENT_ID)"
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
echo "== draw (scenario D)"
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

if [[ $failed -ne 0 ]]; then echo "fanmeeting-check: FAILED"; exit 1; fi
echo "fanmeeting-check: ok"
