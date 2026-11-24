"""Self-tests for merge-gate.py and spec-lint.py. No network, no gh.

  python3 tools/verification/test_tools.py
"""
import contextlib
import importlib.util
import io
import os
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parent / "bin"


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), BIN / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gate = load("merge-gate")
lint = load("spec-lint")


class ClassifyTest(unittest.TestCase):
    def test_any_non_doc_file_is_code(self):
        self.assertEqual(gate.classify(["docs/README.md", "backend/build.gradle"]), "code")

    def test_policy_contract_and_adr_docs(self):
        for f in ["docs/domains/drop/policy.md", "docs/contracts/api/drop.md",
                  "docs/decisions/0004-request-rate-limit.md", "docs/product/open-questions.md"]:
            self.assertEqual(gate.classify([f]), "policy-doc", f)

    def test_code_description_docs(self):
        self.assertEqual(gate.classify(["docs/operations/runbook.md"]), "code-doc")
        self.assertEqual(gate.classify(["docs/architecture/overview.md"]), "code-doc")

    def test_invariant_and_verification_docs_are_policy(self):
        for f in ["docs/operations/failure-handling.md", "docs/testing/independent-verification.md",
                  "docs/testing/strategy.md"]:
            self.assertEqual(gate.classify([f]), "policy-doc", f)

    def test_agent_instructions_and_tool_docs_are_code(self):
        # B1: these .md files steer agents or tools, so they need the same review as code.
        for f in ["tools/verification/skills/independent-verification/SKILL.md",
                  "tools/verification/checklist.md", "backend/src/main/java/com/restless/drop/AGENTS.md",
                  "backend/src/main/resources/db/migration/README.md", "AGENTS.md", "CLAUDE.md",
                  "docs/AGENTS.md"]:
            self.assertEqual(gate.classify([f]), "code", f)

    def test_empty_file_list_is_not_waved_through(self):
        self.assertEqual(gate.classify([]), "code")

    def test_format_docs(self):
        self.assertEqual(gate.classify(["docs/README.md", "README.md"]), "format-doc")
        self.assertEqual(gate.classify(["perf/README.md"]), "code")

    def test_gate_paths_match_checklist(self):
        checklist = (Path(__file__).resolve().parent / "checklist.md").read_text(encoding="utf-8")
        for p in gate.POLICY + gate.CODE_DOC + gate.BUILD_CONFIG:
            self.assertIn(p.rstrip("*").rstrip("/").split("*")[0], checklist, p)

    def test_policy_wins_over_code_description(self):
        self.assertEqual(gate.classify(["docs/operations/x.md", "docs/domains/drop/x.md"]), "policy-doc")


class RecordTest(unittest.TestCase):
    def test_last_line_wins_and_table_prefix_is_allowed(self):
        text = "판정 기록: FAIL " + "a" * 40 + "\n| 판정 기록: PASS " + "b" * 40 + " |\n\n"
        self.assertEqual(gate.last_record(text), ("PASS", "b" * 40))

    def test_record_before_the_last_line_does_not_count(self):
        # N1: an example or quote earlier in the file must not turn a FAIL into a PASS.
        text = "판정 기록: PASS " + "b" * 40 + "\n\n최종 판정은 FAIL이다.\n"
        self.assertIsNone(gate.last_record(text))

    def test_short_or_long_sha_is_not_a_record(self):
        self.assertIsNone(gate.last_record("판정 기록: PASS 66ca50c"))
        self.assertIsNone(gate.last_record("판정 기록: PASS " + "c" * 64))

    def test_no_review_reason_is_required(self):
        self.assertIsNone(gate.NO_REVIEW.search("독립 검증 없음"))
        self.assertIsNotNone(gate.NO_REVIEW.search("독립 검증 없음: 오타 수정"))


class CiScopeTest(unittest.TestCase):
    def test_reads_scope_notice_among_other_annotations(self):
        gate = load("merge-gate")
        msgs = ["Node.js 20 is deprecated.", "Backend test scope: partial (바뀐 도메인 drop)"]
        self.assertEqual(gate.scope_from_annotations(msgs), ("partial", "(바뀐 도메인 drop)"))
        self.assertEqual(gate.scope_from_annotations(["Backend test scope: full (CI 설정 변경)"])[0], "full")

    def test_missing_notice_is_unknown(self):
        self.assertIsNone(load("merge-gate").scope_from_annotations(["setup-java v4 is deprecated."]))


class SpecLintTest(unittest.TestCase):
    def run_lint(self, body):
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False, encoding="utf-8") as f:
            f.write(body)
        try:
            with contextlib.redirect_stdout(io.StringIO()) as out:
                ok = lint.lint(f.name)
        finally:
            os.unlink(f.name)
        return ok, out.getvalue()

    def spec(self, folder, extra=""):
        return (f"# Codex 작업: x (이슈 #1)\n\n작업 폴더: {folder}\n기준: dev 17e2c78\n"
                f"\n## 하지 않을 것\n- x\n{extra}")

    def test_clean_worktree_spec_passes(self):
        ok, out = self.run_lint(self.spec("/tmp/wt1", f"보고서: {lint.MAIN}/.ai-output/issues/1-report.md\n"
                                          "`tools/harness`는 git에 없어 실행하지 않는다.\n"))
        self.assertTrue(ok, out)

    def test_missing_issue_and_ambiguous_choice(self):
        ok, out = self.run_lint("# 작업\n\n작업 폴더: /tmp/wt1\n다음 이슈 진행해줘\n")
        self.assertFalse(ok)
        self.assertIn("E1", out)
        self.assertIn("E2", out)

    def test_worktree_must_not_run_untracked_harness(self):
        ok, out = self.run_lint(self.spec("/tmp/wt1", "루트에서 `bash tools/harness/harness-check.sh` 결과도 보고한다.\n"))
        self.assertFalse(ok)
        self.assertIn("E3", out)

    def test_worktree_report_path_must_be_absolute(self):
        ok, out = self.run_lint(self.spec("/tmp/wt1", "보고서 `.ai-output/issues/1-report.md`에 적는다.\n"))
        self.assertFalse(ok)
        self.assertIn("E4", out)

    def test_main_folder_may_use_relative_paths_and_harness(self):
        ok, out = self.run_lint(self.spec(lint.MAIN, "보고서 `.ai-output/issues/1-report.md`.\n"
                                          "`bash tools/harness/harness-check.sh`를 돌린다.\n"))
        self.assertTrue(ok, out)

    def test_negation_in_an_earlier_clause_does_not_hide_harness_run(self):
        # B2: the real #271 spec line and a minimal variant.
        for line in ["수정하지 않는다. 수정하면 `bash tools/harness/harness-check.sh`를 돌려 확인한다.\n",
                     "기존 코드는 수정하지 않고 `bash tools/harness/harness-check.sh`를 돌린다.\n"]:
            ok, out = self.run_lint(self.spec("/tmp/wt1", line))
            self.assertFalse(ok, line)
            self.assertIn("E3", out)

    def test_negation_in_a_later_clause_does_not_hide_relative_report(self):
        ok, out = self.run_lint(self.spec("/tmp/wt1", "보고서 `.ai-output/issues/9-report.md`에 적고, 테스트는 금지된 단언을 쓰지 않는다.\n"))
        self.assertFalse(ok)
        self.assertIn("E4", out)

    def test_any_relative_ai_output_path_in_worktree(self):
        ok, out = self.run_lint(self.spec("/tmp/wt1", "로그는 `.ai-output/x/run.txt`에 남긴다.\n"))
        self.assertFalse(ok)
        self.assertIn("E4", out)

    def test_prohibited_path_mention_is_allowed(self):
        ok, out = self.run_lint(self.spec("/tmp/wt1", "- `tools/harness/**`, `.ai-output/harness/**` 수정 금지\n"))
        self.assertTrue(ok, out)

    def test_handing_off_by_new_spec_is_not_ambiguous(self):
        ok, out = self.run_lint(self.spec("/tmp/wt1", "다음 이슈는 새 지시서로 받는다.\n"))
        self.assertTrue(ok, out)

    def test_home_relative_folder_is_checked(self):
        ok, out = self.run_lint(self.spec("~/wt1", "`bash tools/harness/harness-check.sh`를 돌린다.\n"))
        self.assertFalse(ok)
        self.assertIn("E3", out)

    def test_base_sha_after_korean_particle(self):
        _, out = self.run_lint("# x (이슈 #1)\n\n작업 폴더: /tmp/wt\n기준 커밋: `fix/a`의 930d1fc\n## 하지 않을 것\n")
        self.assertNotIn("W1", out)


if __name__ == "__main__":
    unittest.main()
