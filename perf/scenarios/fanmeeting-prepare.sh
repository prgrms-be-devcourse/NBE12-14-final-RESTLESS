#!/usr/bin/env bash
# 팬미팅 S1(C46) 준비: 주최자 토큰으로 측정용 행사를 하나 만든다. 사용법은 perf/scenarios/fanmeeting.md.
#
# 필요: BASE_URL, MANAGEMENT_URL, PERF_ALLOWED_APP_HOSTS (k6 시나리오와 같은 값)
# 선택: SEED_OUT(perf/seed/out), OPEN_IN(3m), WINNER_LIMIT(300), RESERVE_LIMIT(100), FEE_AMOUNT(50000),
#       TITLE, OPENS_AT_PUBLIC(true), EVENT_FILE(.ai-output/perf/fanmeeting-event.env),
#       PERF_APP_ADDR(/etc/hosts 대신 perf- 이름을 이 IP로 연결. 예: 127.0.0.1)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
die() { echo "fanmeeting-prepare: $*" >&2; exit 1; }

BASE_URL="${BASE_URL:-http://perf-app:8080}"; BASE_URL="${BASE_URL%/}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://perf-app:8081}"; MANAGEMENT_URL="${MANAGEMENT_URL%/}"
SEED_OUT="${SEED_OUT:-$REPO/perf/seed/out}"
OPEN_IN="${OPEN_IN:-3m}"
WINNER_LIMIT="${WINNER_LIMIT:-300}"
RESERVE_LIMIT="${RESERVE_LIMIT:-100}"
FEE_AMOUNT="${FEE_AMOUNT:-50000}"
OPENS_AT_PUBLIC="${OPENS_AT_PUBLIC:-true}"
EVENT_FILE="${EVENT_FILE:-$REPO/.ai-output/perf/fanmeeting-event.env}"

# ---------------------------------------------------------------- 안전장치 (drop-reservation-spike.js와 같은 규칙)
# perf-로 시작하고 PERF_ALLOWED_APP_HOSTS에 정확히 있는 호스트만 친다. 이름에 prod가 있으면 멈춘다.
: "${PERF_ALLOWED_APP_HOSTS:?PERF_ALLOWED_APP_HOSTS is required (comma separated, e.g. perf-app)}"
IFS=',' read -ra allowed <<< "$PERF_ALLOWED_APP_HOSTS"
host_of() { local h="${1#*://}"; h="${h%%/*}"; echo "${h%%:*}"; }
for url in "$BASE_URL" "$MANAGEMENT_URL"; do
  host="$(host_of "$url")"
  [[ "$host" =~ ^[Pp][Ee][Rr][Ff]- ]] || die "host $host must start with perf- (e.g. perf-app)"
  ok=0
  for a in "${allowed[@]}"; do [[ "$(echo "$a" | xargs)" == "$host" ]] && ok=1; done
  [[ $ok -eq 1 ]] || die "host $host is not in PERF_ALLOWED_APP_HOSTS"
  shopt -s nocasematch; [[ "$host" =~ prod ]] && die "host $host looks like production"; shopt -u nocasematch
done

CURL=(curl -sS --max-time 10)
if [[ -n "${PERF_APP_ADDR:-}" ]]; then
  for url in "$BASE_URL" "$MANAGEMENT_URL"; do
    hp="${url#*://}"; hp="${hp%%/*}"
    CURL+=(--connect-to "${hp}:${PERF_APP_ADDR}:${hp##*:}")
  done
fi

# perf 프로필만 관리 포트에 prometheus를 연다. 응답하지 않으면 perf 앱이 아니므로 멈춘다.
code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$MANAGEMENT_URL/actuator/prometheus" || true)"
[[ "$code" == "200" ]] || die "management $MANAGEMENT_URL/actuator/prometheus returned $code (not a perf app?)"

# ---------------------------------------------------------------- 주최자 토큰
[[ -f "$SEED_OUT/staff.csv" ]] || die "$SEED_OUT/staff.csv not found. Run perf/seed/seed.sh first."
token="$(awk -F',' 'NR == 1 { for (i = 1; i <= NF; i++) { if ($i == "role") r = i; if ($i == "access_token") t = i }; next }
                    $r == "ORGANIZER" { print $t; exit }' "$SEED_OUT/staff.csv")"
[[ -n "$token" ]] || die "no ORGANIZER row in $SEED_OUT/staff.csv"

# ---------------------------------------------------------------- 시각 (policy.md 3절)
# entry_opens_at > now, 응모 기간 10분 고정(entry_closes_at은 서버가 계산), first_notice <= opens,
# meeting >= closes + 48시간. promotionClosesAt은 비워서 meeting - 24시간(closes + 48시간)을 쓴다.
times="$(python3 - "$OPEN_IN" <<'PY'
import re, sys, datetime as dt
m = re.fullmatch(r"(\d+)(s|m|h)?", sys.argv[1])
if not m: sys.exit("OPEN_IN must look like 90, 90s, 3m or 1h")
sec = int(m.group(1)) * {None: 1, "s": 1, "m": 60, "h": 3600}[m.group(2)]
if sec < 30: sys.exit("OPEN_IN must be at least 30s (entry_opens_at must stay in the future)")
now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
opens = now + dt.timedelta(seconds=sec)
closes = opens + dt.timedelta(minutes=10)
meeting = closes + dt.timedelta(hours=72)
f = lambda t: t.strftime("%Y-%m-%dT%H:%M:%SZ")
print(f(now), f(opens), f(closes), f(meeting))
PY
)" || die "cannot compute times"
read -r notice opens closes meeting <<< "$times"
TITLE="${TITLE:-perf S1 응모 $(date -u +%m%d-%H%M)}"

body="$(python3 -c 'import json,sys; a=sys.argv; print(json.dumps({"title": a[1], "entryOpensAt": a[2],
  "firstNoticeScheduledAt": a[3], "winnerLimit": int(a[4]), "reserveLimit": int(a[5]), "feeAmount": int(a[6]),
  "meetingAt": a[7], "promotionClosesAt": None, "opensAtPublic": a[8] == "true"}, ensure_ascii=False))' \
  "$TITLE" "$opens" "$notice" "$WINNER_LIMIT" "$RESERVE_LIMIT" "$FEE_AMOUNT" "$meeting" "$OPENS_AT_PUBLIC")"

resp="$(mktemp)"; trap 'rm -f "$resp"' EXIT
# 토큰은 화면과 파일에 남기지 않는다. 헤더는 표준 입력으로 넘긴다.
code="$(printf 'Authorization: Bearer %s\n' "$token" | "${CURL[@]}" -o "$resp" -w '%{http_code}' \
  -H @- -H 'Content-Type: application/json' -X POST --data-binary "$body" "$BASE_URL/api/organizer/fan-meetings")"
[[ "$code" == "201" ]] || die "POST /api/organizer/fan-meetings returned $code: $(head -c 500 "$resp")"
event_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["eventId"])' "$resp")"

mkdir -p "$(dirname "$EVENT_FILE")"
cat > "$EVENT_FILE" <<ENV
FM_EVENT_ID=$event_id
FM_ENTRY_OPENS_AT=$opens
FM_ENTRY_CLOSES_AT=$closes
FM_MEETING_AT=$meeting
FM_WINNER_LIMIT=$WINNER_LIMIT
FM_RESERVE_LIMIT=$RESERVE_LIMIT
FM_FEE_AMOUNT=$FEE_AMOUNT
FM_CREATED_AT=$notice
ENV
echo "eventId=$event_id entryOpensAt=$opens entryClosesAt=$closes winner=$WINNER_LIMIT reserve=$RESERVE_LIMIT fee=$FEE_AMOUNT"
echo "event file: $EVENT_FILE"
echo "next: start k6 (fanmeeting-apply.js) before $opens"
