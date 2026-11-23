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
printf '%s\n' 'timestamp,hikaricp_connections_active,hikaricp_connections_pending,hikaricp_connections_timeout_total,tomcat_threads_busy_threads,tomcat_connections_current_connections,process_cpu_usage,system_cpu_usage,auction_stream_connections,http_server_requests_active_seconds_gcount,jvm_heap_used_bytes,jvm_heap_committed_bytes,jvm_heap_max_bytes,jvm_gc_pause_seconds_sum,jvm_gc_pause_seconds_count,jvm_threads_live_threads,process_files_open_files' > "$output"

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
  (IFS=,; printf '%s,%s\n' "$timestamp" "${values[*]}") >> "$output"
  sleep 2
done
