#!/usr/bin/env bash
# macOS k6 부하 발생기 사전 검사와 k6 로그 판정.
set -euo pipefail

case "${1:-}" in
  preflight)
    first=$(sysctl -n net.inet.ip.portrange.first 2>/dev/null) || first=
    last=$(sysctl -n net.inet.ip.portrange.last 2>/dev/null) || last=
    msl=$(sysctl -n net.inet.tcp.msl 2>/dev/null) || msl=
    printf 'net.inet.ip.portrange.first=%s net.inet.ip.portrange.last=%s net.inet.tcp.msl=%s\n' \
      "${first:-unavailable}" "${last:-unavailable}" "${msl:-unavailable}"
    if [[ "$first" =~ ^[1-9][0-9]{0,4}$ && "$last" =~ ^[1-9][0-9]{0,4}$ && "$msl" =~ ^(0|[1-9][0-9]{0,3})$ ]] &&
      (( first <= 32768 && last >= 65535 && msl <= 1000 )); then
      echo '부하 발생기 사전 검사 통과'
      exit 0
    fi
    cat >&2 <<'EOF'
부하 발생기 사전 검사 실패. k6 실행을 중단한다.
macOS 부하 발생기에서 아래 명령을 직접 실행한 뒤 사전 검사를 다시 실행한다.
sudo sysctl -w net.inet.ip.portrange.first=32768 net.inet.ip.portrange.last=65535 net.inet.tcp.msl=1000
EOF
    exit 1
    ;;
  report)
    if [[ $# -ne 2 || ! -f "$2" || ! -r "$2" || ! -s "$2" ]]; then
      echo '부하 발생기 판정에 내용이 있는 k6.log가 필요하다.' >&2
      exit 2
    fi
    # 한 로그 줄에서 각 오류를 한 번 센다. 포트 할당 오류는 dial 오류와 겹칠 수 있다.
    awk -v port_error="can't assign requested address" '
      {
        line = tolower($0)
        if (index(line, port_error)) port++
        if (line ~ /(^|[^[:alnum:]_])dial(:| (tcp[46]?|udp[46]?|ip[46]?|unix[[:alpha:]]*|i\/o timeout)([[:space:]:]|$))/) dial++
      }
      END {
        invalid = port + dial > 0
        printf "- 부하 발생기 로그 검사 %s (포트 할당 오류 %d건, dial 오류 %d건)\n", invalid ? "무효" : "통과", port, dial
        exit invalid
      }
    ' < "$2"
    ;;
  *)
    echo 'usage: check-load-generator.sh preflight | report <k6.log>' >&2
    exit 2
    ;;
esac
