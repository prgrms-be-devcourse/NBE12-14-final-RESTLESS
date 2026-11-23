"""Check distribution against the installed personal harness without changing it."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCH = ROOT / "tools/verification/integration.patch"


class IntegrationPatchTest(unittest.TestCase):
    def test_personal_harness_reverse_and_forward_round_trip(self):
        paths = re.findall(r"^\+\+\+ b/(.+)$", PATCH.read_text(), re.MULTILINE)
        self.assertTrue(paths)
        self.assertTrue(all(p.startswith("tools/harness/") and ".." not in Path(p).parts for p in paths))
        missing = [p for p in paths if not (ROOT / p).is_file()]
        if missing:
            self.skipTest("Personal harness is not installed: " + ", ".join(missing))
        with tempfile.TemporaryDirectory() as folder:
            workspace = Path(folder)
            originals = {}
            for name in paths:
                target = workspace / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, target)
                originals[name] = target.read_bytes()
            # Only disposable copies are changed, never the installed guard.
            for args in (["--reverse", "--check"], ["--reverse"], ["--check"], []):
                result = subprocess.run(["git", "apply", *args, str(PATCH)], cwd=workspace,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
            for name, expected in originals.items():
                self.assertEqual((workspace / name).read_bytes(), expected, name)


if __name__ == "__main__":
    unittest.main()
