#!/usr/bin/env python3
"""머지 직전 검사. PR head SHA와 독립 검증 판정 기록을 대조한다.

  python3 tools/verification/bin/merge-gate.py <PR 번호> [--verify-dir DIR]

변경 종류(파일 경로로 분류)에 따라 요구가 다르다.
  code        : head SHA에 대한 `판정 기록: PASS <sha>` 필요
  policy-doc  : code와 같음 + 사람 확인 필요 표시
  code-doc    : head SHA에 대한 판정 기록 필요 (가벼운 검증, build 불필요)
  format-doc  : 검증 불필요. PR 본문에 "독립 검증 없음" 문구 필요

CI 결과를 전체 build 증거로 쓸 수 있는지도 함께 판정한다.
  같은 head SHA의 backend 검사 성공 + CI나 빌드 설정 파일 변경 없음.

종료 코드: 0 머지 가능, 1 막힘, 2 실행 오류.
판정 기록 형식(review.md, 재검증마다 한 줄 추가, 마지막 줄이 유효):
  판정 기록: PASS 66ca50c44dacf9bb1f273b0c6a1e920278bc1201
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
POLICY = ["docs/domains/*/policy.md", "docs/domains/*/invariants.md", "docs/domains/*/*.md",
          "docs/contracts/*", "docs/decisions/*", "docs/product/open-questions.md"]
CODE_DOC = ["docs/operations/*", "docs/architecture/*", "docs/testing/*"]
BUILD_CONFIG = [".github/workflows/*", "backend/build.gradle", "backend/settings.gradle",
                "backend/gradle/*", "backend/gradlew", "frontend/package.json"]
RECORD = re.compile(r"^\s*(?:[-*|]\s*)?판정 기록:\s*(PASS|FAIL|INCOMPLETE)\s+([0-9a-f]{40})", re.M)


def gh(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"gh {' '.join(args)} 실패: {r.stderr.strip()}")
    return r.stdout


def match(path, patterns):
    return any(fnmatch.fnmatch(path, p) for p in patterns)


def classify(files):
    if any(not (f.startswith("docs/") or f.endswith(".md")) for f in files):
        return "code"
    if any(match(f, POLICY) for f in files):
        return "policy-doc"
    if any(match(f, CODE_DOC) for f in files):
        return "code-doc"
    return "format-doc"


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
    blocks, notes = [], []

    print(f"PR #{a.pr} {pr['title']}")
    print(f"head SHA: {head}")
    print(f"변경 종류: {kind} (파일 {len(files)}개)")
    print(f"닫는 이슈: {', '.join(map(str, issues)) or '없음'}")

    # 판정 기록
    if kind == "format-doc":
        if "독립 검증 없음" in pr["body"]:
            notes.append("서식 변경. PR 본문에 독립 검증 없음 명시됨")
        else:
            blocks.append('서식 변경이지만 PR 본문에 "독립 검증 없음" 문구가 없음')
    else:
        found = None
        for n in issues:
            path = os.path.join(a.verify_dir, str(n), "review.md")
            if not os.path.exists(path):
                notes.append(f"#{n}: 검증 기록 없음 ({path})")
                continue
            text = Path(path).read_text(encoding="utf-8")
            recs = RECORD.findall(text)
            if recs:
                verdict, sha = recs[-1]
                print(f"#{n} 마지막 판정 기록: {verdict} {sha[:12]}")
                if sha == head and verdict == "PASS":
                    found = (n, verdict)
                elif sha != head:
                    blocks.append(f"#{n}: 판정 기록 SHA {sha[:12]}가 head {head[:12]}와 다름. 재검증하거나 트리를 비교할 것")
                else:
                    blocks.append(f"#{n}: head SHA의 판정이 {verdict}")
            elif head in text or head[:7] in text:
                notes.append(f"#{n}: 옛 형식. review.md에 head SHA는 있으나 판정 기록 줄이 없어 수동 확인 필요")
                blocks.append(f"#{n}: 판정 기록 줄 없음")
            else:
                notes.append(f"#{n}: review.md에 head SHA가 없음")
        if not found and not any(b.startswith("#") for b in blocks):
            blocks.append("head SHA에 대한 독립 PASS 기록 없음")
        if kind == "policy-doc":
            notes.append("정책, 계약, ADR 변경. 독립 PASS에 더해 사람 확인 필요")

    # CI 결과를 build 증거로 쓸 수 있는지
    cfg = [f for f in files if match(f, BUILD_CONFIG)]
    backend = [c for c in pr["statusCheckRollup"] if c.get("name") == "backend"]
    ci_ok = bool(backend) and all(c.get("conclusion") == "SUCCESS" for c in backend)
    if cfg:
        print(f"CI 증거 사용: 불가. 빌드나 CI 설정 변경 {cfg}. 검증자가 직접 build할 것")
    elif not backend:
        print("CI 증거 사용: 불가. backend 검사 결과 없음")
    else:
        print(f"CI 증거 사용: {'가능' if ci_ok else '불가'} (backend {backend[-1].get('conclusion') or '진행 중'})")
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
