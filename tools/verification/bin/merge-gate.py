#!/usr/bin/env python3
"""머지 직전 검사. PR head SHA와 독립 검증 판정 기록을 대조한다.

  python3 tools/verification/bin/merge-gate.py <PR 번호> [--verify-dir DIR]

변경 종류별 요구와 CI 증거 조건의 정본은 tools/verification/checklist.md 3절과 5절이다.
아래 경로 목록은 그 표와 같게 유지한다 (test_tools.py가 대조한다).
종료 코드: 0 머지 가능, 1 막힘, 2 실행 오류.
"""
import argparse
import fnmatch
import json
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
# 문서로 보는 범위: docs/ 아래와 저장소 루트의 .md. 그 밖의 .md(SKILL.md, 모듈 AGENTS.md 등)는 코드다.
AGENT_FILES = {"AGENTS.md", "CLAUDE.md", "SKILL.md"}
POLICY = ["docs/domains/*", "docs/contracts/*", "docs/decisions/*", "docs/product/*",
          "docs/testing/*", "docs/operations/failure-handling.md"]
CODE_DOC = ["docs/operations/*", "docs/architecture/*"]
BUILD_CONFIG = [".github/workflows/*", "backend/build.gradle", "backend/settings.gradle",
                "backend/gradle/*", "backend/gradlew", "frontend/package.json"]
MUTATION_REQUIRED = ["backend/src/main/java/com/restless/payment/application/PaymentReconciler.java",
                     "backend/src/main/java/com/restless/payment/application/PaymentTransactions.java",
                     "backend/src/main/java/com/restless/payment/domain/*",
                     "backend/src/main/java/com/restless/drop/*Inventory*",
                     "backend/src/main/java/com/restless/drop/*Reservation*"]
RECORD = re.compile(r"^\s*(?:[-*|]\s*)?판정 기록:\s*(PASS|FAIL|INCOMPLETE)\s+([0-9a-f]{40})(?![0-9a-f])")
NO_REVIEW = re.compile(r"독립 검증 없음:\s*\S")


def gh(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"gh {' '.join(args)} 실패: {r.stderr.strip()}")
    return r.stdout


def match(path, patterns):
    return any(fnmatch.fnmatch(path, p) for p in patterns)


def is_doc(path):
    if os.path.basename(path) in AGENT_FILES or not path.endswith(".md"):
        return False
    return path.startswith("docs/") or "/" not in path


def classify(files):
    if not files or not all(is_doc(f) for f in files):
        return "code"
    if any(match(f, POLICY) for f in files):
        return "policy-doc"
    if any(match(f, CODE_DOC) for f in files):
        return "code-doc"
    return "format-doc"


def last_record(text):
    """Only the last non-empty line counts, so an example or a quoted line earlier cannot pass."""
    lines = [l for l in text.splitlines() if l.strip()]
    m = RECORD.match(lines[-1]) if lines else None
    return m.groups() if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pr", type=int)
    ap.add_argument("--verify-dir", default=os.path.join(MAIN, ".ai-output/verification"))
    a = ap.parse_args()

    pr = json.loads(gh("pr", "view", str(a.pr), "--json",
                       "headRefOid,files,closingIssuesReferences,statusCheckRollup,body,title"))
    head = pr["headRefOid"]
    files = [f["path"] for f in pr["files"]]
    issues = [i["number"] for i in pr["closingIssuesReferences"]]
    kind = classify(files)
    blocks, notes, reviews = [], [], []

    print(f"PR #{a.pr} {pr['title']}")
    print(f"head SHA: {head}")
    print(f"변경 종류: {kind} (파일 {len(files)}개)")
    print(f"닫는 이슈: {', '.join(map(str, issues)) or '없음'}")

    if kind == "format-doc":
        if NO_REVIEW.search(pr["body"]):
            notes.append("서식 변경. PR 본문에 독립 검증 없음과 이유가 있음")
        else:
            blocks.append("서식 변경이지만 PR 본문에 `독립 검증 없음: <이유>`가 없음")
    else:
        passed = False
        for n in issues:
            path = os.path.join(a.verify_dir, str(n), "review.md")
            if not os.path.exists(path):
                blocks.append(f"#{n}: 검증 기록 없음 ({path})")
                continue
            text = Path(path).read_text(encoding="utf-8")
            reviews.append(text)
            rec = last_record(text)
            if rec is None:
                old = " review.md에 head SHA는 있으나" if head[:7] in text else ""
                blocks.append(f"#{n}:{old} 마지막 줄이 판정 기록이 아님")
                continue
            verdict, sha = rec
            print(f"#{n} 마지막 판정 기록: {verdict} {sha[:12]}")
            if sha != head:
                blocks.append(f"#{n}: 판정 기록 SHA {sha[:12]}가 head {head[:12]}와 다름. 재검증하거나 트리를 비교할 것")
            elif verdict != "PASS":
                blocks.append(f"#{n}: head SHA의 판정이 {verdict}")
            else:
                passed = True
        if not issues:
            blocks.append("닫는 이슈가 없어 검증 기록을 찾을 수 없음")
        elif not passed and not blocks:
            blocks.append("head SHA에 대한 독립 PASS 기록 없음")
        if kind == "policy-doc":
            notes.append("정책, 계약, 검증 절차, 불변식 문서 변경. 독립 PASS에 더해 사람 확인 필요")

    hot = [f for f in files if match(f, MUTATION_REQUIRED)]
    if hot and not any("변이" in t for t in reviews):
        blocks.append(f"변이 시험 필수 경로 변경 {hot}. review.md에 변이 결과가 없음")

    cfg = [f for f in files if match(f, BUILD_CONFIG)]
    backend = [c for c in pr["statusCheckRollup"] if c.get("name") == "backend"]
    ci_ok = bool(backend) and all(c.get("conclusion") == "SUCCESS" for c in backend)
    if cfg:
        print(f"CI 증거: 쓸 수 없음. 빌드나 CI 설정 변경 {cfg}. 검증자가 직접 build할 것")
    elif not backend:
        print("CI 증거: 쓸 수 없음. backend 검사 결과 없음")
    elif ci_ok:
        print("CI 증거: 조건 1, 2 충족. 조건 3(artifact 테스트 수)은 review.md에서 확인할 것")
    else:
        print(f"CI 증거: 쓸 수 없음. backend {backend[-1].get('conclusion') or '진행 중'}")
    if kind != "format-doc" and backend and not ci_ok:
        blocks.append("backend CI가 성공하지 않음")

    for n in notes:
        print(f"참고: {n}")
    for b in blocks:
        print(f"막힘: {b}")
    print("RESULT:", "BLOCK" if blocks else "PASS")
    return 1 if blocks else 0


if __name__ == "__main__":
    sys.exit(main())
