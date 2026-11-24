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


def diff(path, added=(), removed=(), status="modified"):
    return {path: {"added": list(added), "removed": list(removed), "status": status, "old": path}}


def tier(files, patches, labels=(), body=""):
    return gate.assess_risk(files, patches, labels, body)


MAIN_J = "backend/src/main/java/com/restless"
TEST_J = "backend/src/test/java/com/restless"


class RiskTest(unittest.TestCase):
    def test_docs_only_is_low(self):
        f = "docs/testing/drop-performance-plan.md"
        self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.LOW)
        f = "docs/decisions/0006-x.md"
        self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.LOW)

    def test_test_additions_only_is_low(self):
        f = f"{TEST_J}/drop/application/DropQueueIntegrationTest.java"
        self.assertEqual(tier([f], diff(f, ["assertThat(a).isTrue();"]))[0], gate.LOW)

    def test_changed_or_removed_assertion_is_high(self):
        f = f"{TEST_J}/drop/application/DropQueueIntegrationTest.java"
        for gone in ["assertThat(a).isEqualTo(1);", "    verify(x).y();", "@Test", ".andExpect(status().isOk())"]:
            t, why = tier([f], diff(f, ["new"], [gone]))
            self.assertEqual(t, gate.HIGH, gone)
            self.assertIn("단언", why[0])

    def test_removed_non_assert_line_in_a_test_stays_low(self):
        f = f"{TEST_J}/drop/application/DropQueueIntegrationTest.java"
        self.assertEqual(tier([f], diff(f, ["int n = 2;"], ["int n = 1;"]))[0], gate.LOW)

    def test_deleted_test_file_and_disabled_test_are_high(self):
        f = f"{TEST_J}/drop/application/A.java"
        self.assertEqual(tier([f], diff(f, [], ["class A {}"], "removed"))[0], gate.HIGH)
        self.assertEqual(tier([f], diff(f, ["@Disabled"]))[0], gate.HIGH)

    def test_acceptance_architecture_harness_paths_are_high(self):
        for f in [f"{TEST_J}/drop/acceptance/DropAcceptanceSupport.java", f"{TEST_J}/architecture/A.java",
                  "backend/src/test/resources/harness/drop-invariants.sql", "tools/harness/guard/guard.py"]:
            self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.HIGH, f)

    def test_migration_contract_and_invariant_docs_are_high(self):
        for f in ["backend/src/main/resources/db/migration/V99__x.sql", "docs/contracts/api/drop.md",
                  "docs/domains/drop/invariants.md", "docs/domains/drop/transactions.md",
                  "docs/operations/failure-handling.md"]:
            self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.HIGH, f)

    def test_core_paths_are_high(self):
        for f in gate.MUTATION_REQUIRED[:2] + [f"{MAIN_J}/drop/application/DropInventoryService.java",
                                              f"{MAIN_J}/payment/domain/Payment.java"]:
            self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.HIGH, f)

    def test_verification_tooling_and_agent_instructions_are_high(self):
        for f in ["tools/verification/bin/merge-gate.py", "tools/ci/select-backend-tests.py", ".github/workflows/ci.yml",
                  "backend/build.gradle", "AGENTS.md", "tools/verification/skills/independent-verification/SKILL.md"]:
            self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.HIGH, f)

    def test_new_lock_or_transaction_in_production_code_is_high(self):
        f = f"{MAIN_J}/drop/application/Foo.java"
        for line in ["    @Transactional", "select * from t for update", "... FOR UPDATE SKIP LOCKED",
                     "@Lock(LockModeType.PESSIMISTIC_WRITE)", "@Modifying"]:
            self.assertEqual(tier([f], diff(f, [line]))[0], gate.HIGH, line)

    def test_lock_word_in_a_comment_or_in_tests_is_not_a_trigger(self):
        f = f"{MAIN_J}/drop/application/Foo.java"
        self.assertEqual(tier([f], diff(f, ["// FOR UPDATE is not used here"]))[0], gate.MID)
        t = f"{TEST_J}/drop/application/FooTest.java"
        self.assertEqual(tier([t], diff(t, ["@Transactional"]))[0], gate.LOW)

    def test_plain_production_code_config_and_perf_script_are_mid(self):
        f = f"{MAIN_J}/drop/api/Foo.java"
        self.assertEqual(tier([f], diff(f, ["return 1;"]))[0], gate.MID)
        for f in ["backend/src/main/resources/application.yml", "perf/scenarios/drop-sale.js", "perf/ec2/db-probe.sh"]:
            self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.MID, f)

    def test_mixed_change_takes_the_highest(self):
        a, b = f"{MAIN_J}/drop/api/Foo.java", "docs/contracts/api/drop.md"
        patches = {**diff(a, ["x"]), **diff(b, ["x"])}
        self.assertEqual(tier([a, b], patches)[0], gate.HIGH)

    def test_unreadable_diff_or_empty_list_is_high(self):
        self.assertEqual(tier(["docs/a.md"], None)[0], gate.HIGH)
        self.assertEqual(tier([], {})[0], gate.HIGH)

    def test_people_can_raise_but_not_lower(self):
        f = "docs/a.md"
        self.assertEqual(tier([f], diff(f, ["x"]), ["risk:high"])[0], gate.HIGH)
        self.assertEqual(tier([f], diff(f, ["x"]), [], "설명\n위험도: 중간\n")[0], gate.MID)
        self.assertEqual(tier([f], diff(f, ["x"]), [], "위험도: 높음")[0], gate.HIGH)
        h = "docs/contracts/api/drop.md"
        t, why = tier([h], diff(h, ["x"]), ["risk:low"], "위험도: 낮음")
        self.assertEqual(t, gate.HIGH)
        self.assertTrue(any("무시" in w for w in why))

    def test_every_result_has_a_reason(self):
        for f, p in [("docs/a.md", diff("docs/a.md", ["x"])), ("perf/x.js", diff("perf/x.js", ["x"]))]:
            self.assertTrue(tier([f], p)[1])

    def test_parse_diff(self):
        text = ("diff --git a/x/A.java b/x/A.java\nindex 1..2 100644\n--- a/x/A.java\n+++ b/x/A.java\n@@ -1 +1 @@\n"
                "-old\n+new\n ctx\n"
                "diff --git a/x/B.java b/x/B.java\nnew file mode 100644\n--- /dev/null\n+++ b/x/B.java\n@@ -0,0 +1 @@\n+b\n"
                "diff --git a/x/C.java b/x/C.java\ndeleted file mode 100644\n--- a/x/C.java\n+++ /dev/null\n@@ -1 +0,0 @@\n-c\n")
        d = gate.parse_diff(text)
        self.assertEqual(d["x/A.java"]["added"], ["new"])
        self.assertEqual(d["x/A.java"]["removed"], ["old"])
        self.assertEqual(d["x/B.java"]["status"], "added")
        self.assertEqual(d["x/C.java"]["status"], "removed")

    def test_removing_a_lock_or_transaction_is_high(self):
        f = f"{MAIN_J}/drop/application/Foo.java"
        for gone in ["    @Transactional", "select 1 for update", "@org.springframework.transaction.annotation.Transactional",
                     "RLock l = redisson.getLock(k);", "redis.opsForValue().setIfAbsent(k, v)",
                     'jdbc.update("UPDATE drop_stock SET qty = qty - 1")']:
            self.assertEqual(tier([f], diff(f, [], [gone]))[0], gate.HIGH, gone)

    def test_chained_assertions_and_awaits_count_as_assertions(self):
        f = f"{TEST_J}/drop/application/FooTest.java"
        for gone in [".doesNotContain(x);", ".allSatisfy(a -> {});", "latch.await(5, SECONDS);", "pool.invokeAll(tasks);"]:
            self.assertEqual(tier([f], diff(f, ["x"], [gone]))[0], gate.HIGH, gone)

    def test_test_resources_are_not_test_additions(self):
        f = "backend/src/test/resources/application-test.yml"
        self.assertEqual(tier([f], diff(f, ["lock-timeout: 0"], ["lock-timeout: 3000"]))[0], gate.MID)

    def test_docs_and_tests_together_stay_low(self):
        d, t = "docs/testing/drop-performance-plan.md", f"{TEST_J}/drop/application/FooTest.java"
        self.assertEqual(tier([d, t], {**diff(d, ["x"]), **diff(t, ["x"])})[0], gate.LOW)

    def test_verification_docs_are_high(self):
        f = "docs/testing/independent-verification.md"
        self.assertEqual(tier([f], diff(f, ["x"]))[0], gate.HIGH)

    def test_quoted_path_is_read_and_still_checked(self):
        text = ('diff --git "a/backend/src/test/java/com/restless/drop/application/\\355\\225\\234Test.java" '
                '"b/backend/src/test/java/com/restless/drop/application/\\355\\225\\234Test.java"\n'
                '--- "a/backend/src/test/java/com/restless/drop/application/\\355\\225\\234Test.java"\n'
                '+++ "b/backend/src/test/java/com/restless/drop/application/\\355\\225\\234Test.java"\n'
                '@@ -1 +1 @@\n-assertThat(a).isEqualTo(3);\n+assertThat(a).isNotNull();\n')
        d = gate.parse_diff(text)
        self.assertEqual(list(d), ["backend/src/test/java/com/restless/drop/application/한Test.java"])
        f = list(d)
        self.assertEqual(tier(f, d)[0], gate.HIGH)

    def test_rename_out_of_a_high_path_is_high(self):
        text = ("diff --git a/backend/src/test/java/com/restless/drop/acceptance/A.java "
                "b/backend/src/test/java/com/restless/drop/application/A.java\n"
                "similarity index 100%\nrename from backend/src/test/java/com/restless/drop/acceptance/A.java\n"
                "rename to backend/src/test/java/com/restless/drop/application/A.java\n")
        d = gate.parse_diff(text)
        new = "backend/src/test/java/com/restless/drop/application/A.java"
        self.assertIn(new, d)
        self.assertEqual(tier([new], d)[0], gate.HIGH)

    def test_dashes_inside_a_hunk_are_content(self):
        text = ("diff --git a/x.sql b/x.sql\n--- a/x.sql\n+++ b/x.sql\n@@ -1,2 +1 @@\n--- drop this\n-select 1 for update;\n+x\n")
        d = gate.parse_diff(text)
        self.assertEqual(d["x.sql"]["removed"], ["-- drop this", "select 1 for update;"])

    def test_binary_and_mode_only_changes_are_named_and_not_unreadable(self):
        text = ("diff --git a/docs/a.png b/docs/a.png\nnew file mode 100644\nBinary files /dev/null and b/docs/a.png differ\n"
                "diff --git a/perf/x.sh b/perf/x.sh\nold mode 100644\nnew mode 100755\n")
        d = gate.parse_diff(text)
        self.assertEqual(sorted(d), ["docs/a.png", "perf/x.sh"])
        self.assertEqual(tier(["perf/x.sh"], {"perf/x.sh": d["perf/x.sh"]})[0], gate.MID)

    def test_compare_binary_file_is_unreadable(self):
        d = gate.parse_compare([{"filename": "backend/src/main/resources/x.jar", "status": "modified", "changes": 0}])
        self.assertEqual(gate.followup_ok(d)[0], False)
        r = gate.parse_compare([{"filename": "a.md", "status": "renamed", "previous_filename": "b.md", "changes": 0}])
        self.assertFalse(r["a.md"]["unreadable"])

    def test_config_keys_that_change_transaction_or_schema_behavior_are_high(self):
        f = "backend/src/main/resources/application.yml"
        for line in ["    validate-on-migrate: false", "    open-in-view: true", "    hibernate.ddl-auto: update", "lock-timeout: 0"]:
            self.assertEqual(tier([f], diff(f, [line]))[0], gate.HIGH, line)

    def test_file_missing_from_diff_is_high(self):
        self.assertEqual(tier(["docs/a.md", "docs/b.md"], diff("docs/a.md", ["x"]))[0], gate.HIGH)

    def test_parse_compare_marks_missing_patch(self):
        d = gate.parse_compare([{"filename": "a.java", "status": "modified", "patch": "@@\n-a\n+b", "changes": 2},
                                {"filename": "b.bin", "status": "modified", "changes": 5}])
        self.assertEqual(d["a.java"]["removed"], ["a"])
        self.assertTrue(d["b.bin"]["unreadable"])
        self.assertEqual(gate.assess_risk(["b.bin"], d)[0], gate.HIGH)

    def test_followup_commit_small_and_not_high_passes(self):
        f = f"{MAIN_J}/drop/api/Foo.java"
        ok, _ = gate.followup_ok(diff(f, ["a"] * 5, ["b"] * 5))
        self.assertTrue(ok)

    def test_followup_commit_large_or_high_does_not_pass(self):
        f = f"{MAIN_J}/drop/api/Foo.java"
        self.assertFalse(gate.followup_ok(diff(f, ["a"] * 31))[0])
        self.assertFalse(gate.followup_ok(diff(f, ["@Transactional"]))[0])
        t = f"{TEST_J}/drop/acceptance/A.java"
        self.assertFalse(gate.followup_ok(diff(t, ["x"]))[0])

    def test_checklist_lists_every_high_path(self):
        checklist = (Path(__file__).resolve().parent / "checklist.md").read_text(encoding="utf-8")
        for why, pats in gate.HIGH_PATHS:
            self.assertIn(why, checklist, why)
            for p in pats:
                short = p.replace("backend/src/main/java/com/restless/", "") if pats is gate.MUTATION_REQUIRED else p
                self.assertIn(short.rstrip("*").rstrip("/").split("*")[0], checklist, p)


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
