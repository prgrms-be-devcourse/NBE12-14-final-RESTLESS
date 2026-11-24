#!/usr/bin/env python3
"""머지 직전 검사. PR head SHA와 독립 검증 판정 기록을 대조한다.

  python3 tools/verification/bin/merge-gate.py <PR 번호> [--verify-dir DIR]

  python3 tools/verification/bin/merge-gate.py <PR 번호> --classify-only   # 위험도와 근거만 출력

위험도(높음, 중간, 낮음)는 diff로 자동 분류한다. 높음만 새 컨텍스트 독립 검증 PASS를 요구한다.
단계 기준, 변경 종류별 요구, CI 증거 조건의 정본은 tools/verification/checklist.md 3절과 5절이다.
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

# 위험도. 정본은 checklist.md 5절. 애매하면 높은 쪽이다.
HIGH, MID, LOW = "높음", "중간", "낮음"
RANK = {LOW: 0, MID: 1, HIGH: 2}
# AGENTS.md Never break 표의 코드가 사는 모듈. application과 domain 패키지는 로직만 바뀌어도 높음이다.
# api, infrastructure, common, config는 잠금 줄과 쓰기 쿼리 규칙으로 판단한다.
NEVER_BREAK_MODULES = ["drop", "order", "payment", "fanmeeting", "ticketing", "auction"]
MAIN_PKG = "backend/src/main/java/com/restless"
CORE_CODE = [f"{MAIN_PKG}/{m}/{pkg}/*" for m in NEVER_BREAK_MODULES for pkg in ("application", "domain")] \
    + [f"{MAIN_PKG}/payment/*/pg/*"]
HIGH_PATHS = [
    ("검증 절차 문서", ["docs/testing/independent-verification.md", "docs/testing/strategy.md"]),
    ("Flyway 마이그레이션", ["backend/src/main/resources/db/migration/*"]),
    ("공개 API 계약", ["docs/contracts/*"]),
    ("채점 테스트", ["backend/src/test/java/com/restless/drop/acceptance/*",
                 "backend/src/test/java/com/restless/architecture/*",
                 "backend/src/test/resources/harness/*", "tools/harness/*"]),
    ("불변식 핵심 코드", MUTATION_REQUIRED),
    ("Never break 모듈의 application과 domain 코드", CORE_CODE),
    ("도메인 문서와 장애 처리 문서", ["docs/domains/*", "docs/operations/failure-handling.md"]),
    ("검증과 CI 도구 자체", ["tools/verification/*", "tools/ci/*", ".github/*"]),
    ("빌드 설정", [p for p in BUILD_CONFIG if not p.startswith(".github/")]),
]
# 운영 코드에 새로 생긴 줄. 잠금과 트랜잭션 경계, 쓰기 쿼리.
LOCK_LINE = re.compile(r"Transactional|TransactionTemplate|Propagation\.|Isolation\.|FOR\s+UPDATE|SKIP\s+LOCKED"
                       r"|@Lock\b|PESSIMISTIC_|LockModeType|\bsynchronized\b|ReentrantLock|advisory_lock|GET_LOCK"
                       r"|@Version\b|@Modifying|ddl-auto|flyway|out-of-order|validate-on-migrate|baseline-on-migrate|clean-disabled|isolation|minimum-idle|max-lifetime|lock[-_]timeout|open-in-view|maximum-pool-size|connection-timeout|getLock\(|\.lock\(|tryLock|\bRLock\b|setIfAbsent|opsFor\w+|RedisTemplate"
                       r"|\bUPDATE\s+\w+\s+SET\b|\bINSERT\s+INTO\b|\bDELETE\s+FROM\b", re.I)
# 테스트에서 사라지거나 바뀐 줄이 이런 줄이면 단언을 약하게 만들 수 있다.
ASSERT_REMOVED = re.compile(r"\bassert\w*|\.(?:is|has|contains|matches|does|all|none|any|satisf|extracting|usingRecursive|as)\w*\("
                            r"|\bverify\w*\(|\bexpect\w*|\bfail\(|@Test\b|@ParameterizedTest|@RepeatedTest|@Nested\b|\$\.|\.andExpect"
                            r"|await\w*\(|\bLatch|invokeAll|Timeout|\.join\(|\.get\(|\.should\(|\bthen\(|\bwill\w*\(|\bgiven\(", re.I)
SKIP_ADDED = re.compile(r"@Disabled|@Ignore\b|@EnabledIf|@DisabledIf|@EnabledOn|@DisabledOn|@EnabledFor|@DisabledFor|@Tag\(\"skip"
                        r"|\bAssumptions?\.(?:assume|abort)|\bassumingThat\(|TestAbortedException|\bAssume\.assume|\bassume\w*\(|\.skip\(|\btest\.only\b|\b(?:it|describe)\.only\b"
                        r"|\bx(?:it|describe)\(|\b(?:it|describe|test)\.skip\b")
# 기존 테스트에 더해진 줄이 실행을 조건부로 만들면 최소 중간이다.
COND_ADDED = re.compile(r"\bif\s*\(|\bwhile\s*\(|^\s*return\s*;|\bcontinue\s*;|\?\s*[^:]+:|\btry\s*\{|\bcatch\s*\(")
# 동시성 테스트와 공유 테스트 픽스처를 기존 파일에서 바꾸면 높음이다.
CONCURRENCY_LINE = re.compile(r"Thread|Executor|Latch|Barrier|Semaphore|parallel|concurren|\bthreads?\b|\bworkers?\b|CompletableFuture|invokeAll|Phaser", re.I)
FIXTURE_PATH = re.compile(r"(Support|Fixture|Testcontainers|TestConfig|Configuration|Abstract|Helper|Factory|Builder|TestUtil|Concurren|Race)\w*\.\w+$"
                          r"|/(support|fixture|fixtures|testsupport|testutil)/|^backend/src/test/java/com/restless/[^/]+\.java$", re.I)

FOLLOWUP_MAX_LINES = 30
RISK_LABEL = {"risk:high": HIGH, "risk:medium": MID}
RISK_BODY = re.compile(r"^\s*위험도:\s*(높음|중간)\s*$", re.M)
RISK_DOWN = re.compile(r"^\s*위험도:\s*낮음\s*$", re.M)
RECORD = re.compile(r"^\s*(?:[-*|]\s*)?판정 기록:\s*(PASS|FAIL|INCOMPLETE)\s+([0-9a-f]{40})(?![0-9a-f])")


SCOPE_NOTICE = re.compile(r"^Backend test scope: (full|partial|none)\b(.*)$")


def scope_from_annotations(messages):
    """CI의 Select tests 단계가 남긴 notice에서 실제로 돌린 범위를 읽는다. 없으면 None."""
    for m in messages:
        hit = SCOPE_NOTICE.match(m.strip())
        if hit:
            return hit.group(1), hit.group(2).strip()
    return None


def ci_test_scope(check):
    """backend 검사가 실제로 돌린 범위. 다시 계산하지 않고 그 실행의 annotation을 읽는다.
    읽을 수 없으면 None이고, 그때 CI는 증거로 쓰지 않는다."""
    job = re.search(r"/job/(\d+)", check.get("detailsUrl") or "")
    if not job:
        return None
    r = subprocess.run(["gh", "api", f"repos/{{owner}}/{{repo}}/check-runs/{job.group(1)}/annotations",
                        "--jq", ".[].message"], capture_output=True, text=True)
    if r.returncode != 0:
        return None
    return scope_from_annotations(r.stdout.splitlines())


def is_test_path(path):
    """테스트 코드 파일. src/test 아래 코드만 센다. 그 아래 설정이나 리소스(yml, sql)는 중간이고, src/main 아래는 이름이 Test로 끝나도 운영 코드다."""
    return path.endswith((".java", ".kt", ".js", ".ts")) and "/src/test/" in path


def unquote(path):
    """git은 비 ASCII나 특수 문자 경로를 따옴표와 8진수 이스케이프로 낸다."""
    path = path.strip()
    if len(path) >= 2 and path[0] == '"' and path[-1] == '"':
        try:
            return path[1:-1].encode("latin1").decode("unicode_escape").encode("latin1").decode("utf-8")
        except (UnicodeError, ValueError):
            return path[1:-1]
    return path


def same_path(header):
    """--- 줄이 없는 블록(바이너리, 모드만 바뀜)의 경로. 이름을 바꾸지 않았으면 a/P b/P라서 쪼갤 수 있다.
    따옴표 경로는 못 쪼개고 None이다."""
    rest = header[len("diff --git "):]
    n = len(rest)
    if rest.startswith('"') or (n - 5) % 2:
        return None
    half = (n - 5) // 2
    first, second = rest[2:2 + half], rest[5 + half:]
    return first if rest[:2] == "a/" and rest[2 + half:5 + half] == " b/" and first == second else None


def parse_diff(text):
    """gh pr diff 출력을 {경로: {added, removed, status, old}}로. status는 added, removed, modified.
    경로는 diff --git 줄이 아니라 --- 와 +++ 와 rename 줄에서 읽는다. 첫 @@ 뒤의 --- 로 시작하는 줄은 내용이다.
    내용이 없는 변경(이름만 바꿈, 바이너리)도 항목으로 남는다."""
    out = {}
    for block in re.split(r"^(?=diff --git )", text, flags=re.M):
        if not block.startswith("diff --git "):
            continue
        e = {"added": [], "removed": [], "status": "modified", "old": None, "new": None}
        in_body = False
        for line in block.splitlines()[1:]:
            if in_body:
                if line.startswith("+"):
                    e["added"].append(line[1:])
                elif line.startswith("-"):
                    e["removed"].append(line[1:])
            elif line.startswith("@@"):
                in_body = True
            elif line.startswith("new file mode"):
                e["status"] = "added"
            elif line.startswith("deleted file mode"):
                e["status"] = "removed"
            elif line.startswith("rename from "):
                e["old"] = unquote(line[12:])
            elif line.startswith("rename to "):
                e["new"] = unquote(line[10:])
            elif line.startswith("--- ") and line[4:].strip() != "/dev/null":
                e["old"] = unquote(line[4:])[2:]
            elif line.startswith("+++ ") and line[4:].strip() != "/dev/null":
                e["new"] = unquote(line[4:])[2:]
            elif line.startswith("Binary files"):
                e["binary"] = True
        key = e["new"] or e["old"] or same_path(block.splitlines()[0])
        if key:
            out[key] = e
        else:
            out["?" + block.splitlines()[0][:80]] = {**e, "unreadable": True}
    return out


def parse_compare(files):
    """GitHub compare API의 files 배열을 parse_diff와 같은 모양으로. patch가 없으면(큰 파일, 바이너리) 읽을 수 없다."""
    out = {}
    for f in files:
        patch = f.get("patch")
        status = {"added": "added", "removed": "removed"}.get(f.get("status"), "modified")
        # patch가 없으면 바이너리나 큰 파일이다. 이름만 바꾼 경우(renamed)가 아니면 읽을 수 없는 것으로 본다.
        entry = {"added": [], "removed": [], "status": status, "old": f.get("previous_filename", f["filename"]),
                 "unreadable": patch is None and f.get("status") != "renamed"}
        for line in (patch or "").splitlines():
            if line.startswith("+"):
                entry["added"].append(line[1:])
            elif line.startswith("-"):
                entry["removed"].append(line[1:])
        out[f["filename"]] = entry
    return out


def changed_lines(patches):
    return sum(len(p["added"]) + len(p["removed"]) for p in patches.values())


def assess_risk(files, patches, labels=(), body=""):
    """(위험도, 근거 목록). patches가 None이면 diff를 읽지 못한 것이라 높음이다.
    사람은 라벨 risk:high, risk:medium이나 본문 `위험도: 높음`으로 올릴 수만 있고 내릴 수는 없다."""
    reasons, mid = [], []
    paths = list(files)
    if not files or patches is None:
        reasons.append("변경 파일 목록이 비어 있거나 diff를 읽지 못함. 애매하면 높음")
    else:
        # 모든 판정은 파일 목록, diff 키, 이름을 바꾸기 전 경로의 합집합에 건다.
        paths = list(dict.fromkeys(list(files) + [k for k in patches if not k.startswith("?")]
                                   + [p["old"] for p in patches.values() if p.get("old")]))
        for f in paths:
            for why, pats in HIGH_PATHS:
                if match(f, pats):
                    reasons.append(f"{why}: {f}")
            if os.path.basename(f) in AGENT_FILES:
                reasons.append(f"에이전트 지침: {f}")
        for f in files:
            if f not in patches:
                reasons.append(f"diff에서 찾지 못한 파일: {f}")
        for f, p in patches.items():
            if p.get("unreadable") or (p.get("binary") and is_test_path(f)):
                reasons.append(f"diff를 읽지 못함: {f}")
            prod = f.startswith("backend/src/main/") or f.endswith(".sql")
            if prod and "db/migration" not in f:
                # 지워지거나 바뀐 줄도 본다. 잠금을 빼는 변경도 같은 위험이다.
                for kind_, lines in (("새로 생김", p["added"]), ("지워지거나 바뀜", p["removed"])):
                    hit = next((l.strip() for l in lines
                                if LOCK_LINE.search(l) and not l.strip().startswith(("//", "*", "/*", "--", "#"))), None)
                    if hit:
                        reasons.append(f"잠금, 트랜잭션, 쓰기 쿼리가 {kind_}: {f}: {hit[:80]}")
                        break
            if is_test_path(f):
                if p["status"] == "removed":
                    reasons.append(f"테스트 파일 삭제: {f}")
                gone = next((l.strip() for l in p["removed"] if ASSERT_REMOVED.search(l)), None)
                if gone and p["status"] != "removed":
                    reasons.append(f"기존 단언이나 테스트 줄 삭제 또는 변경: {f}: {gone[:80]}")
                skip = next((l.strip() for l in p["added"] if SKIP_ADDED.search(l)), None)
                if skip:
                    reasons.append(f"테스트를 건너뛰는 줄이 생김: {f}: {skip[:80]}")
                if p["status"] == "modified":
                    # 기존 테스트 파일을 바꾼 경우. 추가만 한 새 테스트와 다르게 본다.
                    touched = p["added"] + p["removed"]
                    if FIXTURE_PATH.search(f) or any(CONCURRENCY_LINE.search(l) for l in touched):
                        reasons.append(f"기존 동시성 테스트나 공유 테스트 픽스처 변경: {f}")
                    elif p["removed"]:
                        mid.append(f"기존 테스트 파일에서 줄이 지워지거나 바뀜: {f}: {p['removed'][0].strip()[:80]}")
                    else:
                        cond = next((l.strip() for l in p["added"] if COND_ADDED.search(l)), None)
                        if cond:
                            mid.append(f"기존 테스트 파일에 조건부 실행이 더해짐: {f}: {cond[:80]}")
    if reasons:
        tier = HIGH
    elif mid:
        tier, reasons = MID, mid
    elif all(is_doc(f) or is_test_path(f) for f in paths):
        tier = LOW
        reasons.append("문서와 테스트 추가만, 기존 테스트에서 지운 줄 없음" if any(is_test_path(f) for f in paths)
                       else "문서만 변경")
    else:
        tier = MID
        other = [f for f in paths if not is_doc(f) and not is_test_path(f)]
        other = [f for f in other if not f.endswith(".md")] or other
        reasons.append(f"운영 코드, 설정, perf 스크립트: {', '.join(other[:3])}{' 외' if len(other) > 3 else ''}")
    # 사람이 올리는 것만 반영한다.
    asked = [RISK_LABEL[l] for l in labels if l in RISK_LABEL] + RISK_BODY.findall(body or "")
    for a in asked:
        if RANK[a] > RANK[tier]:
            tier = a
            reasons.append(f"사람이 라벨이나 본문 표시로 {a}으로 올림")
    if any(l == "risk:low" for l in labels) or RISK_DOWN.search(body or ""):
        reasons.append("낮추는 표시는 무시함. 위험도는 올리기만 가능")
    return tier, reasons


def followup_ok(delta):
    """독립 PASS 뒤에 쌓인 후속 커밋이 작고 높음이 아니면 True와 근거."""
    n = changed_lines(delta)
    tier, reasons = assess_risk(list(delta), delta)
    if tier == HIGH:
        return False, f"후속 변경이 높음: {reasons[0]}"
    if n > FOLLOWUP_MAX_LINES:
        return False, f"후속 변경 {n}줄이 {FOLLOWUP_MAX_LINES}줄을 넘음"
    return True, f"후속 변경 {n}줄, {tier}"


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


def ci_blocks(files, rollup):
    """backend 검사가 성공이 아니거나, 검증 도구를 바꿨는데 tools 검사가 성공이 아니면 막는다."""
    out = []
    backend = [c for c in rollup if c.get("name") == "backend"]
    if backend and not all(c.get("conclusion") == "SUCCESS" for c in backend):
        out.append("backend CI가 성공하지 않음")
    tools = [c for c in rollup if c.get("name") == "tools"]
    if tools and not all(c.get("conclusion") == "SUCCESS" for c in tools):
        out.append("tools CI(분류기와 테스트 선택 자체 시험)가 성공하지 않음")
    elif not tools and any(f.startswith(("tools/verification/", "tools/ci/")) for f in files):
        out.append("검증 도구를 바꿨는데 tools CI 결과가 없음. CI를 다시 돌릴 것")
    return out


def pr_patches(n):
    """PR 전체 diff. 너무 크면 gh가 실패하므로 None이고, 그때 분류는 높음이다."""
    r = subprocess.run(["gh", "pr", "diff", str(n)], capture_output=True, text=True)
    return parse_diff(r.stdout) if r.returncode == 0 and r.stdout.strip() else None


def compare_delta(sha, head):
    """독립 PASS를 받은 sha와 지금 head 사이 변경. 읽지 못하면 None."""
    r = subprocess.run(["gh", "api", f"repos/{{owner}}/{{repo}}/compare/{sha}...{head}",
                        "--jq", "{files: [.files[] | {filename, status, patch, changes, previous_filename}]}"],
                       capture_output=True, text=True)
    if r.returncode != 0:
        return None
    try:
        files = json.loads(r.stdout)["files"]
        return None if len(files) >= 300 else parse_compare(files)
    except (ValueError, KeyError):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pr", type=int)
    ap.add_argument("--verify-dir", default=os.path.join(MAIN, ".ai-output/verification"))
    ap.add_argument("--classify-only", action="store_true", help="위험도와 근거만 출력하고 끝낸다")
    a = ap.parse_args()

    pr = json.loads(gh("pr", "view", str(a.pr), "--json",
                       "headRefOid,files,closingIssuesReferences,statusCheckRollup,body,title,labels"))
    head = pr["headRefOid"]
    files = [f["path"] for f in pr["files"]]
    issues = [i["number"] for i in pr["closingIssuesReferences"]]
    kind = classify(files)
    tier, why = assess_risk(files, pr_patches(a.pr), [l["name"] for l in pr["labels"]], pr["body"])
    blocks, notes, reviews = [], [], []

    print(f"PR #{a.pr} {pr['title']}")
    print(f"head SHA: {head}")
    print(f"변경 종류: {kind} (파일 {len(files)}개)")
    print(f"위험도: {tier}")
    for w in why:
        print(f"  근거: {w}")
    print(f"닫는 이슈: {', '.join(map(str, issues)) or '없음'}")
    if a.classify_only:
        return 0

    if tier != HIGH:
        need = "작성자 자기 검증, CI, 하네스. 독립 검증 없이 diff 확인으로 충분" if tier == MID \
            else "CI와 하네스 통과로 충분. 독립 검증 없음"
        notes.append(f"위험도 {tier}: {need}")
        if tier == MID:
            notes.append("운영 배포 전이면 머지 뒤 검증도 허용. 머지 전 diff 확인자를 PR에 적을 것")
        if kind == "policy-doc":
            notes.append("정책, 계약, 검증 절차, 불변식 문서 변경. 사람 확인 필요")
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
                ok, detail = (False, "PASS가 아님") if verdict != "PASS" else \
                    (lambda d: (False, "후속 변경을 읽지 못함") if d is None else followup_ok(d))(compare_delta(sha, head))
                if ok:
                    notes.append(f"#{n}: 판정 SHA {sha[:12]} 뒤 작은 후속 커밋. {detail}. 재검증 생략")
                    passed = True
                else:
                    blocks.append(f"#{n}: 판정 기록 SHA {sha[:12]}가 head {head[:12]}와 다름 ({detail}). 재검증하거나 트리를 비교할 것")
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
    if hot and tier == HIGH and not any("변이" in t for t in reviews):
        blocks.append(f"변이 시험 필수 경로 변경 {hot}. review.md에 변이 결과가 없음")

    cfg = [f for f in files if match(f, BUILD_CONFIG)]
    backend = [c for c in pr["statusCheckRollup"] if c.get("name") == "backend"]
    ci_ok = bool(backend) and all(c.get("conclusion") == "SUCCESS" for c in backend)
    scope = ci_test_scope(backend[-1]) if backend and ci_ok else None
    if cfg:
        print(f"CI 증거: 쓸 수 없음. 빌드나 CI 설정 변경 {cfg}. 검증자가 직접 build할 것")
    elif not backend:
        print("CI 증거: 쓸 수 없음. backend 검사 결과 없음")
    elif ci_ok and scope is None:
        print("CI 증거: 쓸 수 없음. CI가 돌린 테스트 범위를 읽지 못함. 검증자가 직접 build할 것")
    elif ci_ok and scope[0] == "none":
        print("CI 증거: 백엔드 변경 없음. build 증거가 필요 없음")
    elif ci_ok and scope[0] != "full":
        print(f"CI 증거: 쓸 수 없음. CI 범위 {scope[0]} {scope[1]}. 검증자가 직접 build할 것")
    elif ci_ok:
        print("CI 증거: 조건 1, 2, 4 충족. 검증자는 전체 build를 다시 돌리지 않는다. 조건 3(artifact 테스트 수)은 review.md에서 확인할 것")
    else:
        print(f"CI 증거: 쓸 수 없음. backend {backend[-1].get('conclusion') or '진행 중'}")
    blocks.extend(ci_blocks(files, pr["statusCheckRollup"]))

    for n in notes:
        print(f"참고: {n}")
    for b in blocks:
        print(f"막힘: {b}")
    print(f"원장 기록: 위험도 {tier}")
    print("RESULT:", "BLOCK" if blocks else "PASS")
    return 1 if blocks else 0


if __name__ == "__main__":
    sys.exit(main())
