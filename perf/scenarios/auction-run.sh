#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <ending|detail|list|bid|sse> [name]" >&2
  exit 2
fi
test_name=$1
case "$test_name" in ending|detail|list|bid|sse) ;; *) echo "unknown auction test: $test_name" >&2; exit 2 ;; esac
: "${PERF_APP_IP:?PERF_APP_IP is required}"
python3 -c 'import ipaddress,sys; ipaddress.ip_address(sys.argv[1])' "$PERF_APP_IP" >/dev/null || {
  echo 'PERF_APP_IP must be an IPv4 or IPv6 address' >&2; exit 2;
}

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
sampler_pid=''
load_pid=''
cleanup() {
  if [[ -n "$load_pid" ]]; then kill "$load_pid" 2>/dev/null || true; wait "$load_pid" 2>/dev/null || true; fi
  if [[ -n "$sampler_pid" ]]; then kill "$sampler_pid" 2>/dev/null || true; wait "$sampler_pid" 2>/dev/null || true; fi
  rm -f "$hosts_config"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
printf '{"hosts":{"perf-local":"%s"}}\n' "$PERF_APP_IP" > "$hosts_config"
server_ip_url=$PERF_APP_IP
if [[ "$server_ip_url" == *:* ]]; then server_ip_url="[$server_ip_url]"; fi

export BASE_URL=http://perf-local:8080
export MANAGEMENT_URL=http://perf-local:8081
export PERF_APP_HOST=perf-local
export PERF_ALLOWED_APP_HOSTS=perf-local
# 실행 전후 전체 지표. 사전 확인 이유별 수처럼 샘플 열에 없는 값은 두 파일의 차이로 본다.
curl --silent --fail --max-time 5 "http://$server_ip_url:8081/actuator/prometheus" > "$prom_before" || echo 'prometheus snapshot before run failed' >&2
"$script_dir/sample-actuator.sh" "http://$server_ip_url:8081" "$metrics_file" &
sampler_pid=$!
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
curl --silent --fail --max-time 5 "http://$server_ip_url:8081/actuator/prometheus" > "$prom_after" || echo 'prometheus snapshot after run failed' >&2
exit "$status"
