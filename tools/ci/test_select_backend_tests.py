"""select-backend-tests.py 규칙 테스트. python3 -m unittest discover -s tools/ci -p 'test_*.py'"""
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("select_tests", os.path.join(HERE, "select-backend-tests.py"))
select_tests = importlib.util.module_from_spec(spec)
spec.loader.exec_module(select_tests)

MAIN = "backend/src/main/java/com/restless"
TEST = "backend/src/test/java/com/restless"
ALWAYS = ["com.restless.architecture.*", "com.restless.RestlessApplicationTests"]


class Fixture(unittest.TestCase):
    def setUp(self):
        # 아래 선택 규칙 시험은 drop을 일반 도메인 예시로 쓴다. 항상 전체 규칙은 FullModulesTest가 따로 본다.
        self.real_full_modules = select_tests.FULL_MODULES
        select_tests.FULL_MODULES = set()
        self.addCleanup(setattr, select_tests, "FULL_MODULES", self.real_full_modules)
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        # member is shared. drop implements member's interface. auction uses credit. reporting has no tests.
        self.java(MAIN, "member", "public interface WithdrawalGuard {}")
        self.java(MAIN, "drop", "import com.restless.member.api.WithdrawalGuard;\n"
                                "class DropGuard implements WithdrawalGuard {}")
        self.java(MAIN, "credit", "")
        self.java(MAIN, "auction", "import com.restless.credit.Credit;")
        self.java(MAIN, "reporting", "")
        self.java(MAIN, "uses", "import com.restless.reporting.R;")
        for m in ["member", "drop", "credit", "auction", "architecture", "uses"]:
            self.java(TEST, m, "")
        self.java(TEST, "ticketing", "import com.restless.drop.Y;")

    def tearDown(self):
        self.tmp.cleanup()

    def java(self, root, module, body):
        d = os.path.join(self.repo, root, module)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "A.java"), "w", encoding="utf-8") as f:
            f.write(body)

    def select(self, *files):
        scope, patterns, _ = select_tests.select(list(files), self.repo)
        return scope, patterns


class SelectTest(Fixture):
    def test_domain_change_runs_domain_interface_owner_test_users_and_always(self):
        self.assertEqual(self.select(f"{MAIN}/drop/A.java"),
                         ("partial", ["com.restless.drop.*", "com.restless.member.*",
                                      "com.restless.ticketing.*"] + ALWAYS))

    def test_domain_used_by_another_domain_spreads(self):
        self.assertEqual(self.select(f"{MAIN}/credit/A.java"),
                         ("partial", ["com.restless.auction.*", "com.restless.credit.*"] + ALWAYS))

    def test_two_domains_run_both(self):
        scope, patterns = self.select(f"{MAIN}/auction/A.java", f"{TEST}/drop/A.java")
        self.assertEqual(scope, "partial")
        self.assertTrue({"com.restless.auction.*", "com.restless.drop.*"} <= set(patterns))

    def test_shared_module_runs_everything(self):
        self.assertEqual(self.select(f"{MAIN}/member/A.java"), ("full", []))
        self.assertEqual(self.select(f"{TEST}/member/A.java"), ("full", []))

    def test_rename_from_shared_module_runs_everything(self):
        self.assertEqual(self.select(f"{MAIN}/member/Old.java", f"{MAIN}/drop/New.java"), ("full", []))

    def test_paths_outside_modules_run_everything(self):
        for f in ["backend/build.gradle", "backend/Dockerfile",
                  "backend/src/main/resources/db/migration/V1.sql",
                  "backend/src/main/resources/application.yml",
                  "backend/src/test/resources/harness/x.sql",
                  f"{TEST}/TestcontainersConfiguration.java", f"{MAIN}/RestlessApplication.java",
                  ".github/workflows/ci.yml", "tools/ci/select-backend-tests.py"]:
            self.assertEqual(self.select(f), ("full", []), f)

    def test_domain_without_tests_runs_everything(self):
        self.assertEqual(self.select(f"{MAIN}/reporting/A.java"), ("full", []))

    def test_non_backend_change_runs_nothing(self):
        self.assertEqual(self.select("docs/a.md", "frontend/src/x.ts", "perf/x.js"), ("none", []))

    def test_architecture_test_change_runs_always_only(self):
        self.assertEqual(self.select(f"{TEST}/architecture/A.java"), ("partial", ALWAYS))


    def test_wildcard_imported_interface_counts_as_implemented(self):
        self.java(MAIN, "payment", "public interface PaymentResultHandler {}")
        self.java(MAIN, "fanmeeting", "import com.restless.payment.application.payable.*;\n"
                                      "class Handler implements PaymentResultHandler {}")
        self.java(TEST, "fanmeeting", "")
        self.java(TEST, "payment", "")
        scope, patterns = self.select(f"{MAIN}/fanmeeting/A.java")
        self.assertIn("com.restless.payment.*", patterns)

    def test_test_overriding_a_bean_by_name_counts_as_use(self):
        self.java(MAIN, "credit", "class CreditChargeHandler {}")
        self.java(TEST, "drop", '@Bean("creditChargeHandler") Object fake() { return null; }')
        scope, patterns = self.select(f"{MAIN}/credit/A.java")
        self.assertIn("com.restless.drop.*", patterns)


class FullModulesTest(Fixture):

    """실제 FULL_MODULES 설정으로 본다. setUp이 비운 값을 되돌린다."""

    def setUp(self):
        super().setUp()
        select_tests.FULL_MODULES = self.real_full_modules

    def test_drop_main_and_test_change_runs_everything(self):
        self.assertEqual(self.select(f"{MAIN}/drop/A.java"), ("full", []))
        self.assertEqual(self.select(f"{TEST}/drop/A.java"), ("full", []))
        self.assertEqual(self.select("docs/a.md", f"{TEST}/drop/acceptance/A.java"), ("full", []))

    def test_order_and_payment_are_shared_so_full(self):
        for m in ("order", "payment"):
            self.assertIn(m, select_tests.SHARED)

    def test_other_domains_stay_selected(self):
        self.assertEqual(self.select(f"{MAIN}/credit/A.java")[0], "partial")

    def test_drop_is_not_full_for_docs_or_perf_only(self):
        self.assertEqual(self.select("docs/domains/drop/policy.md", "perf/scenarios/drop-sale.js"), ("none", []))

    def test_configured_modules(self):
        self.assertEqual(self.real_full_modules, {"drop"})


REPO = os.path.abspath(os.path.join(HERE, "..", ".."))


@unittest.skipUnless(os.path.isdir(os.path.join(REPO, MAIN)), "backend 소스가 없음")
class RealRepoTest(unittest.TestCase):
    """실제 코드 기준. 모듈 사이 결합이 바뀌면 이 기대값도 다시 본다."""

    def patterns(self, path):
        scope, patterns, _ = select_tests.select([path], REPO)
        return scope, set(patterns)

    def test_drop_change_runs_everything(self):
        # 드롭은 항상 전체다. 모듈 사이 결합을 따진 선택 실행은 다른 도메인에서 본다.
        self.assertEqual(self.patterns(f"{MAIN}/drop/application/DropPaymentResultHandler.java")[0], "full")
        self.assertEqual(self.patterns(f"{TEST}/drop/acceptance/DropQueueAcceptanceTest.java")[0], "full")

    def test_non_shared_domain_still_selects(self):
        scope, patterns = self.patterns(f"{MAIN}/auction/application/AuctionService.java")
        self.assertEqual(scope, "partial")
        self.assertTrue(set(ALWAYS) <= patterns)

    def test_shared_modules_run_everything(self):
        for m in sorted(select_tests.SHARED):
            self.assertEqual(self.patterns(f"{MAIN}/{m}/X.java")[0], "full", m)


if __name__ == "__main__":
    unittest.main()
