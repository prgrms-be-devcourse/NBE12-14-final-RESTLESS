#!/usr/bin/env bash
# Actuator Prometheus 지표를 일정 간격으로 CSV에 남긴다.
#   sample-actuator.sh <MANAGEMENT_URL> <out.csv | -> [watched-pid]
# out이 -이면 표준 출력으로 쓴다 (perf-ec2.sh sample이 서버 안에서 쓴다).
# 간격은 SAMPLE_INTERVAL_SECONDS (기본 2초). 노트북에서 몰림 중에 수집하면 수집 요청도 밀려 행이 빌 수 있다.
set -uo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 <MANAGEMENT_URL> <out.csv | -> [watched-pid]" >&2
  exit 2
fi

management_url=${1%/}
output=$2
watched_pid=${3:-}
interval=${SAMPLE_INTERVAL_SECONDS:-2}
[[ "$interval" =~ ^[1-9][0-9]*$ ]] || { echo "SAMPLE_INTERVAL_SECONDS must be a positive integer" >&2; exit 2; }
if [[ "$output" == - ]]; then
  output=/dev/stdout
else
  mkdir -p "$(dirname "$output")"
  : > "$output"
fi

# 열 이름, 지표 이름, 라벨 조건, 양수만 더할지. 같은 이름의 지표는 라벨이 맞는 줄을 모두 더한다.
# JVM 힙 한도는 한도가 없는 풀이 -1이라 양수만 더한다.
# 열 순서는 바꾸지 않는다. 새 열은 끝에 붙인다 (이전 CSV와 비교하기 위해).
columns=(
  'hikaricp_connections_active|hikaricp_connections_active||'
  'hikaricp_connections_pending|hikaricp_connections_pending||'
  'hikaricp_connections_timeout_total|hikaricp_connections_timeout_total||'
  'tomcat_threads_busy_threads|tomcat_threads_busy_threads||'
  'tomcat_connections_current_connections|tomcat_connections_current_connections||'
  'process_cpu_usage|process_cpu_usage||'
  'system_cpu_usage|system_cpu_usage||'
  'auction_stream_connections|auction_stream_connections||'
  'http_server_requests_active_seconds_gcount|http_server_requests_active_seconds_gcount||'
  'jvm_heap_used_bytes|jvm_memory_used_bytes|area="heap"|'
  'jvm_heap_committed_bytes|jvm_memory_committed_bytes|area="heap"|'
  'jvm_heap_max_bytes|jvm_memory_max_bytes|area="heap"|positive'
  'jvm_gc_pause_seconds_sum|jvm_gc_pause_seconds_sum||'
  'jvm_gc_pause_seconds_count|jvm_gc_pause_seconds_count||'
  'jvm_threads_live_threads|jvm_threads_live_threads||'
  'process_files_open_files|process_files_open_files||'
  # SSE 연결당 메모리를 힙 밖까지 보려고 둔다 (#448). direct buffer는 소켓 입출력 버퍼가 쓰는 메모리다.
  'jvm_nonheap_used_bytes|jvm_memory_used_bytes|area="nonheap"|'
  'jvm_buffer_direct_used_bytes|jvm_buffer_memory_used_bytes|id="direct"|'
  'auction_stream_rejected_total|auction_stream_rejected_total||'
  'auction_stream_heartbeat_removed_total|auction_stream_heartbeat_removed_total||'
  'auction_bid_precheck_pass_total|auction_bid_precheck_total|result="pass"|'
  'auction_bid_precheck_reject_total|auction_bid_precheck_total|result="reject"|'
  # DB 연결을 얻기까지 가장 오래 기다린 시간. 표본 사이의 대기도 남는다 (#483).
  'hikaricp_connections_acquire_seconds_max|hikaricp_connections_acquire_seconds_max||'
)

header=timestamp
specs=''
for column in "${columns[@]}"; do
  header+=",${column%%|*}"
  specs+="${column#*|};"
done
printf '%s\n' "$header" >> "$output"

# 응답 한 번을 awk 한 번으로 읽는다. 서버 안에서 1초마다 돌려도 프로세스를 적게 띄우기 위해서다.
extract() {
  awk -v specs="$specs" '
    BEGIN {
      n = split(specs, items, ";") - 1
      for (i = 1; i <= n; i++) { split(items[i], f, "|"); metric[i] = f[1]; label[i] = f[2]; positive[i] = (f[3] != "") }
    }
    $0 !~ /^#/ && NF >= 2 {
      name = $1; sub(/\{.*/, "", name)
      for (i = 1; i <= n; i++) {
        if (name != metric[i] || (label[i] != "" && index($0, label[i]) == 0)) continue
        if (positive[i] && $NF <= 0) continue
        sum[i] += $NF; found[i] = 1
      }
    }
    END {
      line = ""
      for (i = 1; i <= n; i++) line = line "," (found[i] ? sprintf("%.12g", sum[i]) : "")
      print line
    }
  '
}

while :; do
  if [[ -n "$watched_pid" ]] && ! kill -0 "$watched_pid" 2>/dev/null; then break; fi
  timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  body=$(curl --silent --show-error --fail --max-time 3 "$management_url/actuator/prometheus" 2>/dev/null || true)
  # 응답이 없으면 시각만 남는다. 그 자체가 앱이 응답하지 못한 기록이다.
  # 받는 쪽이 끊기면 (예: ssh를 먼저 끊음) 쓰기가 실패한다. 그때 멈춘다.
  printf '%s%s\n' "$timestamp" "$(extract <<< "$body")" >> "$output" || break
  sleep "$interval"
done
