#!/usr/bin/env bash
set -euo pipefail

checker="$(cd "$(dirname "$0")/.." && pwd)/check-load-generator.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/restless-load-generator.XXXXXX")"
trap 'rm -r "$tmp"' EXIT

cat > "$tmp/sysctl" <<'EOF'
#!/usr/bin/env bash
[[ "$1" == -n ]] || exit 2
[[ "$2" != "${TEST_MISSING_KEY:-}" ]] || exit 1
case "$2" in
  net.inet.ip.portrange.first) echo "${TEST_FIRST:-32768}" ;;
  net.inet.ip.portrange.last) echo "${TEST_LAST:-65535}" ;;
  net.inet.tcp.msl) echo "${TEST_MSL:-1000}" ;;
  *) exit 2 ;;
esac
EOF
cat > "$tmp/sudo" <<'EOF'
#!/usr/bin/env bash
touch "${0%/*}/sudo-called"
exit 1
EOF
chmod +x "$tmp/sysctl" "$tmp/sudo"
export PATH="$tmp:$PATH"

bash "$checker" preflight
for setting in TEST_FIRST=32769 TEST_LAST=65534 TEST_MSL=1001 TEST_MSL=invalid TEST_MISSING_KEY=net.inet.tcp.msl; do
  status=0
  env "$setting" bash "$checker" preflight > "$tmp/preflight.log" 2>&1 || status=$?
  [[ "$status" == 1 ]]
  grep -Fq 'sudo sysctl -w' "$tmp/preflight.log"
done
[[ ! -e "$tmp/sudo-called" ]]

echo 'level=warning msg="Request Failed" error="context deadline exceeded"' > "$tmp/k6.log"
bash "$checker" report "$tmp/k6.log" | grep -F '통과 (포트 할당 오류 0건, dial 오류 0건)'

cat > "$tmp/k6.log" <<'EOF'
level=warning msg="Request Failed" error="dial tcp 127.0.0.1:81: connect: can't assign requested address"
level=warning msg="Request Failed" error="dial tcp 127.0.0.1:81: connect: connection refused"
level=warning msg="Request Failed" error="dial: i/o timeout"
level=warning msg="Request Failed" error="connect: can't assign requested address"
EOF
status=0
bash "$checker" report "$tmp/k6.log" > "$tmp/verdict.md" || status=$?
[[ "$status" == 1 ]]
grep -F '무효 (포트 할당 오류 2건, dial 오류 3건)' "$tmp/verdict.md"

status=0
bash "$checker" report "$tmp/missing.log" 2> "$tmp/report-error.log" || status=$?
[[ "$status" == 2 ]]
