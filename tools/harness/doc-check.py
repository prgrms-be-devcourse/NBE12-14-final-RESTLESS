#!/usr/bin/env python3
"""Doc check: finds documents that no longer match the repository.

Usage: python3 tools/harness/doc-check.py [repo-root] [--all]
Prints FAIL:/WARN: lines, then "DOC-CHECK: PASS|FAIL". Exit 1 when anything fails.
harness-check.sh runs it and turns the lines into its own FAIL/WARN.

Checks
  1. Links. Relative markdown links and backticked repo paths (`backend/...`, `docs/...`,
     `tools/...`, `frontend/...`) must exist. Notion exports (`attachment:...`) count as broken.
     Fenced code blocks are skipped (trees and examples are not links).
  2. Freshness header (owned docs only, except index pages). Right under the title:
         > 관련 코드: `backend/src/main/java/com/restless/drop/domain/`, `...`
         > 마지막 확인: 2026-11-14
     `관련 코드: 없음 (<reason>)` is allowed when no code exists yet.
     Both lines are required and every related path must exist (globs allowed).
     When git shows a commit touching a related path after that date, it warns: compare the doc
     with the code, fix what differs, and move the date.

Only owned (drop) docs are checked, and their problems fail. Other owners' docs are not the
harness's business: a teammate's broken link must not fail or clutter a drop stage. --all also
lists their broken links as warnings, for an occasional look.
"""
import glob
import os
import re
import subprocess
import sys
from urllib.parse import unquote

# ---- Domain settings (drop). Another domain adopting the harness changes this block only. ----
OWNED = (
    "docs/domains/drop/",
    "docs/contracts/api/drop.md",
    "docs/contracts/events/drop.md",
    "tools/harness/README.md",
    "backend/src/main/java/com/restless/drop/AGENTS.md",
)
FRESHNESS = ("docs/domains/drop/", "docs/contracts/api/drop.md", "docs/contracts/events/drop.md")
FRESHNESS_EXEMPT = ("docs/domains/drop/README.md",)  # index pages
SCAN = ("docs", "AGENTS.md", "README.md", "CONTRIBUTING.md", "tools/harness/README.md",
        "backend/src/main/java/com/restless/drop/AGENTS.md")
# ---- End of domain settings. ----

LINK = re.compile(r"!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
CODE_PATH = re.compile(r"`((?:backend|frontend|docs|tools)/[^`\s]+)`")
PLACEHOLDER = re.compile(r"[<>{}]|\.\.\.|…")
RELATED = re.compile(r"^>?\s*관련 코드:\s*(.+)$", re.M)
CHECKED = re.compile(r"^>?\s*마지막 확인:\s*(\d{4}-\d{2}-\d{2})\b.*$", re.M)


def owned(path, prefixes=OWNED):
    return any(path == p or (p.endswith("/") and path.startswith(p)) for p in prefixes)


def doc_files(root):
    out = []
    for entry in SCAN:
        full = os.path.join(root, entry)
        if os.path.isdir(full):
            out += [os.path.relpath(p, root) for p in glob.glob(os.path.join(full, "**/*.md"), recursive=True)]
        elif os.path.isfile(full):
            out.append(entry)
    return sorted(set(out))


def strip_fences(text):
    """Blank out fenced code blocks, keeping line count."""
    out, fence = [], None
    for line in text.splitlines():
        m = re.match(r"^\s*(```|~~~)", line)
        if m and fence is None:
            fence = m.group(1)
            out.append("")
        elif m and line.strip().startswith(fence):
            fence = None
            out.append("")
        else:
            out.append("" if fence else line)
    return "\n".join(out)


def exists(root, path):
    if any(c in path for c in "*?["):
        return bool(glob.glob(os.path.join(root, path)))
    return os.path.exists(os.path.join(root, path))


def broken_links(root, doc):
    text = strip_fences(open(os.path.join(root, doc), encoding="utf-8").read())
    bad = []
    for m in LINK.finditer(text):
        target = m.group(1)
        if target.startswith("attachment:"):
            bad.append(target[:60] + " (노션 첨부, 레포에 없음)")
            continue
        if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
            continue  # http:, mailto:, in-page anchors
        target = unquote(target.split("#", 1)[0])
        base = root if target.startswith("/") else os.path.join(root, os.path.dirname(doc))
        if not os.path.exists(os.path.normpath(os.path.join(base, target.lstrip("/")))):
            bad.append(target)
    for m in CODE_PATH.finditer(text):
        path = re.sub(r":\d+(-\d+)?$", "", m.group(1)).rstrip(".,;:")
        if PLACEHOLDER.search(path):
            continue
        if not exists(root, path.rstrip("/")):
            bad.append(f"`{path}`")
    return bad


def git_last_change(root, paths):
    specs = [f":(glob){p.rstrip('/')}" + ("/**" if p.endswith("/") else "") for p in paths]
    # Docs inside a code folder (e.g. drop/AGENTS.md) are not code changes.
    specs.append(":(exclude,glob)**/*.md")
    r = subprocess.run(["git", "log", "-1", "--format=%cs", "--", *specs], cwd=root,
                       capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else ""


def freshness(root, doc):
    """(fails, warns) for one doc's header."""
    head = "\n".join(open(os.path.join(root, doc), encoding="utf-8").read().splitlines()[:12])
    related, checked = RELATED.search(head), CHECKED.search(head)
    if not related or not checked:
        missing = [n for n, m in (("관련 코드", related), ("마지막 확인 YYYY-MM-DD", checked)) if not m]
        return [f"{doc}: 제목 아래에 '{' / '.join(missing)}' 줄이 없음"], []
    value = related.group(1).strip()
    if value.startswith("없음"):
        return [], []
    paths = re.findall(r"`([^`]+)`", value)
    if not paths:
        return [f"{doc}: '관련 코드'에 백틱으로 감싼 경로가 없음"], []
    gone = [p for p in paths if not exists(root, p.rstrip("/"))]
    if gone:
        return [f"{doc}: 관련 코드 경로가 없음: {', '.join(gone)}"], []
    last = git_last_change(root, paths)
    if last and last > checked.group(1):
        return [], [f"{doc}: 관련 코드가 마지막 확인({checked.group(1)}) 뒤 {last}에 바뀜. 코드와 대조하고 날짜를 갱신할 것"]
    return [], []


def main(argv):
    show_all = "--all" in argv
    args = [a for a in argv if not a.startswith("--")]
    root = os.path.realpath(args[0] if args else
                            subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True,
                                           text=True).stdout.strip() or ".")
    fails, warns, foreign = [], [], {}
    docs = doc_files(root)
    if not docs:  # wrong or deleted folder: nothing checked must not read as PASS
        print(f"FAIL: 검사할 문서가 없음 ({root})")
        print("DOC-CHECK: FAIL (fails 1, warnings 0)")
        return 1
    for doc in docs:
        if not owned(doc) and not show_all:
            continue
        bad = broken_links(root, doc)
        if bad and owned(doc):
            fails += [f"{doc}: 깨진 링크 {b}" for b in bad]
        elif bad:
            foreign[doc] = bad
        if owned(doc, FRESHNESS) and doc not in FRESHNESS_EXEMPT:
            f, w = freshness(root, doc)
            fails += f
            warns += w
    if foreign:
        total = sum(len(v) for v in foreign.values())
        where = ", ".join(f"{d}({len(v)})" for d, v in foreign.items())
        warns.append(f"다른 담당 문서의 깨진 링크 {total}건: {where}")
        warns += [f"  {d}: {b}" for d, v in foreign.items() for b in v]
    for w in warns:
        print(f"WARN: {w}")
    for f in fails:
        print(f"FAIL: {f}")
    print(f"DOC-CHECK: {'FAIL' if fails else 'PASS'} (fails {len(fails)}, warnings {len(warns)})")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
