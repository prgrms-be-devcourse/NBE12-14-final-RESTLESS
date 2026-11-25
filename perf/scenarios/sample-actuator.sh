#!/usr/bin/env bash
set -uo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 <MANAGEMENT_URL> <out.csv> [watched-pid]" >&2
  exit 2
fi

management_url=${1%/}
output=$2
watched_pid=${3:-}
mkdir -p "$(dirname "$output")"
printf '%s\n' 'timestamp,hikaricp_connections_active,hikaricp_connections_pending,hikaricp_connections_timeout_total,tomcat_threads_busy_threads,tomcat_connections_current_connections,process_cpu_usage,system_cpu_usage,auction_stream_connections,http_server_requests_active_seconds_gcount,jvm_heap_used_bytes,jvm_heap_committed_bytes,jvm_heap_max_bytes,jvm_gc_pause_seconds_sum,jvm_gc_pause_seconds_count,jvm_threads_live_threads,process_files_open_files,jvm_nonheap_used_bytes,jvm_buffer_direct_used_bytes,auction_stream_rejected_total,auction_stream_heartbeat_removed_total,auction_bid_precheck_pass_total,auction_bid_precheck_reject_total' > "$output"

metric_value() {
  local body=$1 metric=$2 label=${3:-}
  awk -v wanted="$metric" -v label="$label" '
    $0 !~ /^#/ {
      name=$1; sub(/\{.*/, "", name)
      if (name == wanted && (label == "" || index($0, label) > 0)) { sum += $NF; found=1 }
    }
    END { if (found) printf "%.12g", sum }
  ' <<< "$body"
}

while :; do
  if [[ -n "$watched_pid" ]] && ! kill -0 "$watched_pid" 2>/dev/null; then break; fi
  body=$(curl --silent --show-error --fail --max-time 3 "$management_url/actuator/prometheus" 2>/dev/null || true)
  timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  values=()
  for metric in hikaricp_connections_active hikaricp_connections_pending hikaricp_connections_timeout_total \
    tomcat_threads_busy_threads tomcat_connections_current_connections process_cpu_usage system_cpu_usage \
    auction_stream_connections http_server_requests_active_seconds_gcount; do
    values+=("$(metric_value "$body" "$metric")")
  done
  # JVM heap is summed over the heap memory pools; max is -1 for pools without a limit, so only positive maxima count.
  values+=("$(metric_value "$body" jvm_memory_used_bytes 'area="heap"')")
  values+=("$(metric_value "$body" jvm_memory_committed_bytes 'area="heap"')")
  values+=("$(awk '$0 !~ /^#/ && $1 ~ /^jvm_memory_max_bytes\{/ && index($0, "area=\"heap\"") && $NF > 0 { sum += $NF; found=1 } END { if (found) printf "%.12g", sum }' <<< "$body")")
  for metric in jvm_gc_pause_seconds_sum jvm_gc_pause_seconds_count jvm_threads_live_threads process_files_open_files; do
    values+=("$(metric_value "$body" "$metric")")
  done
  # SSE 연결당 메모리를 힙 밖까지 보려고 둔다 (#448). direct buffer는 소켓 입출력 버퍼가 쓰는 메모리다.
  values+=("$(metric_value "$body" jvm_memory_used_bytes 'area="nonheap"')")
  values+=("$(metric_value "$body" jvm_buffer_memory_used_bytes 'id="direct"')")
  for metric in auction_stream_rejected_total auction_stream_heartbeat_removed_total; do
    values+=("$(metric_value "$body" "$metric")")
  done
  values+=("$(metric_value "$body" auction_bid_precheck_total 'result="pass"')")
  values+=("$(metric_value "$body" auction_bid_precheck_total 'result="reject"')")
  (IFS=,; printf '%s,%s\n' "$timestamp" "${values[*]}") >> "$output"
  sleep 2
done
