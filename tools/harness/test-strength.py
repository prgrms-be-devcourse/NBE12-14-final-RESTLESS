#!/usr/bin/env python3
"""Checks that the acceptance tests can fail: the harness's own test. See tools/harness/README.md.

  python3 tools/harness/test-strength.py red <AcceptanceClass> [--ref REF]
      Before a stage is implemented: turn the class's @Disabled off in a throwaway copy and run it.
      Every test should FAIL. A test that passes with no implementation checks nothing.

  python3 tools/harness/test-strength.py mutants [--only M1,M3] [--ref REF]
      After a stage is merged: put one known bug at a time into a throwaway copy (mutants/drop.json)
      and run the acceptance class that should catch it. Every bug should be CAUGHT.

Both work in a temporary git worktree at REF (default HEAD), so the working folder is never touched.
Exit 0 = all as expected, 1 = a weak test (passed in red, or a bug survived), 2 = setup error.
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ACCEPTANCE_DIR = "backend/src/test/java/com/restless/drop/acceptance"
ACCEPTANCE_PKG = "com.restless.drop.acceptance"
MUTANTS = os.path.join(HERE, "mutants", "drop.json")
DISABLED = (re.compile(r"^@Disabled\("), re.compile(r"^import org\.junit\.jupiter\.api\.Disabled;$"))


def git(*args, cwd=None, check=True):
    r = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout.strip()


class Worktree:
    """A detached throwaway checkout of REF, removed on exit."""

    def __init__(self, ref):
        self.repo = git("rev-parse", "--show-toplevel")
        self.commit = git("rev-parse", "--short", ref)
        self.path = tempfile.mkdtemp(prefix="harness-strength-")
        os.rmdir(self.path)

    def __enter__(self):
        git("worktree", "add", "-q", "--detach", self.path, self.commit, cwd=self.repo)
        return self

    def __exit__(self, *exc):
        git("worktree", "remove", "--force", self.path, cwd=self.repo, check=False)
        shutil.rmtree(self.path, ignore_errors=True)

    def reset(self):
        git("checkout", "-q", "--", ".", cwd=self.path)

    def run_class(self, cls):
        """Run one acceptance class. Returns (status, {test: passed}) with status ok|failed|no-build."""
        log = os.path.join(self.path, "strength-gradle.log")
        with open(log, "w") as out:
            r = subprocess.run(["./gradlew", "cleanTest", "test", "--tests", f"{ACCEPTANCE_PKG}.{cls}"],
                               cwd=os.path.join(self.path, "backend"), stdout=out, stderr=subprocess.STDOUT)
        results = {}
        for x in glob.glob(os.path.join(self.path, "backend/build/test-results/test", f"TEST-{ACCEPTANCE_PKG}.{cls}.xml")):
            for case in ET.parse(x).getroot().iter("testcase"):
                bad = case.find("failure") is not None or case.find("error") is not None
                results[case.get("name").removesuffix("()")] = not bad
        if not results:
            tail = open(log, encoding="utf-8", errors="replace").read().splitlines()[-15:]
            return "no-build", {"_log": "\n".join(tail)}
        return ("ok" if r.returncode == 0 else "failed"), results


def display_names(worktree_path, cls):
    """method name -> @DisplayName text. Gradle's XML reports the display name, not the method."""
    src = open(os.path.join(worktree_path, ACCEPTANCE_DIR, f"{cls}.java"), encoding="utf-8").read()
    return {m: d for d, m in re.findall(r'@DisplayName\("((?:[^"\\]|\\.)*)"\)\s*(?:@\w+(?:\([^)]*\))?\s*)*void\s+(\w+)\s*\(', src)}


def apply_edits(wt_path, m):
    """Apply a mutant's edits. Returns None, or the file whose code to break is gone."""
    for e in m.get("edits") or [m]:
        file = os.path.join(wt_path, e["file"])
        text = open(file, encoding="utf-8").read() if os.path.exists(file) else ""
        if e["find"] not in text:
            return e["file"]
        text = text.replace(e["find"], e["replace"]) if e.get("all") else text.replace(e["find"], e["replace"], 1)
        open(file, "w", encoding="utf-8").write(text)
    return None


def red(args):
    path = f"{ACCEPTANCE_DIR}/{args.cls}.java"
    with Worktree(args.ref) as wt:
        file = os.path.join(wt.path, path)
        if not os.path.exists(file):
            sys.exit(f"{path} not found at {wt.commit}")
        lines = open(file, encoding="utf-8").read().splitlines(keepends=True)
        kept = [l for l in lines if not any(p.match(l.strip()) for p in DISABLED)]
        if len(kept) == len(lines):
            print(f"WARN: {args.cls} has no @Disabled at {wt.commit}. It is already on; red check is only "
                  f"meaningful before the stage is implemented (try --ref <commit before the stage>).")
        open(file, "w", encoding="utf-8").writelines(kept)
        print(f"== red check  {args.cls}  at {wt.commit} (no implementation expected)")
        status, results = wt.run_class(args.cls)
    if status == "no-build":
        print("ERROR: tests did not run. Gradle tail:\n" + results["_log"])
        return 2
    passed = [t for t, ok in results.items() if ok]
    for t, ok in results.items():
        print(("WEAK  passes without implementation: " if ok else "OK    fails: ") + t)
    print(f"RESULT: {'PASS' if not passed else 'WEAK'}  tests={len(results)} passing_without_implementation={len(passed)}")
    return 1 if passed else 0


def mutants(args):
    specs = json.load(open(MUTANTS, encoding="utf-8"))
    if args.only:
        wanted = set(args.only.split(","))
        specs = [m for m in specs if m["id"] in wanted]
    rows, weak = [], 0
    with Worktree(args.ref) as wt:
        print(f"== mutant check  {len(specs)} bugs  at {wt.commit}")
        for m in specs:
            gone = apply_edits(wt.path, m)
            if gone:
                wt.reset()
                rows.append((m, "STALE", f"the code to break is gone in {gone}; update mutants/drop.json"))
                weak += 1
                print(f"{'STALE':<17} {m['id']} {m['bug']}  -> {rows[-1][2]}", flush=True)
                continue
            cls, _, method = m["test"].partition("#")
            method = display_names(wt.path, cls).get(method, method)
            status, results = wt.run_class(cls)
            wt.reset()
            if status == "no-build":
                rows.append((m, "NO-BUILD", "the bug does not compile; fix the mutant"))
                weak += 1
            elif results.get(method) is False:
                others = [t for t, ok in results.items() if not ok and t != method]
                rows.append((m, "CAUGHT", f"{method}" + (f" (+{len(others)} more)" if others else "")))
            else:
                others = [t for t, ok in results.items() if not ok]
                verdict = "CAUGHT-ELSEWHERE" if others else "SURVIVED"
                rows.append((m, verdict, ", ".join(others) if others else f"{m['test']} still passes"))
                weak += not others
            print(f"{rows[-1][1]:<17} {m['id']} {m['bug']}  -> {rows[-1][2]}", flush=True)
    caught = sum(1 for r in rows if r[1].startswith("CAUGHT"))
    print(f"RESULT: {'PASS' if not weak else 'WEAK'}  caught={caught}/{len(rows)}")
    return 1 if weak else 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="mode", required=True)
    r = sub.add_parser("red")
    r.add_argument("cls")
    r.add_argument("--ref", default="HEAD")
    m = sub.add_parser("mutants")
    m.add_argument("--only")
    m.add_argument("--ref", default="HEAD")
    args = p.parse_args()
    return red(args) if args.mode == "red" else mutants(args)


if __name__ == "__main__":
    sys.exit(main())
