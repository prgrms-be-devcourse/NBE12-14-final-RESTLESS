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
        self.assertEqual(gate.classify(["docs/operations/failure-handling.md"]), "code-doc")

    def test_format_docs(self):
        self.assertEqual(gate.classify(["docs/README.md", "perf/README.md"]), "format-doc")

    def test_policy_wins_over_code_description(self):
        self.assertEqual(gate.classify(["docs/operations/x.md", "docs/domains/drop/x.md"]), "policy-doc")


class RecordTest(unittest.TestCase):
    def test_last_record_line_wins_and_table_prefix_is_allowed(self):
        text = ("판정 기록: FAIL " + "a" * 40 + "\n"
                "| 판정 기록: PASS " + "b" * 40 + " |\n")
        self.assertEqual(gate.RECORD.findall(text)[-1], ("PASS", "b" * 40))

    def test_short_sha_is_not_a_record(self):
        self.assertEqual(gate.RECORD.findall("판정 기록: PASS 66ca50c"), [])


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

    def test_base_sha_after_korean_particle(self):
        _, out = self.run_lint("# x (이슈 #1)\n\n작업 폴더: /tmp/wt\n기준 커밋: `fix/a`의 930d1fc\n## 하지 않을 것\n")
        self.assertNotIn("W1", out)


if __name__ == "__main__":
    unittest.main()
