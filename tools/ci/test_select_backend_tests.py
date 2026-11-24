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


class SelectTest(unittest.TestCase):
    def setUp(self):
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


if __name__ == "__main__":
    unittest.main()
