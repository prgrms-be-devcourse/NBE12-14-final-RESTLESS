#!/usr/bin/env bash
# Installs the harness guard for this clone (opt-in, local only). Run once from the main checkout:
#   bash tools/harness/install.sh
# - .git/hooks/harness-guard : launcher that runs the guard from the main checkout
# - .git/hooks/pre-commit    : calls the launcher (blocks the commit if the guard is missing)
# - prints the Codex PreToolUse hook entry to add to ~/.codex/hooks.json (not edited automatically)
set -euo pipefail
COMMON="$(git rev-parse --path-format=absolute --git-common-dir)"
ROOT="$(dirname "$COMMON")"
HOOKS="$COMMON/hooks"
mkdir -p "$HOOKS"

cat > "$HOOKS/harness-guard" <<'SH'
#!/bin/sh
# Harness guard launcher (installed by tools/harness/install.sh). Usage: harness-guard codex-hook|pre-commit
ROOT="__ROOT__"   # main checkout, fixed at install time (the guard itself checks which repo an event is in)
GUARD="$ROOT/tools/harness/guard/guard.py"
[ -f "$GUARD" ] || GUARD="$ROOT/.ai-output/harness/guard/guard.py"   # before tools/harness reached this checkout
if [ ! -f "$GUARD" ]; then
  echo "[harness guard] guard.py가 없음: $ROOT/tools/harness/guard/" >&2
  # pre-commit fails closed. The Codex hook fails open: pre-commit and harness-check.sh still check.
  [ "$1" = "pre-commit" ] && exit 1 || { cat >/dev/null; exit 0; }
fi
exec python3 "$GUARD" "$@"
SH
sed -i.bak "s#__ROOT__#$ROOT#" "$HOOKS/harness-guard" && rm -f "$HOOKS/harness-guard.bak"
chmod +x "$HOOKS/harness-guard"

if [ -f "$HOOKS/pre-commit" ] && ! grep -q "harness" "$HOOKS/pre-commit"; then
  echo "기존 pre-commit hook이 있어 건드리지 않음: $HOOKS/pre-commit" >&2
  echo "그 hook 안에서 '$HOOKS/harness-guard pre-commit'을 호출하도록 직접 추가할 것" >&2
else
  printf '#!/bin/sh\n# Drop harness guard (local only, installed by tools/harness/install.sh)\nexec "%s" pre-commit\n' \
    "$HOOKS/harness-guard" > "$HOOKS/pre-commit"
  chmod +x "$HOOKS/pre-commit"
  echo "설치함: $HOOKS/pre-commit -> harness-guard"
fi

cat <<EOF

Codex 편집 hook: ~/.codex/hooks.json 의 "PreToolUse" 목록에 아래 항목을 넣는다 (이미 있으면 command만 바꾼다).
Codex를 다시 시작하고, hook 확인 창에서 이 hook을 신뢰해야 동작한다.

  { "hooks": [ { "type": "command", "command": "sh $HOOKS/harness-guard codex-hook", "timeout": 10 } ] }

확인: python3 $ROOT/tools/harness/guard/test_guard.py <작업 폴더>
EOF
