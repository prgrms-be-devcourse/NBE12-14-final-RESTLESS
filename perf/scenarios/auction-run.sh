#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <ending|detail|list|bid|sse> [name]" >&2
  exit 2
fi
test_name=$1
case "$test_name" in ending|detail|list|bid|sse) ;; *) echo "unknown auction test: $test_name" >&2; exit 2 ;; esac
# 대상: 서버 하나(PERF_APP_IP) 또는 ALB(PERF_ALB_HOST, HTTP 80). 주소는 환경 변수로만 받고 저장소에 남기지 않는다 (#483).
if [[ -n "${PERF_APP_IP:-}" && -n "${PERF_ALB_HOST:-}" ]]; then
  echo 'set only one of PERF_APP_IP (one server) and PERF_ALB_HOST (ALB)' >&2; exit 2
elif [[ -n "${PERF_ALB_HOST:-}" ]]; then
  target=alb
  if [[ ! "$PERF_ALB_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9-]*\.[A-Za-z0-9-]+\.elb\.amazonaws\.com$ ]] \
      || [[ "$(printf '%s' "$PERF_ALB_HOST" | tr '[:upper:]' '[:lower:]')" == *prod* ]]; then
    echo 'PERF_ALB_HOST must be an AWS ALB DNS name (*.elb.amazonaws.com) without prod' >&2; exit 2
  fi
elif [[ -n "${PERF_APP_IP:-}" ]]; then
  target=direct
  python3 -c 'import ipaddress,sys; ipaddress.ip_address(sys.argv[1])' "$PERF_APP_IP" >/dev/null || {
    echo 'PERF_APP_IP must be an IPv4 or IPv6 address' >&2; exit 2;
  }
else
  echo 'PERF_APP_IP (one server) or PERF_ALB_HOST (ALB) is required' >&2; exit 2
fi
# 서버 지표를 서버 안에서 SSH로 받을 대상 (공백으로 구분한 ssh 별칭 또는 user@ip). ALB 뒤 서버는 노트북에서 8081에 닿지 않는다.
# ALB면 필수다. 서버 지표 없이 돌리려면 none을 준다. 서버 하나면 비워 두고 예전처럼 노트북에서 8081을 읽어도 된다.
ssh_targets=${PERF_SSH_TARGETS:-}
if [[ "$target" == alb && -z "$ssh_targets" ]]; then
  echo 'PERF_SSH_TARGETS is required with PERF_ALB_HOST (e.g. "perf-cheon@<server3 IP> perf-cheon@<server4 IP>"); use none to skip server metrics' >&2
  exit 2
fi
[[ "$ssh_targets" == none ]] && ssh_targets=''
server_count=0
for ssh_target in $ssh_targets; do
  [[ "$ssh_target" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]*$ ]] || { echo "invalid ssh target: $ssh_target" >&2; exit 2; }
  server_count=$((server_count + 1))
done
remote_ec2=${PERF_REMOTE_EC2:-'sudo -u ubuntu /home/ubuntu/restless/perf/ec2/perf-ec2.sh'}
ssh_opts=(-n -o BatchMode=yes -o ConnectTimeout=10)
remote() { ssh "${ssh_opts[@]}" "$1" "$remote_ec2 ${*:2}"; }

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$script_dir/../.." && pwd)
cd "$repo_root"
# 입찰자·관전자 토큰 (#483). 기본은 시드 토큰, AUTH_MODE=login이면 로그인한다. 상세·목록(detail, list)은 토큰을 쓰지 않는다.
export AUTH_MODE="${AUTH_MODE:-seed}"
export SEED_OUT="${SEED_OUT:-$repo_root/perf/seed/out}"
case "$AUTH_MODE" in seed|login) ;; *) echo 'AUTH_MODE must be seed or login' >&2; exit 2 ;; esac
if [[ "$AUTH_MODE" == seed && "$test_name" =~ ^(ending|bid|sse)$ && ! -s "$SEED_OUT/users.csv" ]]; then
  echo "$SEED_OUT/users.csv not found. Fetch the seed token bundle (docs/domains/auction/load-test.md) or set AUTH_MODE=login" >&2
  exit 2
fi
stamp=$(date '+%Y%m%dT%H%M%S')
run_name=${2:-"${test_name}-${stamp}"}
if [[ ! "$run_name" =~ ^[A-Za-z0-9._-]+$ ]]; then echo 'name may contain only letters, digits, dot, underscore and dash' >&2; exit 2; fi
out_dir="$repo_root/.ai-output/perf/auction"
mkdir -p "$out_dir"
base="$out_dir/$run_name"
hosts_config="$out_dir/.hosts-${run_name}.json"
metrics_file="${base}-metrics.csv"
log_file="${base}.log"
summary_file="${base}.json"
html_file="${base}.html"
prom_before="${base}-prom-before.txt"
prom_after="${base}-prom-after.txt"
sampler_pids=''
load_pid=''
cleanup() {
  if [[ -n "$load_pid" ]]; then kill "$load_pid" 2>/dev/null || true; wait "$load_pid" 2>/dev/null || true; fi
  # ssh를 끊으면 서버 안의 sample도 쓰기에 실패해 멈춘다.
  for pid in $sampler_pids; do kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; done
  rm -f "$hosts_config"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
export PERF_TARGET=$target PERF_SERVER_COUNT=$server_count
if [[ "$target" == alb ]]; then
  printf '{"hosts":{}}\n' > "$hosts_config"
  export BASE_URL="http://$PERF_ALB_HOST" PERF_APP_HOST="$PERF_ALB_HOST" PERF_ALLOWED_APP_HOSTS="$PERF_ALB_HOST" PERF_ALB_HOST
  unset MANAGEMENT_URL
else
  printf '{"hosts":{"perf-local":"%s"}}\n' "$PERF_APP_IP" > "$hosts_config"
  server_ip_url=$PERF_APP_IP
  if [[ "$server_ip_url" == *:* ]]; then server_ip_url="[$server_ip_url]"; fi
  export BASE_URL=http://perf-local:8080
  export MANAGEMENT_URL=http://perf-local:8081
  export PERF_APP_HOST=perf-local
  export PERF_ALLOWED_APP_HOSTS=perf-local
fi
# 실행 전후 전체 지표. 사전 확인 이유별 수처럼 샘플 열에 없는 값은 두 파일의 차이로 본다.
# SSH 대상이 있으면 서버마다 s1, s2 ... 순서(PERF_SSH_TARGETS 순서)로 받는다. 첫 스냅샷이 실패하면 SSH나 앱이 준비되지 않은 것이라 측정 전에 멈춘다.
if [[ -n "$ssh_targets" ]]; then
  n=0
  for ssh_target in $ssh_targets; do
    n=$((n + 1))
    remote "$ssh_target" prom > "${base}-prom-before-s$n.txt" || { echo "server s$n: SSH or prometheus snapshot failed; check PERF_SSH_TARGETS and the app" >&2; exit 1; }
    # 함수가 아니라 ssh를 바로 띄워야 $!가 ssh 자신이고, 끝날 때 kill로 끊긴다.
    ssh "${ssh_opts[@]}" "$ssh_target" "$remote_ec2 sample 7200 1" > "${base}-metrics-s$n.csv" 2>"${base}-metrics-s$n.err" &
    sampler_pids="$sampler_pids $!"
  done
  echo "server metrics: $n servers, files ${run_name}-metrics-s1..s$n.csv in PERF_SSH_TARGETS order"
elif [[ "$target" == direct ]]; then
  curl --silent --fail --max-time 5 "http://$server_ip_url:8081/actuator/prometheus" > "$prom_before" || echo 'prometheus snapshot before run failed' >&2
  "$script_dir/sample-actuator.sh" "http://$server_ip_url:8081" "$metrics_file" &
  sampler_pids=$!
fi
sleep 1

status=0
if [[ "$test_name" == sse ]]; then
  export SUMMARY_PATH="$summary_file"
  export PERF_APP_IP
  node "$script_dir/auction-sse.mjs" >"$log_file" 2>&1 &
  load_pid=$!
  wait "$load_pid" || status=$?
  load_pid=''
  cat "$log_file"
else
  case "$test_name" in ending|detail|list|bid) export TARGET="$test_name" ;; esac
  export SUMMARY_PATH="$summary_file"
  export K6_WEB_DASHBOARD=true
  export K6_WEB_DASHBOARD_EXPORT="$html_file"
  k6 run --include-system-env-vars --config "$hosts_config" "$script_dir/auction-burst.js" >"$log_file" 2>&1 &
  load_pid=$!
  wait "$load_pid" || status=$?
  load_pid=''
  cat "$log_file"
fi
if [[ -n "$ssh_targets" ]]; then
  n=0
  for ssh_target in $ssh_targets; do
    n=$((n + 1))
    remote "$ssh_target" prom > "${base}-prom-after-s$n.txt" || echo "server s$n: prometheus snapshot after run failed" >&2
  done
elif [[ "$target" == direct ]]; then
  curl --silent --fail --max-time 5 "http://$server_ip_url:8081/actuator/prometheus" > "$prom_after" || echo 'prometheus snapshot after run failed' >&2
fi
exit "$status"
