#!/usr/bin/env bash
# GHCR의 dev 이미지가 바뀌었을 때만 배포 컨테이너를 교체한다. systemd 타이머가 몇 분마다 실행한다.
# 새 컨테이너의 health가 제한 시간 안에 UP이 되지 않으면 직전 이미지로 되돌린다.
# 부하 테스트 중에는 교체하지 않는다. 일시 정지 파일이 있거나 perf 컨테이너가 돌고 있으면 건너뛴다.
# 설치와 사용법: deploy/ec2/README.md
set -euo pipefail

IMAGE="${IMAGE:-ghcr.io/prgrms-be-devcourse/nbe12-14-final-restless-backend:dev}"
NAME="${NAME:-restless}"
ENV_FILE="${ENV_FILE:-/opt/restless/prod.env}"
PORT="${PORT:-8080}"
HEALTH_URL="${HEALTH_URL:-http://localhost:${PORT}/actuator/health}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
PAUSE_FILE="${PAUSE_FILE:-/opt/restless/deploy.paused}"
PERF_NAME="${PERF_NAME:-restless-perf}"
PREVIOUS_TAG="restless:previous"
LOCK_FILE="${LOCK_FILE:-/tmp/restless-deploy.lock}"
# 되돌린 이미지 ID를 기록해 같은 이미지를 매번 다시 시도하지 않는다. 새 커밋이 올라오면 ID가 바뀐다.
FAILED_FILE="${FAILED_FILE:-$HOME/.restless-deploy-failed}"

log() { echo "[restless-deploy] $*"; }

exec 9>"$LOCK_FILE"
flock -n 9 || { log "another run is in progress"; exit 0; }

if [[ -e "$PAUSE_FILE" ]]; then
  log "paused by $PAUSE_FILE"
  exit 0
fi
if [[ -n "$(docker ps -q --filter "name=^/${PERF_NAME}$")" ]]; then
  log "load test container $PERF_NAME is running"
  exit 0
fi
[[ -r "$ENV_FILE" ]] || { log "cannot read $ENV_FILE"; exit 1; }

run_container() {
  docker run -d --name "$NAME" --restart unless-stopped \
    --env-file "$ENV_FILE" -p "${PORT}:8080" "$1" >/dev/null
}

wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while (( SECONDS < deadline )); do
    if curl -fsS --max-time 3 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 5
  done
  return 1
}

docker pull -q "$IMAGE" >/dev/null
latest_id="$(docker image inspect -f '{{.Id}}' "$IMAGE")"
current_id="$(docker inspect -f '{{.Image}}' "$NAME" 2>/dev/null || true)"
running="$(docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null || true)"

if [[ "$latest_id" == "$current_id" && "$running" == "true" ]]; then
  exit 0
fi
if [[ "$latest_id" == "$(cat "$FAILED_FILE" 2>/dev/null || true)" && "$running" == "true" ]]; then
  exit 0
fi

revision="$(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$IMAGE" 2>/dev/null || true)"
log "deploying ${revision:0:12} (${latest_id:7:12})"

if [[ -n "$current_id" ]]; then
  docker tag "$current_id" "$PREVIOUS_TAG"
fi
docker rm -f "$NAME" >/dev/null 2>&1 || true
run_container "$IMAGE"

if wait_healthy; then
  log "healthy: ${revision:0:12}"
  rm -f "$FAILED_FILE"
  docker image prune -f >/dev/null
  exit 0
fi

log "health check failed within ${HEALTH_TIMEOUT}s. last logs:"
docker logs --tail 50 "$NAME" 2>&1 || true
docker rm -f "$NAME" >/dev/null 2>&1 || true
echo "$latest_id" > "$FAILED_FILE"

if [[ -z "$current_id" ]]; then
  log "no previous image to roll back to"
  exit 1
fi
log "rolling back to ${current_id:7:12}"
run_container "$PREVIOUS_TAG"
if wait_healthy; then
  log "rollback healthy"
else
  log "rollback also unhealthy. check the server manually"
fi
exit 1
