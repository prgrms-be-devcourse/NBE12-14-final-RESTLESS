#!/usr/bin/env python3
"""Synthetic tests for guard.py codex-hook. Usage: python3 test_guard.py <worktree-path>
Expected results depend on the worktree's branch kind: drop | harness | other."""
import json, os, re, subprocess, sys
G = os.path.join(os.path.dirname(os.path.abspath(__file__)), "guard.py")
W = os.path.realpath(sys.argv[1])
branch = subprocess.run(["git", "-C", W, "branch", "--show-current"], capture_output=True, text=True).stdout.strip()
kind = "harness" if re.match(r"^chore/drop-\d+-harness$", branch) else ("drop" if "drop" in branch else "other")
ACC = "backend/src/test/java/com/restless/drop/acceptance/DropStockHoldAcceptanceTest.java"
P = lambda b: "*** Begin Patch\n" + b + "\n*** End Patch"
B = 2  # blocked
# (name, event, expected exit per kind {drop, harness, other})
cases = [
 ("remove @Disabled", {"tool_name": "apply_patch", "tool_input": {"command": P(f"*** Update File: {ACC}\n@@\n-import org.junit.jupiter.api.Disabled;\n@@\n-@Disabled(\"x\")\n class X {{")}}, (0, 0, 0)),
 ("change assertion", {"tool_name": "apply_patch", "tool_input": {"command": P(f"*** Update File: {ACC}\n@@\n-        assertThat(a).isEqualTo(1);\n+        assertThat(a).isEqualTo(2);")}}, (B, 0, B)),
 ("add file in acceptance", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Add File: backend/src/test/java/com/restless/drop/acceptance/New.java\n+class New {}")}}, (B, 0, B)),
 ("absolute path harness sql", {"tool_name": "apply_patch", "tool_input": {"input": P(f"*** Update File: {W}/backend/src/test/resources/harness/drop-invariants.sql\n@@\n-SELECT 1;\n+SELECT 0;")}}, (B, 0, B)),
 ("edit merged migration", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: backend/src/main/resources/db/migration/V20261111_1010__drop_create_tables.sql\n@@\n-a\n+b")}}, (B, B, B)),
 ("add new migration", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Add File: backend/src/main/resources/db/migration/V20261114_0900__drop_add_col.sql\n+ALTER TABLE x;")}}, (0, 0, 0)),
 ("edit unmerged new migration", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: backend/src/main/resources/db/migration/V20991231_0000__not_in_dev.sql\n@@\n-a\n+b")}}, (0, 0, 0)),
 ("write stage report", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Add File: .ai-output/harness/reports/D2-report.md\n+# D2")}}, (0, 0, 0)),
 ("write report via shell", {"tool_name": "shell", "tool_input": {"command": "cat > .ai-output/harness/reports/D2-report.md <<'X'\n# D2\nX"}}, (0, 0, 0)),
 ("overwrite stage spec", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: .ai-output/harness/reports/D2-spec.md\n@@\n-a\n+b")}}, (B, B, B)),
 ("drop main code", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: backend/src/main/java/com/restless/drop/application/DropService.java\n@@\n-a\n+b")}}, (0, 0, 0)),
 ("other domain", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: backend/src/main/java/com/restless/ticketing/A.java\n@@\n-a\n+b")}}, (B, B, B)),
 ("versioned harness tool (patch)", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: tools/harness/harness-check.sh\n@@\n-a\n+b")}}, (B, B, B)),
 ("versioned guard (shell)", {"tool_name": "shell", "tool_input": {"command": "echo x > tools/harness/guard/guard.py"}}, (B, B, B)),
 ("run versioned harness-check", {"tool_name": "shell", "tool_input": {"command": "bash tools/harness/harness-check.sh > .ai-output/x.log 2>&1"}}, (0, 0, 0)),
 # Known gap: the shell check matches path text, so cd + relative path is not seen here.
 # pre-commit and harness-check.sh (diff based) still catch the change. Kept to document the limit.
 ("known gap: cd then relative write", {"tool_name": "shell", "tool_input": {"command": "cd backend/src/test/java/com/restless/drop && sed -i '' 's/1/2/' acceptance/X.java"}}, (0, 0, 0)),
 ("harness script", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: .ai-output/harness/harness-check.sh\n@@\n-a\n+b")}}, (B, B, B)),
 ("codex hooks config", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Add File: .codex/hooks.json\n+{}")}}, (B, B, B)),
 ("move into acceptance", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: backend/src/main/java/com/restless/drop/A.java\n*** Move to: backend/src/test/java/com/restless/drop/acceptance/A.java\n@@\n-a\n+b")}}, (B, 0, B)),
 ("cat acceptance", {"tool_name": "shell", "tool_input": {"command": ["bash", "-lc", f"cat {ACC}"]}}, (0, 0, 0)),
 ("grep with 2>&1", {"tool_name": "shell", "tool_input": {"command": f"grep -n Disabled {ACC} 2>&1 | head"}}, (0, 0, 0)),
 ("sed -i acceptance", {"tool_name": "shell", "tool_input": {"command": ["bash", "-lc", f"sed -i '' 's/1/2/' {ACC}"]}}, (B, B, B)),
 ("git checkout acceptance", {"tool_name": "shell", "tool_input": {"command": f"git checkout -- {ACC}"}}, (B, B, B)),
 ("redirect into harness sql", {"tool_name": "shell", "tool_input": {"command": "echo 'SELECT 0;' > backend/src/test/resources/harness/drop-invariants.sql"}}, (B, B, B)),
 ("gradle to log", {"tool_name": "shell", "tool_input": {"command": "cd backend && ./gradlew test --tests '*DropStockHold*' > ../.ai-output/D2-green.log 2>&1"}}, (0, 0, 0)),
 ("run harness-check", {"tool_name": "shell", "tool_input": {"command": "bash .ai-output/harness/harness-check.sh"}}, (0, 0, 0)),
 ("harness-check to log (HD-10)", {"tool_name": "shell", "tool_input": {"command": "bash .ai-output/harness/harness-check.sh > .ai-output/p72-harness.log 2>&1"}}, (0, 0, 0)),
 ("tail harness log | tee scratch", {"tool_name": "shell", "tool_input": {"command": "tail -4 .ai-output/harness/last-build-X.log | tee .ai-output/copy.log"}}, (0, 0, 0)),
 ("redirect into harness file", {"tool_name": "shell", "tool_input": {"command": "cat x > .ai-output/harness/progress.md"}}, (B, B, B)),
 ("tee into guard", {"tool_name": "shell", "tool_input": {"command": "echo hi | tee .ai-output/harness/guard/guard.py"}}, (B, B, B)),
 ("cp out of harness stays blocked", {"tool_name": "shell", "tool_input": {"command": "cp .ai-output/harness/last.log .ai-output/x.log"}}, (B, B, B)),
 ("write P-stage report", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Add File: .ai-output/harness/reports/P72-report.md\n+# P72")}}, (0, 0, 0)),
 ("write P-stage report via shell", {"tool_name": "shell", "tool_input": {"command": "cat > .ai-output/harness/reports/P72-report.md <<'X'\n# P72\nX"}}, (0, 0, 0)),
 ("git add acceptance", {"tool_name": "shell", "tool_input": {"command": f"git add {ACC} backend/src/main/java/com/restless/drop"}}, (0, 0, 0)),
 ("git commit", {"tool_name": "shell", "tool_input": {"command": "git commit -m 'feat(drop): add stock hold' -- backend"}}, (0, 0, 0)),
 ("commit --no-verify", {"tool_name": "shell", "tool_input": {"command": "git commit --no-verify -m x"}}, (B, B, B)),
 ("commit -nm", {"tool_name": "shell", "tool_input": {"command": "git commit -nm x"}}, (B, B, B)),
 ("git push", {"tool_name": "shell", "tool_input": {"command": "git push origin feat/drop-58"}}, (B, B, B)),
 ("HARNESS_EDIT", {"tool_name": "shell", "tool_input": {"command": "HARNESS_EDIT=1 bash .ai-output/harness/harness-check.sh"}}, (B, B, B)),
 ("hooksPath", {"tool_name": "shell", "tool_input": {"command": "git config core.hooksPath /tmp/x"}}, (B, B, B)),
 ("rm pre-commit hook", {"tool_name": "shell", "tool_input": {"command": "rm .git/hooks/pre-commit"}}, (B, B, B)),
 ("edit drop agent instructions", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: backend/src/main/java/com/restless/drop/AGENTS.md\n@@\n-a\n+b")}}, (B, B, B)),
 ("drop CLAUDE.md via shell", {"tool_name": "shell", "tool_input": {"command": "echo x > backend/src/main/java/com/restless/drop/CLAUDE.md"}}, (B, B, B)),
 ("new memo at repo root", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Add File: readyToX.md\n+# memo")}}, (B, B, B)),
 ("edit root README", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Update File: README.md\n@@\n-a\n+b")}}, (0, 0, 0)),
 ("new doc under docs", {"tool_name": "apply_patch", "tool_input": {"command": P("*** Add File: docs/reports/EXP-01.md\n+# r")}}, (0, 0, 0)),
 ("outside repo", {"cwd": "/tmp", "tool_name": "shell", "tool_input": {"command": "git push"}}, (0, 0, 0)),
]
idx = {"drop": 0, "harness": 1, "other": 2}[kind]
env = dict(os.environ, GUARD_NO_LOG="1")
bad = 0
for name, ev, want in cases:
    ev.setdefault("cwd", W)
    r = subprocess.run(["python3", G, "codex-hook"], input=json.dumps(ev), capture_output=True, text=True, env=env)
    ok = r.returncode == want[idx]
    bad += not ok
    print(("OK  " if ok else "BAD ") + f"{name}: exit={r.returncode} want={want[idx]}")
print(f"branch={branch} kind={kind} cases={len(cases)} failures={bad}")
sys.exit(1 if bad else 0)
