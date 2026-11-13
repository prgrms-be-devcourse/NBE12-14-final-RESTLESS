#!/usr/bin/env python3
"""Drop harness guard: stops harness violations early, while the agent is still working.

The guard is the first of three layers, not the verdict:
  1. guard.py codex-hook   Codex PreToolUse hook. Reads the hook JSON on stdin and blocks
                           (exit 2, reason on stderr) patches and shell commands that would break
                           the rules. Patches are checked exactly. Shell commands are checked by
                           pattern, so a reworded command can slip through (e.g. cd into a folder,
                           then a relative path). Layers 2 and 3 catch those.
  2. guard.py pre-commit   git pre-commit hook. Checks the staged diff with the same file rules.
  3. harness-check.sh      Diffs the branch against its base. This is the verdict.

Rules (same as harness-check.sh checks 1-4, plus tamper protection)
  - Harness files (acceptance tests, harness SQL, ArchUnit): only removing an @Disabled line
    and its import is allowed. No new, deleted or moved files.
  - Migrations: files already in origin/dev must not change. New (unmerged) ones may.
  - Other domains (ticketing, fanmeeting, auction, concert, credit, coupon): no changes, on any branch.
  - Harness tooling (tools/harness) and local state (.ai-output/harness, .codex, .git/hooks, .claude):
    no changes, except stage reports .ai-output/harness/reports/<STAGE><n>-report.md (D3, P72, ...).
  - Drop agent instructions (drop/AGENTS.md, drop/CLAUDE.md, git-ignored personal files): agents never edit them.
  - No new .md at the repository root besides README/AGENTS/CLAUDE/CONTRIBUTING/DESIGN/PRODUCT (AGENTS.md 7:
    notes go to .ai-output/, shared docs to docs/). Any branch, agent or commit.
  - No `git commit --no-verify`, no `git push`, no HARNESS_EDIT override, no hooksPath change.

Harness branches (chore/drop-*-harness) may change harness files, and may commit tools/harness
and the drop agent instructions. The Codex hook never lets an agent edit those, on any branch.
Every block is appended to .ai-output/harness/guard-blocks.log in the main checkout.
"""
import datetime
import json
import os
import re
import subprocess
import sys

# ---- Domain settings (drop). Another domain adopting the harness changes this block only. ----
REPO_MARKER = "NBE12-14-final-RESTLESS"  # folder name or origin URL must contain this
HARNESS_BRANCH = re.compile(r"^chore/drop-\d+-harness$")

PROTECTED = (
    "backend/src/test/java/com/restless/drop/acceptance/",
    "backend/src/test/resources/harness/",
    "backend/src/test/java/com/restless/architecture/",
)
MIGRATIONS = "backend/src/main/resources/db/migration/"
FOREIGN = re.compile(r"^backend/src/main/java/com/restless/(ticketing|fanmeeting|auction|concert|credit|coupon)/")
AGENT_DOCS = ("backend/src/main/java/com/restless/drop/AGENTS.md", "backend/src/main/java/com/restless/drop/CLAUDE.md")
# ---- End of domain settings. ----
# The only .md files allowed at the root. DESIGN.md / PRODUCT.md: shared design docs the design tool keeps there.
ROOT_DOCS = ("README.md", "AGENTS.md", "CLAUDE.md", "CONTRIBUTING.md", "DESIGN.md", "PRODUCT.md")
HARNESS_TOOLS = "tools/harness/"  # versioned tooling: harness branches may commit it
TOOLING = (HARNESS_TOOLS, ".ai-output/harness/", ".codex/", ".git/hooks/", ".claude/")
# Codex writes its stage report here (codex-stage-spec.md step 3).
REPORT = re.compile(r"^\.ai-output/harness/reports/[A-Za-z]+\d+-report\.md$")
REPORT_IN_CMD = re.compile(r"\.ai-output/harness/reports/[A-Za-z]+\d+-report\.md")
MERGED_REF = "origin/dev"


def merged(path, cwd):
    """True if the file already exists in origin/dev (merged migrations must not change)."""
    try:
        return subprocess.run(["git", "cat-file", "-e", f"{MERGED_REF}:{path}"], cwd=cwd,
                              capture_output=True).returncode == 0
    except OSError:
        return True  # cannot tell: treat as merged (safe side)

ALLOWED_REMOVED = (re.compile(r"^@Disabled\("), re.compile(r"^import org\.junit\.jupiter\.api\.Disabled;$"))

# Path fragments that mark a command as touching guarded files (relative-path independent).
GUARDED_FRAGMENTS = (
    "drop/acceptance", "test/resources/harness", "restless/architecture",
    "tools/harness", ".ai-output/harness", ".codex/", ".git/hooks", "hooksPath",
    "restless/ticketing", "restless/fanmeeting", "restless/auction",
    "restless/concert", "restless/credit", "restless/coupon",
    "drop/AGENTS.md", "drop/CLAUDE.md",
)
# Signs that a shell command writes files. Harmless redirects are stripped first.
WRITES = re.compile(
    r"(>|\btee\b|\bsed\b[^|;&]*\s-i|\bperl\b[^|;&]*\s-[a-z]*i|\bmv\b|\brm\b|\bcp\b|\bln\b|\bchmod\b|"
    r"\btruncate\b|\bdd\b|\binstall\b|\bpatch\b|\bpython3?\b|\bnode\b|\bruby\b|\bawk\b[^|;&]*-i|"
    r"\bgit\s+(checkout|restore|rm|mv|apply|am|stash|reset|clean)\b|\bapply_patch\b)"
)
HARMLESS_REDIRECT = re.compile(r"\d?>\s*/dev/null|\d>&\d|&>\s*/dev/null")
# Writes whose target can be read from the command itself (HD-10). Other writers stay conservative.
REDIRECT_TARGET = re.compile(r"(?:\d?>>?|&>>?)\s*([^\s;&|<>]+)|\btee\s+(?:-a\s+)?([^\s;&|<>]+)")
OPAQUE_WRITES = re.compile(WRITES.pattern.replace("(>|\\btee\\b|", "(", 1))


def git(args, cwd):
    try:
        r = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    except OSError:  # cwd removed (e.g. a deleted worktree)
        return ""
    return r.stdout.strip() if r.returncode == 0 else ""


def repo_root(cwd):
    """Main repository root (shared by all worktrees), or None if this is not the RESTLESS repo."""
    common = git(["rev-parse", "--path-format=absolute", "--git-common-dir"], cwd)
    if not common:
        return None
    root = os.path.dirname(common)
    if os.path.basename(root) == REPO_MARKER:
        return root
    return root if REPO_MARKER in git(["config", "--get", "remote.origin.url"], cwd) else None


def log_block(root, source, branch, reason):
    if os.environ.get("GUARD_NO_LOG") == "1":  # test runs only
        return
    path = os.path.join(root, ".ai-output/harness/guard-blocks.log")
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a", encoding="utf-8") as f:
            stamp = datetime.datetime.now().isoformat(timespec="seconds")
            f.write(f"{stamp}\t{source}\t{branch}\t{reason.splitlines()[0]}\n")
    except OSError:
        pass


def file_reason(path, op, removed, added, harness_branch, cwd=".", agent=True):
    """Reason to block a change to one file, or None. op: add/delete/update/move.
    agent=True for the Codex hook (an agent edits), False for pre-commit (a commit on a branch)."""
    if REPORT.match(path) and op in ("add", "update"):
        return None
    if path.startswith(HARNESS_TOOLS) and harness_branch and not agent:
        return None
    if path in AGENT_DOCS:
        if harness_branch and not agent:
            return None
        return f"{path}: 드롭 지침 파일은 하네스 브랜치의 하네스 PR에서만 고칠 수 있음"
    if op == "add" and "/" not in path and path.endswith(".md") and path not in ROOT_DOCS:
        return f"{path}: 레포 루트에 새 .md 금지. 메모·보고서는 .ai-output/, 팀 문서는 docs/에 둘 것 (AGENTS.md 7절)"
    if path.startswith(TOOLING) or "/.git/hooks/" in path:
        return f"{path}: 하네스 도구·가드 파일은 수정할 수 없음"
    if FOREIGN.match(path):
        return f"{path}: 다른 도메인 코드는 수정할 수 없음"
    if path.startswith(MIGRATIONS) and op != "add" and merged(path, cwd):
        return f"{path}: 머지된 마이그레이션은 수정할 수 없음 (새 마이그레이션 파일을 추가할 것)"
    if path.startswith(PROTECTED) and not harness_branch:
        if op != "update":
            return f"{path}: 하네스 폴더에 파일 추가·삭제·이동 금지"
        bad = [l for l in removed if not any(p.match(l.strip()) for p in ALLOWED_REMOVED)]
        if added or bad:
            return f"{path}: 하네스 파일은 @Disabled 줄(과 그 import) 삭제만 허용. 테스트가 계약과 다르면 고치지 말고 보고서에 적을 것"
    return None


def parse_patch(text):
    """apply_patch text -> list of (path, op, removed_lines, added_lines)."""
    files, cur = [], None
    for line in text.splitlines():
        m = re.match(r"^\*\*\* (Add|Delete|Update) File: (.+)$", line)
        if m:
            cur = [m.group(2).strip(), m.group(1).lower(), [], []]
            files.append(cur)
            continue
        m = re.match(r"^\*\*\* Move to: (.+)$", line)
        if m and cur:
            cur[1] = "move"
            files.append([m.group(1).strip(), "add", [], []])
            continue
        if cur is None or line.startswith(("***", "@@")):
            continue
        if line.startswith("-"):
            cur[2].append(line[1:])
        elif line.startswith("+"):
            cur[3].append(line[1:])
    return [tuple(f) for f in files]


def rel(path, root, cwd):
    p = path if os.path.isabs(path) else os.path.join(cwd, path)
    p = os.path.realpath(p)
    for base in {os.path.realpath(root), os.path.realpath(git(["rev-parse", "--show-toplevel"], cwd) or root)}:
        if p.startswith(base + os.sep):
            return os.path.relpath(p, base)
    return path


def strings(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for v in node.values():
            yield from strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from strings(v)


def command_text(tool_input):
    cmd = tool_input.get("command") if isinstance(tool_input, dict) else None
    if isinstance(cmd, list):
        return " ".join(map(str, cmd))
    return cmd if isinstance(cmd, str) else ""


def command_reason(cmd):
    if re.search(r"\bgit\b[^;&|]*\bcommit\b[^;&|]*(\s--no-verify\b|\s-[a-zA-Z]*n[a-zA-Z]*\b)", cmd):
        return "git commit --no-verify 금지 (pre-commit 가드를 건너뛸 수 없음)"
    if re.search(r"\bgit\b[^;&|]*\bpush\b", cmd):
        return "git push 금지 (push와 PR은 Claude가 한다)"
    if "HARNESS_EDIT" in cmd:
        return "HARNESS_EDIT 사용 금지 (하네스 PR 전용)"
    if re.search(r"core\.hooksPath|--no-verify", cmd):
        return "git hook 우회 금지"
    stripped = HARMLESS_REDIRECT.sub(" ", cmd)
    fragments = GUARDED_FRAGMENTS
    stripped_reports = REPORT_IN_CMD.sub(" ", stripped)
    hit = next((f for f in fragments if f in stripped_reports), None)
    if hit and WRITES.search(stripped) and not OPAQUE_WRITES.search(stripped):
        # Only redirects/tee write here: allow when none of their targets is a guarded path,
        # e.g. `bash .ai-output/harness/harness-check.sh > .ai-output/x.log`.
        targets = [a or b for a, b in REDIRECT_TARGET.findall(REPORT_IN_CMD.sub(" ", stripped))]
        if not any(f in t for t in targets for f in fragments):
            hit = None
    if hit and WRITES.search(stripped):
        return (f"보호 경로({hit})를 셸 명령으로 바꾸거나 덮어쓸 수 없음. "
                "읽기는 cat/grep/sed -n 등을 리다이렉트 없이 쓸 것. @Disabled 줄 삭제는 apply_patch로 할 것")
    return None


def codex_hook():
    raw = sys.stdin.read()
    try:
        event = json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError:
        return 0
    cwd = event.get("cwd") or os.getcwd()
    root = repo_root(cwd)
    if not root:
        return 0
    branch = git(["branch", "--show-current"], cwd)
    harness_branch = bool(HARNESS_BRANCH.match(branch))
    tool_input = event.get("tool_input", event)

    reasons = []
    for s in strings(tool_input):
        if "*** Begin Patch" in s:
            for path, op, removed, added in parse_patch(s):
                r = file_reason(rel(path, root, cwd), op, removed, added, harness_branch, cwd)
                if r:
                    reasons.append(r)
    cmd = command_text(tool_input)
    if cmd and "*** Begin Patch" not in cmd:
        r = command_reason(cmd)
        if r:
            reasons.append(r)

    if not reasons:
        return 0
    reason = "[drop harness guard] 차단:\n- " + "\n- ".join(reasons)
    log_block(root, f"codex:{event.get('tool_name', '?')}", branch, "; ".join(reasons))
    print(reason, file=sys.stderr)
    return 2


def pre_commit():
    cwd = os.getcwd()
    root = repo_root(cwd)
    if not root:
        return 0
    branch = git(["branch", "--show-current"], cwd)
    harness_branch = bool(HARNESS_BRANCH.match(branch))
    status = git(["diff", "--cached", "--name-status", "-M"], cwd)
    # Merge commit: files equal to the merged-in side are someone else's already-reviewed work
    # (e.g. dev's fan meeting code). Check only what differs from BOTH parents (HD-11).
    merge_changed = None
    if git(["rev-parse", "-q", "--verify", "MERGE_HEAD"], cwd):
        merge_changed = set(git(["diff", "--cached", "--name-only", "MERGE_HEAD"], cwd).splitlines())
    reasons = []
    for line in status.splitlines():
        parts = line.split("\t")
        code = parts[0][0]
        paths = parts[1:]
        if merge_changed is not None and not any(p in merge_changed for p in paths):
            continue
        op = {"A": "add", "D": "delete", "M": "update", "R": "move", "C": "add"}.get(code, "update")
        for i, path in enumerate(paths):
            removed, added = [], []
            if op == "update":
                diff = git(["diff", "--cached", "-U0", "--", path], cwd)
                for l in diff.splitlines():
                    if l.startswith(("+++", "---")):
                        continue
                    if l.startswith("-"):
                        removed.append(l[1:])
                    elif l.startswith("+"):
                        added.append(l[1:])
            this_op = "add" if (op == "move" and i == 1) else op
            r = file_reason(path, this_op, removed, added, harness_branch, cwd, agent=False)
            if r:
                reasons.append(r)
    if not reasons:
        return 0
    log_block(root, "pre-commit", branch, "; ".join(reasons))
    print("[drop harness guard] 커밋 차단:\n- " + "\n- ".join(reasons), file=sys.stderr)
    print("하네스 파일은 @Disabled 줄 삭제만 허용된다. 테스트·계약이 틀려 보이면 고치지 말고 보고서에 적는다.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "codex-hook":
        sys.exit(codex_hook())
    if mode == "pre-commit":
        sys.exit(pre_commit())
    print(__doc__, file=sys.stderr)
    sys.exit(64)
