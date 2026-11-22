#!/usr/bin/env python3
"""Codex 지시서 린트. 클립보드에 넣기 전에 돌린다.

  python3 tools/verification/bin/spec-lint.py <지시서.md> [...]

오류(종료 코드 1):
  E1 이슈 번호가 제목이나 앞부분에 없다
  E2 작업 선택을 맡기는 문구 ("다음 이슈 진행해", "알아서 골라" 등)
  E3 작업 폴더가 worktree인데 git에 없는 도구(tools/harness, .ai-output/harness) 실행을 요구한다
  E4 작업 폴더가 worktree인데 .ai-output 경로가 상대 경로다 (worktree 삭제 때 함께 사라진다)
경고:
  W1 기준 SHA가 없다
  W2 "하지 않을 것" 절이 없다
"""
import os
import re
import subprocess
import sys
from pathlib import Path

def main_checkout():
    """Main folder, also from a worktree. Personal files (.ai-output, AGENTS.md) live only there."""
    r = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("git 저장소 안에서 실행할 것")
    return os.path.dirname(r.stdout.strip().rstrip("/"))


MAIN = main_checkout()
UNTRACKED_TOOLS = re.compile(r"tools/harness|\.ai-output/harness/|harness-check\.sh")
NEGATION = re.compile(r"돌리지 않|실행하지 않|돌릴 수 없|실행되지 않|실행할 수 없|못 돌|못했다|수정하지 않|읽지도|바꾸지 않|고치지 않|금지")
AMBIGUOUS = re.compile(r"다음 이슈(를|도)?\s*(진행|해|하|작업|골라|시작)|알아서 (골라|정해|선택)|아무 이슈|적당한 이슈|남은 이슈 중")
# A comma before another code span joins a list that shares one verb, so it does not end the clause.
CLAUSE_END = re.compile(r"[.;]\s|,(?!\s*`)|$")
HEX = r"(?<![0-9a-f])(?=[0-9a-f]*\d)[0-9a-f]{7,40}(?![0-9a-f])"
REL_OUTPUT = re.compile(r"(?<![\w/])`?(\.ai-output/[^\s`)]+)")


def negated_after(line, end):
    """A Korean clause puts its verb after the object, so a negation counts only between the
    mention and the end of its clause. A negation earlier in the line belongs to another clause."""
    clause = line[end:end + CLAUSE_END.search(line[end:]).start()]
    return bool(NEGATION.search(clause))


def lint(path):
    text = Path(path).read_text(encoding="utf-8")
    lines = text.splitlines()
    errors, warns = [], []

    head = "\n".join(lines[:5])
    if not re.search(r"#\d+|이슈 \d+", head):
        errors.append("E1 앞 5줄에 이슈 번호가 없다")

    for i, l in enumerate(lines, 1):
        if AMBIGUOUS.search(l):
            errors.append(f"E2 {i}행: 작업 선택을 맡기는 문구: {l.strip()[:60]}")

    m = re.search(r"(?:작업 폴더|저장소):\s*`?([~/][^\s`(]+)", text)
    folder = os.path.expanduser(m.group(1)).rstrip("/") if m else None
    worktree = folder is not None and folder != MAIN
    if worktree:
        for i, l in enumerate(lines, 1):
            if any(not negated_after(l, t.end()) for t in UNTRACKED_TOOLS.finditer(l)):
                errors.append(f"E3 {i}행: worktree({folder})에 없는 도구 요구: {l.strip()[:70]}")
            for out in REL_OUTPUT.finditer(l):
                if not negated_after(l, out.end()):
                    errors.append(f"E4 {i}행: 상대 경로 {out.group(1)}. {MAIN}/{out.group(1)} 로 쓸 것")
    if folder is None:
        warns.append("작업 폴더 줄이 없어 worktree 검사를 건너뜀")

    if not re.search(r"기준[^\n]{0,80}" + HEX + "|" + HEX + r"[^\n]{0,10}기준", text):
        warns.append("W1 기준 SHA가 없다")
    if "하지 않을 것" not in text and "범위" not in text:
        warns.append("W2 하지 않을 것 또는 범위 절이 없다")

    print(f"== {os.path.basename(path)} (작업 폴더: {folder or '?'}{', worktree' if worktree else ''})")
    for e in errors:
        print("  오류", e)
    for w in warns:
        print("  경고", w)
    print("  RESULT:", "FAIL" if errors else "PASS")
    return not errors


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    ok = all([lint(p) for p in sys.argv[1:]])
    sys.exit(0 if ok else 1)
