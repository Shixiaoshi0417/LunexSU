"""Only a successfully selected revision may modify a kernel integration tree."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class SetupRefTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.kernel = self.base / "kernel"
        self.drivers = self.kernel / "drivers"
        self.drivers.mkdir(parents=True)
        (self.drivers / "Makefile").write_text("# fixture\n")
        (self.drivers / "Kconfig").write_text('menu "fixture"\nendmenu\n')
        self.origin = self.base / "origin"
        self.origin.mkdir()
        self.git("init", "-b", "main", cwd=self.origin)
        (self.origin / "kernel").mkdir()
        (self.origin / "kernel/Kconfig").write_text("# fixture\n")
        self.git("add", ".", cwd=self.origin)
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "fixture", cwd=self.origin)
        self.git("tag", "selected", cwd=self.origin)
        self.git("clone", str(self.origin), str(self.kernel / "LunexSU"))
        # Windows checkouts use CRLF; exercise the same LF bytes Git supplies to CI.
        self.script = self.base / "setup.sh"
        self.script.write_bytes((ROOT / "kernel/setup.sh").read_bytes().replace(b"\r\n", b"\n"))

    def git(self, *args, cwd=None):
        return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

    def run_setup(self, reference, env=None):
        return subprocess.run(["sh", str(self.script), reference], cwd=self.kernel,
                              env=env, capture_output=True, text=True)

    def assert_unmodified(self):
        self.assertEqual((self.drivers / "Makefile").read_text(), "# fixture\n")
        self.assertEqual((self.drivers / "Kconfig").read_text(), 'menu "fixture"\nendmenu\n')
        self.assertFalse((self.drivers / "kernelsu").exists())
        self.assertFalse((self.drivers / "kernelsu").is_symlink())

    def test_missing_ref_fails_before_integration(self):
        result = self.run_setup("does-not-exist")
        self.assertNotEqual(result.returncode, 0)
        self.assert_unmodified()

    def test_ref_cannot_be_a_git_option(self):
        self.assertNotEqual(self.run_setup("--all").returncode, 0)
        self.assert_unmodified()

    def test_unfetchable_ref_does_not_use_existing_local_tag(self):
        self.git("tag", "-d", "selected", cwd=self.origin)
        self.assertNotEqual(self.run_setup("selected").returncode, 0)
        self.assert_unmodified()

    def test_checkout_failure_stops_before_integration(self):
        wrapper = self.base / "bin"
        wrapper.mkdir()
        real_git = shutil.which("git")
        (wrapper / "git").write_text('#!/bin/sh\nif [ "$1" = checkout ]; then exit 77; fi\nexec "' + real_git + '" "$@"\n')
        (wrapper / "git").chmod(0o755)
        result = self.run_setup("selected", dict(os.environ, PATH=str(wrapper) + os.pathsep + os.environ["PATH"]))
        self.assertNotEqual(result.returncode, 0)
        self.assert_unmodified()

    def test_valid_ref_integrates_exact_commit(self):
        result = self.run_setup("selected")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.git("rev-parse", "HEAD", cwd=self.kernel / "LunexSU"),
                         self.git("rev-parse", "selected", cwd=self.origin))
        self.assertTrue((self.drivers / "kernelsu").is_symlink())
        self.assertIn("CONFIG_KSU", (self.drivers / "Makefile").read_text())

    def test_dirty_checkout_is_preserved_and_not_stashed(self):
        source = self.kernel / "LunexSU/kernel/Kconfig"
        source.write_text("# local work\n")
        self.assertNotEqual(self.run_setup("selected").returncode, 0)
        self.assertEqual(source.read_text(), "# local work\n")
        self.assertEqual(self.git("stash", "list", cwd=self.kernel / "LunexSU"), "")
        self.assert_unmodified()


if __name__ == "__main__":
    unittest.main(verbosity=2)
