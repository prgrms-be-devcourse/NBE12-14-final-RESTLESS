#!/usr/bin/env bash
# DB 프로브 (읽기 전용). 사용법은 perf/ec2/README.md.
# performance_schema가 꺼져 있고 Performance Insights 권한이 없을 때 쓰는 대체 수단이다.
#
#   db-probe.sh status <파일>        전역 카운터 스냅샷 (측정 전후로 한 번씩, 차이를 본다)
#   db-probe.sh sample <초> <파일>   약 0.5초마다 실행 중인 SQL 표본 (연결 1개)
#
# perf-ec2.sh와 같은 안전장치: perf.env의 DB 이름이 perf-로 시작해야 하고 prod가 들어 있으면 멈춘다.
set -euo pipefail

ENV_FILE="${PERF_ENV_FILE:-/opt/restless/perf.env}"
die() { echo "db-probe: $*" >&2; exit 1; }

# perf.env는 root 소유 600이다. 읽을 수 없으면 sudo로 읽는다. 값은 출력하지 않는다.
read_env_file() { if [[ -r "$ENV_FILE" ]]; then cat "$ENV_FILE"; else sudo cat "$ENV_FILE"; fi; }

load_env() {
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* || "$line" != *=* ]] && continue
    export "${line%%=*}=${line#*=}"
  done < <(read_env_file)
  [[ "${SPRING_PROFILES_ACTIVE:-}" == "perf" ]] || die "$ENV_FILE must set SPRING_PROFILES_ACTIVE=perf"
  [[ "${DB_URL:-}" =~ ^jdbc:mysql://([^:/]+)(:([0-9]+))?/([^?]+) ]] || die "cannot parse DB_URL"
  DB_HOST="${BASH_REMATCH[1]}" DB_PORT="${BASH_REMATCH[3]:-3306}" DB_NAME="${BASH_REMATCH[4]}"
  [[ "$DB_NAME" == perf-* ]] || die "DB name in DB_URL must start with perf-: $DB_NAME"
  shopt -s nocasematch
  [[ "$DB_HOST $DB_NAME" =~ prod ]] && die "DB_URL looks like production"
  shopt -u nocasematch
  [[ -n "${DB_USERNAME:-}" && -n "${DB_PASSWORD:-}" ]] || die "DB_USERNAME and DB_PASSWORD are required"
  if [[ -z "${MYSQL:-}" ]]; then
    if command -v mysql >/dev/null; then MYSQL=mysql
    else MYSQL="docker run --rm -i --network host -e MYSQL_PWD mysql:8.4 mysql"; fi
  fi
}

# shellcheck disable=SC2086 # MYSQL may be a multi-word command
run() { MYSQL_PWD="$DB_PASSWORD" $MYSQL -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" --batch --skip-column-names "$@"; }

cmd="${1:-}"
case "$cmd" in
  status)
    out="${2:?output file is required}"
    load_env
    run -e "SELECT NOW(6); SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Com_select','Com_insert','Com_update','Com_delete','Com_commit','Innodb_rows_read','Innodb_rows_inserted','Innodb_rows_updated','Handler_read_key','Handler_read_next','Handler_read_prev','Handler_read_rnd_next','Handler_write','Handler_update','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads','Innodb_data_fsyncs','Innodb_os_log_fsyncs','Innodb_row_lock_waits','Innodb_row_lock_time')" > "$out"
    echo "saved $out"; cat "$out" ;;
  sample)
    secs="${2:?seconds is required}"; out="${3:?output file is required}"
    [[ "$secs" =~ ^[1-9][0-9]*$ ]] || die "seconds must be a positive integer"
    load_env
    n=$(( secs * 2 ))
    for _ in $(seq "$n"); do
      echo "SELECT NOW(3), ID, TIME, IFNULL(STATE,''), REPLACE(LEFT(IFNULL(INFO,''),160),'\n',' ') FROM information_schema.PROCESSLIST WHERE DB='$DB_NAME' AND COMMAND='Query' AND ID<>CONNECTION_ID();"
      echo "DO SLEEP(0.5);"
    done | run > "$out"
    echo "saved $out ($(wc -l < "$out") rows)" ;;
  *)
    sed -n '2,8p' "$0"; exit 1 ;;
esac
