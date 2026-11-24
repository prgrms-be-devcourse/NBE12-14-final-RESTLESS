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
- 드롭 모듈(FULL_MODULES)이 바뀌어도 전체. 주문과 결제는 SHARED라서 이미 전체다. 이 세 곳은 재고, 결제 상태,
  중복 요청 방지를 다뤄 높음 위험도(tools/verification/checklist.md 5절)가 되는 일이 잦고, merge-gate가
  전체 실행의 test-scope 결과를 검증자의 CI 증거로 쓴다. 선택 실행이 놓친 회귀를 머지 전에 잡는 효과도 있다.
- backend/src/main/java/com/restless/<모듈>/ 과 backend/src/test/java/com/restless/<모듈>/ 밖의 backend 파일이
  바뀌면 전체. Flyway migration, Spring 설정, 공용 테스트 fixture(루트 패키지, test resources),
  Gradle 설정, Dockerfile이 여기 든다.
- .github/ 나 tools/ci/ 가 바뀌면 전체.
- 도메인 모듈이 바뀌면 그 도메인의 테스트를 돌린다. 함께 돌리는 것:
  - 그 도메인을 운영 코드에서 쓰는 도메인 (거슬러 올라가며). 공유 모듈에 닿으면 전체.
  - 테스트 코드에서 그 도메인을 쓰는 모듈의 테스트. 그 도메인 클래스의 빈 이름 문자열을 쓰는 테스트도 포함한다.
  - 그 도메인이 구현하거나 상속하는 타입을 가진 모듈의 테스트. 예: 드롭은 member의 WithdrawalGuard,
    order의 OrderPayableResolver, payment의 PaymentResultHandler를 구현하므로 회원, 주문, 결제 테스트가
    드롭 코드를 실행한다. 와일드카드 import도 찾는다.
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
# 선택 실행 대신 항상 전체를 돌릴 도메인. SHARED가 아니어도 여기 들면 전체다.
FULL_MODULES = {"drop"}
FULL_PREFIXES = (".github/", "tools/ci/")
ALWAYS = ["com.restless.architecture.*", "com.restless.RestlessApplicationTests"]
REF = re.compile(r"com\.restless\.([a-z]+)\.")
IMPORT = re.compile(r"import com\.restless\.([a-z]+)\.[\w.]*\.(\w+);")
WILDCARD = re.compile(r"import com\.restless\.([a-z]+)\.[\w.]*\*;")
DECLARES = re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)")
# 빈 이름으로 볼 문자열. 두 단어 이상인 camelCase만 본다. "page" 같은 흔한 문자열과 겹치지 않게 한다.
STRING = re.compile(r'"([a-z]+[A-Z]\w*)"')
SUPERTYPES = re.compile(r"\b(?:implements|extends)\s+([\w\s,<>.?]+?)\s*(?:\{|implements\b|permits\b)")


def java_files(repo, side_root):
    base = os.path.join(repo, side_root)
    for m in sorted(os.listdir(base)):
        path = os.path.join(base, m)
        if not os.path.isdir(path):
            continue
        texts = []
        for dirpath, _, names in os.walk(path):
            for name in names:
                if name.endswith(".java"):
                    with open(os.path.join(dirpath, name), encoding="utf-8") as f:
                        texts.append(f.read())
        yield m, texts


def scan(repo, side_root, declared=None, bean_names=None):
    """모듈별로 참조하는 다른 모듈과, 구현하거나 상속하는 다른 모듈의 타입이 속한 모듈.
    import가 와일드카드여도 declared(타입 이름 -> 선언한 모듈)로 찾는다."""
    refs, impls = {}, {}
    for m, texts in java_files(repo, side_root):
        used, implemented = set(), set()
        for text in texts:
            used.update(REF.findall(text))
            # 테스트가 다른 모듈의 빈을 이름으로 덮어쓰는 경우. 예: @Bean("dropPaymentResultHandler")
            if bean_names:
                for literal in STRING.findall(text):
                    used |= bean_names.get(literal, set())
            explicit = {t: mod for mod, t in IMPORT.findall(text)}
            wildcard = set(WILDCARD.findall(text))
            for clause in SUPERTYPES.findall(text):
                for t in re.findall(r"\w+", clause):
                    if t in explicit:
                        implemented.add(explicit[t])
                    elif declared:
                        implemented |= declared.get(t, set()) & wildcard
        used.discard(m)
        implemented.discard(m)
        refs[m], impls[m] = used, implemented
    return refs, impls


def declared_types(repo, side_root):
    declared = {}
    for m, texts in java_files(repo, side_root):
        for text in texts:
            for t in DECLARES.findall(text):
                declared.setdefault(t, set()).add(m)
    return declared


def module_of(path, root):
    rest = path[len(root):]
    return rest.split("/", 1)[0] if "/" in rest else None


def select(files, repo):
    """Returns (scope, test patterns, reason)."""
    declared = declared_types(repo, MAIN)
    bean_names = {t[0].lower() + t[1:]: mods for t, mods in declared.items()}
    main_refs, main_impls = scan(repo, MAIN, declared)
    test_refs, _ = scan(repo, TEST, bean_names=bean_names)
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
        if m in FULL_MODULES:
            return "full", [], f"드롭, 주문, 결제는 항상 전체 {m}: {f}"
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
