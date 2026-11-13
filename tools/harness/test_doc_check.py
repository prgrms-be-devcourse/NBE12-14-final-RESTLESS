#!/usr/bin/env python3
"""Synthetic tests for doc-check.py. Usage: python3 tools/harness/test_doc_check.py
Each case builds a throwaway git repo, so the real docs are not touched."""
import os, subprocess, sys, tempfile

DC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "doc-check.py")
CODE = "backend/src/main/java/com/restless/drop/domain/Drop.java"
HEAD = "# T\n\n> 관련 코드: `backend/src/main/java/com/restless/drop/domain/`\n>\n> 마지막 확인: 2026-11-13\n\n"


def run(files, commits=(), extra=()):
    """files: {path: text} committed on 2026-11-13. commits: [(date, {path: text})] after it."""
    with tempfile.TemporaryDirectory() as d:
        def commit(date, fs):
            for p, t in fs.items():
                os.makedirs(os.path.dirname(os.path.join(d, p)) or d, exist_ok=True)
                open(os.path.join(d, p), "w", encoding="utf-8").write(t)
            env = dict(os.environ, GIT_AUTHOR_DATE=f"{date}T12:00:00", GIT_COMMITTER_DATE=f"{date}T12:00:00")
            subprocess.run(["git", "add", "-A"], cwd=d, check=True)
            subprocess.run(["git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", date],
                           cwd=d, check=True, env=env)
        subprocess.run(["git", "init", "-q"], cwd=d, check=True)
        commit("2026-11-13", {CODE: "class Drop {}", **files})
        for date, fs in commits:
            commit(date, fs)
        r = subprocess.run(["python3", DC, d, *extra], capture_output=True, text=True)
        return r.returncode, r.stdout


cases = [
    ("owned doc with header and good links passes",
     {"docs/domains/drop/a.md": HEAD + "[b](b.md) `backend/src/main/java/com/restless/drop/domain/Drop.java`",
      "docs/domains/drop/b.md": HEAD}, (), 0, "DOC-CHECK: PASS"),
    ("owned doc broken link fails",
     {"docs/domains/drop/a.md": HEAD + "[x](missing.md)"}, (), 1, "깨진 링크 missing.md"),
    ("owned doc broken code path fails",
     {"docs/domains/drop/a.md": HEAD + "`backend/src/main/java/com/restless/drop/Gone.java`"}, (), 1, "Gone.java"),
    ("notion attachment in owned doc fails",
     {"docs/domains/drop/a.md": HEAD + "![e](attachment:abc:x.png)"}, (), 1, "노션 첨부"),
    ("other owner's broken link is ignored by default",
     {"docs/domains/drop/a.md": HEAD, "docs/domains/ticketing/a.md": "# T\n[x](missing.md)"}, (), 0, "warnings 0"),
    ("--all lists other owner's broken link as a warning",
     {"docs/domains/drop/a.md": HEAD, "docs/domains/ticketing/a.md": "# T\n[x](missing.md)"}, (), 0, "다른 담당 문서의 깨진 링크 1건", ["--all"]),
    ("fenced code block is not checked",
     {"docs/domains/drop/a.md": HEAD + "```\n[x](missing.md) `docs/nope.md`\n```\n"}, (), 0, "DOC-CHECK: PASS"),
    ("placeholders and globs in code paths are skipped",
     {"docs/domains/drop/a.md": HEAD + "`docs/<domain>.md` `backend/.../X.java` `backend/src/main/java/com/restless/drop/domain/*.java`"},
     (), 0, "DOC-CHECK: PASS"),
    ("missing freshness header fails",
     {"docs/domains/drop/a.md": "# T\n\nbody"}, (), 1, "줄이 없음"),
    ("index page is exempt from header",
     {"docs/domains/drop/README.md": "# T\n\nbody"}, (), 0, "DOC-CHECK: PASS"),
    ("related path that does not exist fails",
     {"docs/domains/drop/a.md": "# T\n\n> 관련 코드: `backend/nope/`\n>\n> 마지막 확인: 2026-11-13\n"}, (), 1, "관련 코드 경로가 없음"),
    ("'없음' related code passes",
     {"docs/domains/drop/a.md": "# T\n\n> 관련 코드: 없음 (아직 구현 안 함)\n>\n> 마지막 확인: 2026-11-13\n"}, (), 0, "DOC-CHECK: PASS"),
    ("code changed after the check date warns",
     {"docs/domains/drop/a.md": HEAD}, [("2026-11-15", {CODE: "class Drop { int x; }"})], 0, "2026-11-15에 바뀜"),
    ("code changed on the check date does not warn",
     {"docs/domains/drop/a.md": HEAD}, [("2026-11-13", {CODE: "class Drop { int y; }"})], 0, "warnings 0"),
    ("a .md added inside the related code folder does not warn",
     {"docs/domains/drop/a.md": HEAD},
     [("2026-11-15", {"backend/src/main/java/com/restless/drop/domain/AGENTS.md": "# notes"})], 0, "warnings 0"),
    ("glob related path tracks matching files",
     {"docs/domains/drop/a.md": "# T\n\n> 관련 코드: `backend/src/main/resources/db/migration/V*__drop_*.sql`\n>\n> 마지막 확인: 2026-11-13\n",
      "backend/src/main/resources/db/migration/V1__drop_a.sql": "x"},
     # The later ticketing migration must not count: the warning names the drop file's date.
     [("2026-11-20", {"backend/src/main/resources/db/migration/V3__drop_b.sql": "z"}),
      ("2026-11-21", {"backend/src/main/resources/db/migration/V2__ticketing_a.sql": "y"})], 0, "2026-11-20에 바뀜"),
]
bad = 0
for name, files, commits, want_code, want_text, *extra in cases:
    code, out = run(files, commits, extra[0] if extra else ())
    ok = code == want_code and want_text in out
    bad += not ok
    print(("OK  " if ok else "BAD ") + name + ("" if ok else f"\n    exit={code} want={want_code}\n    " + out.replace("\n", "\n    ")))
with tempfile.TemporaryDirectory() as empty:  # deleted or wrong folder must not pass
    for name, path in (("empty folder fails", empty), ("missing folder fails", os.path.join(empty, "gone"))):
        r = subprocess.run(["python3", DC, path], capture_output=True, text=True)
        ok = r.returncode == 1 and "검사할 문서가 없음" in r.stdout
        bad += not ok
        cases.append(name)
        print(("OK  " if ok else "BAD ") + name)
print(f"cases={len(cases)} failures={bad}")
sys.exit(1 if bad else 0)
