#!/usr/bin/env bash
# 배포 서버(EC2)를 부하 테스트에 빌려 쓰는 명령 모음. 사용법은 perf/ec2/README.md.
# EC2 Instance Connect 브라우저 터미널에서 ~/restless 저장소로 실행한다.
#
#   perf-ec2.sh check                 perf.env 키와 값 조건 확인 (값은 출력하지 않는다)
#   perf-ec2.sh create-db             RDS에 perf DB 생성 (이름은 perf.env의 DB_URL)
#   perf-ec2.sh start <image tag>     자동 배포 멈춤, restless 중지, restless-perf 기동, Flyway 확인
#   perf-ec2.sh stop-app              restless-perf 중지 (시드, 토큰 전달 전)
#   perf-ec2.sh seed <scale>          빈 perf DB에 시드, 토큰 묶음 생성
#   perf-ec2.sh refresh <scale>       판매 기간과 토큰 만료 갱신
#   perf-ec2.sh verify <scale>        정합성 점검
#   perf-ec2.sh serve-tokens          8080으로 토큰 묶음을 한 번만 내려보낸다 (restless-perf가 멈춰 있어야 한다)
#   perf-ec2.sh lockstats             InnoDB 행 잠금 대기 누적값 (측정 전후 차이를 본다)
#   perf-ec2.sh logs <시작 UTC> <종료 UTC>  perf 컨테이너 로그 조회 (최대 10000줄)
#   perf-ec2.sh status                perf 컨테이너 이미지와 재시작 상태 조회
#   perf-ec2.sh reset-db              perf DB를 지우고 다시 만든다 (스케일을 바꿀 때)
#   perf-ec2.sh finish                restless-perf 삭제, 자동 배포 재개
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${PERF_ENV_FILE:-/opt/restless/perf.env}"
PAUSE_FILE=/opt/restless/deploy.paused
IMAGE=ghcr.io/prgrms-be-devcourse/nbe12-14-final-restless-backend
OUT="$REPO/perf/seed/out"
BUNDLE="$REPO/.ai-output/perf-tokens/seed-out.tar.gz"

die() { echo "perf-ec2: $*" >&2; exit 1; }

# perf.env는 root 소유 600이다. 읽을 수 없으면 sudo로 읽는다.
read_env_file() { if [[ -r "$ENV_FILE" ]]; then cat "$ENV_FILE"; else sudo cat "$ENV_FILE"; fi; }

# perf.env를 셸에 읽는다. docker env 파일은 셸 문법이 아니므로 source하지 않고 한 줄씩 나눈다.
load_env() {
  [[ -n "${PERF_ENV_LOADED:-}" ]] && return
  local line key
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* || "$line" != *=* ]] && continue
    key="${line%%=*}"
    export "$key=${line#*=}"
  done < <(read_env_file)
  PERF_ENV_LOADED=1
  [[ "${SPRING_PROFILES_ACTIVE:-}" == "perf" ]] || die "$ENV_FILE must set SPRING_PROFILES_ACTIVE=perf"
  # jdbc:mysql://host:port/db?params
  [[ "${DB_URL:-}" =~ ^jdbc:mysql://([^:/]+)(:([0-9]+))?/([^?]+) ]] || die "cannot parse DB_URL"
  export DB_HOST="${BASH_REMATCH[1]}" DB_PORT="${BASH_REMATCH[3]:-3306}" DB_NAME="${BASH_REMATCH[4]}"
  export PERF_ALLOWED_DB_HOSTS="$DB_HOST"
  [[ "$DB_NAME" == perf-* ]] || die "DB name in DB_URL must start with perf-: $DB_NAME"
  shopt -s nocasematch
  [[ "$DB_HOST $DB_NAME" =~ prod ]] && die "DB_URL looks like production"
  shopt -u nocasematch
  if [[ -z "${MYSQL:-}" ]]; then
    if command -v mysql >/dev/null; then MYSQL=mysql
    else MYSQL="docker run --rm -i --network host -e MYSQL_PWD mysql:8.4 mysql"; fi
  fi
  export MYSQL
}

# DB 이름 없이 서버에 붙는다 (DB 생성, 삭제용).
# shellcheck disable=SC2086 # MYSQL may be a multi-word command
server_sql() { MYSQL_PWD="$DB_PASSWORD" $MYSQL -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" --batch -e "$1"; }
# shellcheck disable=SC2086
db_sql() { MYSQL_PWD="$DB_PASSWORD" $MYSQL -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" --batch "$DB_NAME" -e "$1"; }

running() { [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" == "true" ]]; }

wait_health() {
  for _ in $(seq 1 60); do
    curl -sf http://127.0.0.1:8081/actuator/health >/dev/null && return 0
    sleep 3
  done
  docker logs --tail 50 restless-perf
  die "restless-perf did not become healthy in 180s"
}

seed() { load_env; "$REPO/perf/seed/seed.sh" --scale "$1" --profile perf "${@:2}"; }

cmd="${1:-}"; shift || true
case "$cmd" in
  check)
    load_env
    echo "keys: $(read_env_file | grep -v '^#' | cut -d= -f1 | xargs)"
    echo "profile=$SPRING_PROFILES_ACTIVE db=$DB_NAME host=$DB_HOST queue_required=${RESTLESS_DROP_QUEUE_REQUIRED:-unset} management_port=${MANAGEMENT_PORT:-unset}"
    echo "pg fake: delay=${PG_FAKE_APPROVE_DELAY:-default 300ms} reject=${PG_FAKE_REJECT_RATE:-0} unknown=${PG_FAKE_UNKNOWN_RATE:-0} lost=${PG_FAKE_LOST_RESPONSE_RATE:-0}"
    [[ "${RESTLESS_DROP_QUEUE_REQUIRED:-}" == "true" ]] || echo "warning: RESTLESS_DROP_QUEUE_REQUIRED is not true (default is on; only reservation capacity and spike runs turn it off, then set it back)"
    [[ ${#JWT_SECRET} -ge 32 ]] || die "JWT_SECRET is shorter than 32 bytes"
    echo "mysql client: $MYSQL"
    ;;
  create-db)
    load_env
    server_sql "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    server_sql "SHOW DATABASES LIKE 'perf-%'"
    ;;
  start)
    tag="${1:?image tag is required, e.g. sha-<12 chars> or dev}"
    load_env
    sudo touch "$PAUSE_FILE"
    docker rm -f restless >/dev/null 2>&1 || true
    docker rm -f restless-perf >/dev/null 2>&1 || true
    docker pull -q "$IMAGE:$tag"
    docker run -d --name restless-perf --env-file "$ENV_FILE" -p 8080:8080 -p 8081:8081 "$IMAGE:$tag" >/dev/null
    wait_health
    echo "restless-perf up: image $tag, revision $(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' restless-perf)"
    db_sql "SELECT COUNT(*) AS migrations, SUM(success = 0) AS failed, MAX(version) AS latest FROM flyway_schema_history"
    ;;
  stop-app)
    docker stop restless-perf >/dev/null && echo "restless-perf stopped (deploy stays paused)"
    ;;
  seed)
    scale="${1:?scale is required}"
    seed "$scale"
    mkdir -p "$(dirname "$BUNDLE")" && chmod 700 "$(dirname "$BUNDLE")"
    tar -czf "$BUNDLE" -C "$OUT" users.csv staff.csv manifest.json && chmod 600 "$BUNDLE"
    echo "bundle: $BUNDLE ($(du -h "$BUNDLE" | cut -f1)). Next: perf-ec2.sh serve-tokens"
    ;;
  refresh)
    scale="${1:?scale is required}"
    seed "$scale" --refresh
    tar -czf "$BUNDLE" -C "$OUT" users.csv staff.csv manifest.json && chmod 600 "$BUNDLE"
    echo "bundle refreshed. Next: perf-ec2.sh serve-tokens"
    ;;
  verify)
    seed "${1:?scale is required}" --verify
    ;;
  serve-tokens)
    [[ -f "$BUNDLE" ]] || die "no bundle. Run seed or refresh first."
    running restless-perf && die "restless-perf is using 8080. Run perf-ec2.sh stop-app first."
    echo "laptop: mkdir -p perf/seed/out && curl -fo perf/seed/out/seed-out.tar.gz http://perf-app:8080/seed-out.tar.gz"
    echo "serving once on 8080 (security group allows only your IP). Times out in 5 minutes."
    timeout 300 python3 "$REPO/perf/ec2/serve-once.py" "$BUNDLE" 8080
    ;;
  lockstats)
    load_env
    db_sql "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_time_max','Innodb_row_lock_current_waits','Threads_running','Innodb_deadlocks')" 2>/dev/null \
      || db_sql "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%'"
    ;;
  logs)
    [[ $# -eq 2 ]] || die 'usage: logs <start UTC> <end UTC> (YYYY-MM-DDTHH:MM:SSZ)'
    timestamp_pattern='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'
    [[ "$1" =~ $timestamp_pattern && "$2" =~ $timestamp_pattern ]] || die 'timestamps must use YYYY-MM-DDTHH:MM:SSZ'
    [[ "$1" < "$2" ]] || die 'start time must be earlier than end time'
    docker logs --timestamps --since "$1" --until "$2" restless-perf 2>&1 | tail -n 10000
    ;;
  status)
    [[ $# -eq 0 ]] || die 'usage: status'
    docker inspect --format 'Image={{.Config.Image}} Revision={{index .Config.Labels "org.opencontainers.image.revision"}} Status={{.State.Status}} ExitCode={{.State.ExitCode}} RestartCount={{.RestartCount}} OOMKilled={{.State.OOMKilled}} StartedAt={{.State.StartedAt}}' restless-perf
    ;;
  reset-db)
    load_env
    running restless-perf && die "stop restless-perf first (perf-ec2.sh stop-app)"
    read -r -p "drop and recreate database $DB_NAME on $DB_HOST? type the name to confirm: " answer
    [[ "$answer" == "$DB_NAME" ]] || die "not confirmed"
    server_sql "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
    echo "recreated $DB_NAME. Next: perf-ec2.sh start <tag> (Flyway migrates), stop-app, seed <scale>"
    ;;
  finish)
    docker rm -f restless-perf >/dev/null 2>&1 || true
    sudo rm -f "$PAUSE_FILE"
    echo "restless-perf removed, auto deploy resumed. The deploy timer brings restless back within 3 minutes."
    echo "check: systemctl list-timers restless-deploy.timer; docker ps"
    ;;
  *)
    sed -n '2,/^set -euo pipefail/{ /^set -euo pipefail/!p; }' "$0"
    exit 1
    ;;
esac
