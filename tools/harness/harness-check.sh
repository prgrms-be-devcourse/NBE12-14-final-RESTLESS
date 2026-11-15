#!/usr/bin/env bash
# Drop harness check (the verdict). Run from the folder to check:  bash tools/harness/harness-check.sh [base-ref]
# Exit 0 = pass (warnings allowed), 1 = fail. Prints a summary at the end. See tools/harness/README.md.
set -o pipefail   # no -u: macOS bash 3.2 treats empty arrays as unbound

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_MARKER="NBE12-14-final-RESTLESS"
# Check the folder the command runs in (a worktree for parallel stages), not the folder holding this script.
is_restless() { case "$1|$(git -C "$1" config --get remote.origin.url 2>/dev/null)" in *"$REPO_MARKER"*) return 0;; esac; return 1; }
REPO="$(git rev-parse --show-toplevel 2>/dev/null)"
{ [ -n "$REPO" ] && is_restless "$REPO"; } || REPO="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
FOLDER_TAG="$(basename "$REPO")"
cd "$REPO" || exit 1
# Local state (lock, build log) lives in the main checkout's git-ignored .ai-output/harness, shared by worktrees.
STATE_DIR="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")/.ai-output/harness"
mkdir -p "$STATE_DIR"
# Base: origin/dev, unless this branch is stacked on an unmerged harness branch (HARNESS_BRANCH):
# then the newest harness commit it contains, so the harness changes are not counted as this
# branch's changes (HD-01). Works after a squash merge too. An explicit argument wins.
HARNESS_BRANCH="${HARNESS_BRANCH:-chore/drop-80-harness}"
STACK_BASE=""
if git rev-parse -q --verify "$HARNESS_BRANCH" >/dev/null; then
  STACK_BASE="$(git merge-base HEAD "$HARNESS_BRANCH" 2>/dev/null)"
  [ -n "$STACK_BASE" ] && git merge-base --is-ancestor "$STACK_BASE" origin/dev 2>/dev/null && STACK_BASE=""
fi
if [ -n "${1:-}" ]; then BASE_REF="$1"
elif [ -n "$STACK_BASE" ] && [ "$(git branch --show-current)" != "$HARNESS_BRANCH" ]; then BASE_REF="$STACK_BASE"
else BASE_REF="origin/dev"; fi
BASE="$(git merge-base HEAD "$BASE_REF")"
FAILS=()
WARNS=()
fail() { FAILS+=("$1"); }
warn() { WARNS+=("$1"); }

echo "== harness-check  base=$BASE_REF ($(git rev-parse --short "$BASE"))  head=$(git rev-parse --short HEAD)"

# HARNESS_EDIT=1: the harness PR itself (Claude only). Skips checks 1 and 3, which guard harness files.
HARNESS_EDIT="${HARNESS_EDIT:-0}"
[ "$HARNESS_EDIT" = 1 ] && warn "HARNESS_EDIT=1: 하네스 파일 보호 검사를 건너뜀 (하네스 PR 전용)"

# Untracked files are invisible to git diff; check their paths too.
UNTRACKED="$(git ls-files --others --exclude-standard)"
if [ -n "$(git status --porcelain)" ]; then warn "커밋되지 않은 변경이 있음 (최종 판정은 커밋 후에)"; fi
if [ "$HARNESS_EDIT" != 1 ]; then
  U_PROT="$(echo "$UNTRACKED" | grep -E '^(backend/src/test/java/com/restless/drop/acceptance|backend/src/test/resources/harness|backend/src/test/java/com/restless/architecture)/')"
  [ -n "$U_PROT" ] && fail "하네스 폴더에 새 파일 추가 금지:"$'\n'"$U_PROT"
fi
U_FOREIGN="$(echo "$UNTRACKED" | grep -E '^backend/src/main/java/com/restless/(ticketing|fanmeeting|auction|concert|credit|coupon)/')"
[ -n "$U_FOREIGN" ] && fail "다른 도메인에 새 파일 추가 금지:"$'\n'"$U_FOREIGN"

# 1. Harness files: only removing an @Disabled line (and its import) is allowed.
PROTECTED="backend/src/test/java/com/restless/drop/acceptance backend/src/test/resources/harness backend/src/test/java/com/restless/architecture"
CHANGED_PROTECTED="$(git diff --name-only "$BASE" -- $PROTECTED)"
if [ -n "$CHANGED_PROTECTED" ] && [ "$HARNESS_EDIT" != 1 ]; then
  BAD="$(git diff -U0 "$BASE" -- $PROTECTED | grep -E '^[+-]' | grep -vE '^(\+\+\+|---) ' \
        | grep -vE '^-@Disabled\(' | grep -vE '^-import org\.junit\.jupiter\.api\.Disabled;$')"
  if [ -n "$BAD" ]; then
    fail "하네스 파일 수정 금지 (@Disabled 줄 삭제만 허용):"$'\n'"$(echo "$BAD" | head -20)"
  else
    echo "-- 켠 수용 테스트: $(echo $CHANGED_PROTECTED | tr '\n' ' ')"
  fi
fi

# 1b. Versioned harness tooling (tools/harness) and the drop agent instructions change only in a harness PR.
AGENT_DOCS="backend/src/main/java/com/restless/drop/AGENTS.md backend/src/main/java/com/restless/drop/CLAUDE.md"
CHANGED_TOOLS="$(git diff --name-only "$BASE" -- tools/harness $AGENT_DOCS; echo "$UNTRACKED" | grep -E '^(tools/harness/|backend/src/main/java/com/restless/drop/(AGENTS|CLAUDE)\.md$)')"
CHANGED_TOOLS="$(echo "$CHANGED_TOOLS" | sed '/^$/d')"
[ -n "$CHANGED_TOOLS" ] && [ "$HARNESS_EDIT" != 1 ] && fail "하네스 도구·드롭 지침 수정 금지:"$'\n'"$CHANGED_TOOLS"

# 2. Merged migrations must not change (only new files allowed).
MIG="$(git diff --name-status "$BASE" -- backend/src/main/resources/db/migration | grep -vE '^A')"
[ -n "$MIG" ] && fail "머지된 마이그레이션 수정/삭제 금지:"$'\n'"$MIG"

# 3. Disabling tests anywhere is forbidden.
ADDED_DISABLED="$(git diff -U0 "$BASE" -- backend/src/test | grep -E '^\+([^+].*)?@([A-Za-z_]+\.)*Disabled' )"
[ -n "$ADDED_DISABLED" ] && [ "$HARNESS_EDIT" != 1 ] && fail "테스트 비활성화(@Disabled 추가) 금지:"$'\n'"$ADDED_DISABLED"

# 4. Other domains' code (auth/member allowed with warning: e.g. SecurityConfig public paths).
FOREIGN="$(git diff --name-only "$BASE" -- backend/src/main/java/com/restless | grep -E '/restless/(ticketing|fanmeeting|auction|concert|credit|coupon)/')"
[ -n "$FOREIGN" ] && fail "다른 도메인 코드 수정 금지:"$'\n'"$FOREIGN"
SHARED="$(git diff --name-only "$BASE" -- backend/src/main/java/com/restless | grep -E '/restless/(auth|member|common)/')"
[ -n "$SHARED" ] && warn "공용 코드 수정 (리뷰 필요): $(echo $SHARED | tr '\n' ' ')"

# 5. Risky patterns in added lines.
ADDED="$(git diff -U0 "$BASE" -- backend/src | grep -E '^\+' | grep -vE '^\+\+\+ ')"
echo "$ADDED" | grep -qE 'counter\([^)]*"[A-Za-z]*([Ii]d|Key)"' && warn "메트릭 태그에 ID/키처럼 끝없이 늘어나는 값이 있음"
for f in $(git diff --name-only "$BASE" -- backend/src/test | grep -E '\.java$'); do
  [ -f "$f" ] || continue
  if grep -q 'Instant.parse(' "$f" && grep -q 'JwtTokenProvider\|tokens.issue' "$f" && ! grep -q 'Clock.systemUTC()' "$f"; then
    warn "$f: 고정 날짜 + JWT 발급 (시간이 지나면 401로 깨질 수 있음)"
  fi
done
echo "$ADDED" | grep -E '^\+.*Thread\.sleep' | grep -v '/test/' >/dev/null && warn "운영 코드에 Thread.sleep"
git diff --check "$BASE" >/dev/null 2>&1 || fail "git diff --check 실패 (공백 오류)"

# 5b. Docs (doc-check.py): broken links and drop docs that may have drifted from the code.
# Drop docs fail; other owners' docs only warn. Runs the copy next to this script.
DOC_OUT="$(python3 "$SCRIPT_DIR/doc-check.py" "$REPO" 2>&1)"
while IFS= read -r l; do
  case "$l" in "FAIL: "*) fail "문서: ${l#FAIL: }";; "WARN: "*) warn "문서: ${l#WARN: }";; esac
done <<< "$DOC_OUT"
echo "$DOC_OUT" | grep -q '^DOC-CHECK: ' || fail "문서 검사(doc-check.py)가 결과를 내지 못함: $(echo "$DOC_OUT" | tail -1)"

# 6. Build + all tests (CI와 같음). SKIP_BUILD=1 skips it (quick rule checks only, never for a final verdict).
if [ "${SKIP_BUILD:-0}" = 1 ]; then
  warn "SKIP_BUILD=1: 빌드·테스트를 건너뜀 (최종 판정에 쓰지 말 것)"
  for w in "${WARNS[@]}"; do echo "WARN: $w"; done
  for f in "${FAILS[@]}"; do echo "FAIL: $f"; done
  [ ${#FAILS[@]} -eq 0 ] && { echo "RESULT: PASS (rules only)"; exit 0; } || { echo "RESULT: FAIL (${#FAILS[@]})"; exit 1; }
fi
# Build lock (HD-02): one full build per working folder. Waits for another harness-check build
# or any Gradle test worker running from this folder, then holds a lock until the script exits.
LOCK="$STATE_DIR/.build.lock-$FOLDER_TAG"
WAITED=0
while true; do
  if mkdir "$LOCK" 2>/dev/null; then echo $$ > "$LOCK/pid"; trap 'rm -rf "$LOCK"' EXIT; fi
  OWNER="$(cat "$LOCK/pid" 2>/dev/null)"
  if [ "$OWNER" = "$$" ] && ! pgrep -f "$REPO/backend/build/tmp/test" >/dev/null; then break; fi
  if [ -n "$OWNER" ] && [ "$OWNER" != "$$" ] && ! kill -0 "$OWNER" 2>/dev/null; then rm -rf "$LOCK"; continue; fi
  [ $WAITED -eq 0 ] && echo "-- 다른 빌드가 이 폴더에서 실행 중이라 기다림"
  WAITED=$((WAITED+5)); [ $WAITED -gt 1800 ] && { echo "RESULT: FAIL (다른 빌드를 30분 기다렸지만 끝나지 않음)"; exit 1; }
  sleep 5
done
START_HEAD="$(git rev-parse HEAD)"; START_BRANCH="$(git branch --show-current)"
# Start Docker Desktop on macOS if it is not running (Testcontainers needs it).
( docker info >/dev/null 2>&1 ) || { command -v open >/dev/null && open -a Docker >/dev/null 2>&1; for _ in $(seq 1 40); do docker info >/dev/null 2>&1 && break; sleep 3; done; }
LOG="$STATE_DIR/last-build-$FOLDER_TAG.log"
# HD-09: CI builds "PR merged into dev", not the branch. Two changes that pass alone can break together
# (enum separator, a default that another stage changed). When HEAD is behind origin/dev, build the
# merge result in a temporary worktree instead. NO_DEV_MERGE=1 builds HEAD as is.
BUILD_DIR="$REPO"; MERGED_WT=""
git fetch -q origin dev 2>/dev/null || warn "origin/dev를 받지 못함 (네트워크). 마지막으로 받은 dev 기준으로 검사"
if [ "${NO_DEV_MERGE:-0}" != 1 ] && ! git merge-base --is-ancestor origin/dev HEAD; then
  if MERGED_TREE="$(git merge-tree --write-tree HEAD origin/dev 2>/dev/null)"; then
    MERGED_COMMIT="$(git commit-tree "$MERGED_TREE" -p HEAD -p origin/dev -m "harness-check: HEAD + origin/dev")"
    MERGED_WT="${TMPDIR:-/tmp}/harness-merged-$FOLDER_TAG"
    git worktree remove --force "$MERGED_WT" >/dev/null 2>&1; rm -rf "$MERGED_WT"
    git worktree add -q --detach "$MERGED_WT" "$MERGED_COMMIT"
    trap 'rm -rf "$LOCK"; git -C "$REPO" worktree remove --force "$MERGED_WT" >/dev/null 2>&1' EXIT
    BUILD_DIR="$MERGED_WT"
    echo "-- 빌드 대상: HEAD + origin/dev($(git rev-parse --short origin/dev)) 합친 결과 (CI와 같음)"
  else
    fail "최신 origin/dev와 충돌함. dev를 합쳐 충돌을 정리한 뒤 다시 검사할 것 (CI는 합친 결과를 빌드한다)"
    BUILD_DIR=""
  fi
else
  echo "-- 빌드 대상: HEAD (이미 최신 dev 포함)"
fi
if [ -z "$BUILD_DIR" ]; then BUILD=skipped
elif (cd "$BUILD_DIR/backend" && ./gradlew cleanTest build >"$LOG" 2>&1); then BUILD=ok
else BUILD=failed; fail "./gradlew cleanTest build 실패 (로그: $LOG)"; fi
SUMMARY="$(python3 - "${BUILD_DIR:-$REPO}/backend/build/test-results/test" <<'PY'
import glob,re,sys,html
t=f=e=s=0; bad=[]
for x in glob.glob(sys.argv[1]+'/*.xml'):
    full=open(x,encoding='utf-8').read(); m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"',full[:1000])
    if not m: continue
    a,b,c,d=map(int,m.groups()); t+=a;s+=b;f+=c;e+=d
    for n in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<(?:failure|error)',full): bad.append(n.group(2).split('.')[-1]+' > '+html.unescape(n.group(1)))
print(f"tests={t} failures={f} errors={e} skipped={s}")
for b in bad[:15]: print("  FAILED", b)
PY
)"
[ "$BUILD" = skipped ] && SUMMARY="(충돌로 빌드하지 않음)"
echo "-- build: $BUILD  $SUMMARY"
# HD-04: the working folder must not move during the build, or the result belongs to nothing.
if [ "$(git rev-parse HEAD)" != "$START_HEAD" ] || [ "$(git branch --show-current)" != "$START_BRANCH" ]; then
  fail "검사 도중 작업 폴더의 브랜치/커밋이 바뀜 ($START_BRANCH@${START_HEAD:0:7} → $(git branch --show-current)@$(git rev-parse --short HEAD)). 결과 무효, 다시 실행할 것"
fi
ACC="$(grep -l '^@Disabled' backend/src/test/java/com/restless/drop/acceptance/*.java 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"
echo "-- 아직 꺼진 수용 테스트: ${ACC:-없음}"

echo
for w in "${WARNS[@]}"; do echo "WARN: $w"; done
for f in "${FAILS[@]}"; do echo "FAIL: $f"; done
if [ ${#FAILS[@]} -eq 0 ]; then echo "RESULT: PASS (warnings ${#WARNS[@]})"; exit 0; else echo "RESULT: FAIL (${#FAILS[@]})"; exit 1; fi
