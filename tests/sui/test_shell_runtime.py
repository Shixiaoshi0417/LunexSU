"""Attack the root starter's shell runtime file helpers with real links, FIFOs and directories.

UID 2000 owns /data/local/tmp and the Sui shell runtime directory, so it can replace any entry
there between two root operations. The production C++ helpers are compiled for the host and every
scenario checks that nothing outside the runtime directory is read, written, chmodded or removed.
"""
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
UTIL = ROOT / "userspace/sui/module/src/main/cpp/util"
DRIVER = Path(__file__).with_name("shell_runtime_check.cpp")
SUI_MAIN = ROOT / "userspace/sui/module/src/main/cpp/main/sui_main.hpp"
CXX = shutil.which("g++") or shutil.which("clang++")


@unittest.skipUnless(os.name == "posix" and CXX, "needs POSIX links/FIFOs and a host C++ compiler")
class ShellRuntimeHelperTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix="sui-shell-runtime-")
        cls.binary = Path(cls.build.name) / "check"
        subprocess.run([CXX, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-I", str(UTIL / "include"),
                        str(UTIL / "shell_runtime.cpp"), str(DRIVER), "-o", str(cls.binary)], check=True)
        listed = subprocess.run([str(cls.binary), "-", "list"], check=True, capture_output=True, text=True)
        cls.scenarios = listed.stdout.split()

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def test_every_scenario_keeps_outside_files_intact(self):
        self.assertEqual(9, len(self.scenarios))
        for scenario in self.scenarios:
            with self.subTest(scenario), tempfile.TemporaryDirectory(prefix="sui-shell-attack-") as work:
                result = subprocess.run([str(self.binary), work, scenario], capture_output=True, text=True,
                                        timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("ok", result.stdout.strip())


class ShellRuntimeWiringTests(unittest.TestCase):
    """The starter must reach shell-owned entries only through the descriptor helpers."""

    def test_starter_never_writes_shell_entries_by_path(self):
        source = SUI_MAIN.read_text(encoding="utf-8")
        for unsafe in ("copyfile", "ensure_dir", "opendir", "getfilecon_raw", "setfilecon_raw"):
            self.assertIsNone(re.search(r"(?<![\w])" + unsafe + r"\(", source), unsafe)
        # The only path-based chmod/chown left is the root-private /data/adb/sui directory.
        self.assertEqual(1, len(re.findall(r"(?<![\w])chmod\(", source)))
        self.assertEqual(1, len(re.findall(r"(?<![\w])chown\(", source)))
        self.assertIn("chmod(SUI_DATA_DIR, 0700);", source)
        self.assertIn("chown(SUI_DATA_DIR, 0, 0);", source)
        for helper in ("shell_runtime_replace_file(", "shell_runtime_open_or_create_dir(",
                       "shell_runtime_remove_dir(", "shell_runtime_open_regular(",
                       "fsetfilecon_raw(", "fchown(dir_fd", "fchmod(dir_fd"):
            self.assertIn(helper, source)

    def test_helpers_are_built_into_the_module(self):
        cmake = (ROOT / "userspace/sui/module/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertIn("util/shell_runtime.cpp", cmake)


if __name__ == "__main__":
    unittest.main()
