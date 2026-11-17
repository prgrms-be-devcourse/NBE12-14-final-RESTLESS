#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
: "${DB_HOST:?}" "${DB_NAME:?}" "${DB_USERNAME:?}" "${DB_PASSWORD:?}" "${PERF_ALLOWED_DB_HOSTS:?}"
[[ "${1:-}" == seed || "${1:-}" == verify ]] || { echo 'usage: ticketing.sh seed | verify PERFORMANCE_ID' >&2; exit 1; }
[[ "$DB_NAME" == perf-* && ! "$DB_NAME $DB_HOST" =~ [Pp][Rr][Oo][Dd] ]] || { echo 'invalid perf DB' >&2; exit 1; }
allowed=0
IFS=',' read -ra hosts <<< "$PERF_ALLOWED_DB_HOSTS"
for host in "${hosts[@]}"; do [[ "${host// /}" == "$DB_HOST" ]] && allowed=1; done
[[ $allowed == 1 ]] || { echo 'DB host not allowed' >&2; exit 1; }
export MYSQL_PWD="$DB_PASSWORD"
MYSQL="${MYSQL:-mysql}"
mysql_run() { $MYSQL -h "$DB_HOST" -P "${DB_PORT:-3306}" -u "$DB_USERNAME" --default-character-set=utf8mb4 "$DB_NAME" "$@"; }
if [[ "$1" == seed ]]; then
  [[ "${SPRING_PROFILES_ACTIVE:-}" == perf ]] || { echo 'SPRING_PROFILES_ACTIVE=perf required' >&2; exit 1; }
  result="$(mysql_run --batch --skip-column-names -e "SELECT (SELECT COUNT(*) FROM perf_seed_meta), (SELECT COUNT(*) FROM users WHERE id BETWEEN 1 AND 1000), (SELECT COUNT(*) FROM performances), (SELECT COUNT(*) FROM concerts WHERE id=1)")"
  IFS=$'\t' read -r meta users performances concerts <<< "$result"
  [[ "$meta" -gt 0 && "$users" == 1000 && "$performances" == 0 && "$concerts" == 1 ]] || { echo 'requires seeded DB with 1000 users, concert 1 and no performances' >&2; exit 1; }
  mysql_run < "$root/ticketing.sql"
else
  [[ "${2:-}" =~ ^[1-9][0-9]*$ ]] || { echo 'positive PERFORMANCE_ID required' >&2; exit 1; }
  result="$( { printf 'SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;\nSTART TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\nSET @performance = %s;\n' "$2"; cat "$root/ticketing-verify.sql"; printf '\nCOMMIT;\n'; } | mysql_run --batch --skip-column-names)"
  printf '%s\n' "$result"
  [[ "$(printf '%s\n' "$result" | awk -F '\t' 'NF != 2 || $2 !~ /^[0-9]+$/ || $2 != 0 {n++} END {if (NR != 14) n++; print n+0}')" == 0 ]] || { echo 'verify: invariant violation or incomplete result' >&2; exit 1; }
  echo 'verify: all 14 checks passed'
fi
