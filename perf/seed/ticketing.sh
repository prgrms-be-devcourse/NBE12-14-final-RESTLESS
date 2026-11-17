#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
[[ "${1:-}" == seed || "${1:-}" == verify ]] || { echo 'usage: ticketing.sh seed | verify PERFORMANCE_ID' >&2; exit 1; }

# 직접 DB 설정을 주지 않은 EC2에서는 기존 perf 설정을 읽는다. 파일은 셸 코드로 실행하지 않는다.
if [[ -z "${DB_HOST:-}" && -z "${DB_NAME:-}" && -z "${DB_USERNAME:-}" && -z "${DB_PASSWORD:-}" && -z "${PERF_ALLOWED_DB_HOSTS:-}" ]]; then
  env_file="${PERF_ENV_FILE:-/opt/restless/perf.env}"
  if [[ -r "$env_file" ]]; then
    env_content="$(cat "$env_file")" || { echo 'cannot read perf environment file' >&2; exit 1; }
  else
    env_content="$(sudo cat "$env_file")" || { echo 'cannot read perf environment file; check its path and permissions' >&2; exit 1; }
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      DB_URL=*) DB_URL="${line#*=}" ;;
      DB_USERNAME=*) DB_USERNAME="${line#*=}" ;;
      DB_PASSWORD=*) DB_PASSWORD="${line#*=}" ;;
      SPRING_PROFILES_ACTIVE=*) SPRING_PROFILES_ACTIVE="${line#*=}" ;;
    esac
  done <<< "$env_content"
  unset env_content
  [[ "${SPRING_PROFILES_ACTIVE:-}" == perf ]] || { echo 'perf environment file must set SPRING_PROFILES_ACTIVE=perf' >&2; exit 1; }
  [[ "${DB_URL:-}" =~ ^jdbc:mysql://([^:/]+)(:([0-9]+))?/([^?]+) ]] || { echo 'perf environment file needs a valid MySQL DB_URL' >&2; exit 1; }
  DB_HOST="${BASH_REMATCH[1]}" DB_PORT="${BASH_REMATCH[3]:-3306}" DB_NAME="${BASH_REMATCH[4]}"
  PERF_ALLOWED_DB_HOSTS="$DB_HOST"
  export DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD PERF_ALLOWED_DB_HOSTS SPRING_PROFILES_ACTIVE
  if [[ -z "${MYSQL:-}" ]] && ! command -v mysql >/dev/null; then
    MYSQL="docker run --rm -i --network host -e MYSQL_PWD mysql:8.4 mysql"
  fi
fi
: "${DB_HOST:?}" "${DB_NAME:?}" "${DB_USERNAME:?}" "${DB_PASSWORD:?}" "${PERF_ALLOWED_DB_HOSTS:?}"
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
  mysql_run < "$root/sql/40_ticketing.sql"
else
  [[ "${2:-}" =~ ^[1-9][0-9]*$ ]] || { echo 'positive PERFORMANCE_ID required' >&2; exit 1; }
  result="$( { printf 'SET @performance = %s;\n' "$2"; cat "$root/ticketing-verify.sql"; } | mysql_run --batch --skip-column-names)"
  printf '%s\n' "$result"
  [[ "$(printf '%s\n' "$result" | awk -F '\t' '$2 != 0 {n++} END {print n+0}')" == 0 ]] || exit 1
fi
