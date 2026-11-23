#!/usr/bin/env bash
# 관리 포트의 Prometheus 텍스트를 일정 간격으로 파일에 남긴다. 창별 서버 지표를 k6 원자료와 같은 시간축에 맞추는 데 쓴다.
# 파일 이름은 수집 시각(UTC)이다. 실행 전후 스냅샷만으로는 창별 원인을 볼 수 없어서 쓴다.
#
#   prom-collect.sh <MANAGEMENT_URL> <out-dir> [interval-seconds=5] [watched-pid]
#
# watched-pid를 주면 그 프로세스가 끝날 때 같이 끝난다. 없으면 Ctrl+C로 멈춘다.
# 노트북에서 돌린다. 호스트 이름은 k6와 같이 perf-로 시작해야 한다 (/etc/hosts에 perf-app을 둔다).
set -uo pipefail

if [[ $# -lt 2 || $# -gt 4 ]]; then
  sed -n '2,8p' "$0" >&2
  exit 2
fi
management_url=${1%/}
out_dir=$2
interval=${3:-5}
watched_pid=${4:-}

host=${management_url#*://}; host=${host%%[:/]*}
shopt -s nocasematch
if [[ "$host" != perf-* || "$host" == *prod* ]]; then
  echo "prom-collect: host $host must start with perf- and must not look like production" >&2
  exit 1
fi
shopt -u nocasematch
[[ "$interval" =~ ^[0-9]+$ && "$interval" -ge 1 ]] || { echo "prom-collect: interval must be a positive integer" >&2; exit 2; }

mkdir -p "$out_dir"
echo "prom-collect: $management_url/actuator/prometheus every ${interval}s -> $out_dir"
trap 'echo "prom-collect: stopped"; exit 0' INT TERM
while :; do
  if [[ -n "$watched_pid" ]] && ! kill -0 "$watched_pid" 2>/dev/null; then break; fi
  started=$(date +%s)
  stamp=$(date -u '+%Y%m%dT%H%M%SZ')
  # 실패한 수집은 빈 파일 대신 .failed로 남겨 빠진 시각이 보이게 한다.
  if curl --silent --fail --max-time 4 "$management_url/actuator/prometheus" -o "$out_dir/$stamp.prom.tmp"; then
    mv "$out_dir/$stamp.prom.tmp" "$out_dir/$stamp.prom"
  else
    rm -f "$out_dir/$stamp.prom.tmp"; : > "$out_dir/$stamp.failed"
  fi
  elapsed=$(( $(date +%s) - started ))
  (( elapsed < interval )) && sleep $(( interval - elapsed ))
done
echo "prom-collect: watched process ended"
