#!/usr/bin/env python3
"""바뀐 파일로 돌릴 백엔드 테스트를 고른다. CI(.github/workflows/ci.yml)의 PR과 dev push가 같이 쓴다.

  python3 tools/ci/select-backend-tests.py [--repo DIR] < changed-files.txt

입력은 저장소 루트 기준 경로 한 줄에 하나다. rename은 이전 경로와 새 경로를 모두 넣는다.
출력은 세 줄이다.
  1. none, full, partial 중 하나
  2. partial이면 Gradle --tests 패턴들 (공백 구분). 아니면 빈 줄
  3. 사람이 읽을 이유

규칙. 분류할 수 없으면 전체를 돌린다.
- 공유 모듈(SHARED)의 운영 코드나 테스트가 바뀌면 전체.
- backend/src/main/java/com/restless/<모듈>/ 과 backend/src/test/java/com/restless/<모듈>/ 밖의 backend 파일이
  바뀌면 전체. Flyway migration, Spring 설정, 공용 테스트 fixture(루트 패키지, test resources),
  Gradle 설정, Dockerfile이 여기 든다.
- .github/ 나 tools/ci/ 가 바뀌면 전체.
- 도메인 모듈이 바뀌면 그 도메인의 테스트를 돌린다. 함께 돌리는 것:
  - 그 도메인을 운영 코드에서 쓰는 도메인 (거슬러 올라가며). 공유 모듈에 닿으면 전체.
  - 테스트 코드에서 그 도메인을 쓰는 모듈의 테스트.
  - 그 도메인이 구현하는 인터페이스를 가진 모듈의 테스트. 예: 드롭은 member의 WithdrawalGuard와
    order의 OrderPayableResolver를 구현하므로 회원과 주문 테스트가 드롭 코드를 실행한다.
  - ArchUnit 모듈 경계 테스트와 애플리케이션 기동 smoke test (RestlessApplicationTests).
- 바뀐 도메인에 테스트가 하나도 없으면 전체.
- 백엔드와 CI 설정 밖의 파일만 바뀌면 none. 문서, 프론트엔드, perf 스크립트가 여기 든다.
"""
import argparse
import os
import re
import sys

PKG = "com/restless"
MAIN = f"backend/src/main/java/{PKG}/"
TEST = f"backend/src/test/java/{PKG}/"
# 여러 모듈이 쓰거나 공용 계약을 가진 모듈. 바뀌면 전체를 돌린다.
SHARED = {"common", "auth", "member", "order", "payment", "concert"}
FULL_PREFIXES = (".github/", "tools/ci/")
ALWAYS = ["com.restless.architecture.*", "com.restless.RestlessApplicationTests"]
REF = re.compile(r"com\.restless\.([a-z]+)\.")
IMPORT = re.compile(r"import com\.restless\.([a-z]+)\.[\w.]*\.(\w+);")
IMPLEMENTS = re.compile(r"\bimplements\s+([\w\s,<>.]+?)\s*\{")


def scan(repo, side_root):
    """모듈별로 참조하는 다른 모듈과, 구현하는 다른 모듈의 타입이 속한 모듈."""
    base = os.path.join(repo, side_root)
    refs, impls = {}, {}
    for m in sorted(os.listdir(base)):
        path = os.path.join(base, m)
        if not os.path.isdir(path):
            continue
        used, implemented = set(), set()
        for dirpath, _, names in os.walk(path):
            for name in names:
                if not name.endswith(".java"):
                    continue
                with open(os.path.join(dirpath, name), encoding="utf-8") as f:
                    text = f.read()
                used.update(REF.findall(text))
                imported = {t: mod for mod, t in IMPORT.findall(text)}
                for clause in IMPLEMENTS.findall(text):
                    for t in re.findall(r"\w+", clause):
                        if t in imported:
                            implemented.add(imported[t])
        used.discard(m)
        implemented.discard(m)
        refs[m], impls[m] = used, implemented
    return refs, impls


def module_of(path, root):
    rest = path[len(root):]
    return rest.split("/", 1)[0] if "/" in rest else None


def select(files, repo):
    """Returns (scope, test patterns, reason)."""
    main_refs, main_impls = scan(repo, MAIN)
    test_refs, _ = scan(repo, TEST)
    changed = set()
    for f in files:
        if f.startswith(FULL_PREFIXES):
            return "full", [], f"CI 설정 변경: {f}"
        if not f.startswith("backend/"):
            continue
        root = MAIN if f.startswith(MAIN) else TEST if f.startswith(TEST) else None
        if root is None:
            return "full", [], f"모듈 밖 백엔드 파일: {f}"
        m = module_of(f, root)
        if m is None:
            return "full", [], f"루트 패키지 파일: {f}"
        if m in SHARED:
            return "full", [], f"공유 모듈 {m}: {f}"
        changed.add(m)
    if not changed:
        return "none", [], "백엔드 변경 없음"

    for m in sorted(changed):
        if m not in test_refs and m != "architecture":
            return "full", [], f"테스트가 없는 모듈 {m}"

    affected = set(changed)
    stack = list(affected)
    while stack:
        x = stack.pop()
        for y, used in main_refs.items():
            if x in used and y not in affected:
                if y in SHARED:
                    return "full", [], f"공유 모듈 {y}가 {x}를 씀"
                affected.add(y)
                stack.append(y)

    run = set(affected)
    for x in affected:
        run |= main_impls.get(x, set())
    run |= {y for y, used in test_refs.items() if used & affected}
    run = sorted(m for m in run if m in test_refs)
    if not run:
        return "full", [], "고른 테스트가 0개"
    patterns = [f"com.restless.{m}.*" for m in run if m != "architecture"] + ALWAYS
    return "partial", patterns, f"바뀐 도메인 {' '.join(sorted(changed))}, 테스트 모듈 {' '.join(run)}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=".")
    a = ap.parse_args()
    files = [line.strip() for line in sys.stdin if line.strip()]
    scope, patterns, reason = select(files, a.repo)
    print(scope)
    print(" ".join(patterns))
    print(reason)


if __name__ == "__main__":
    main()
