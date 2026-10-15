"""Exercise the Manager's production rollback shell template without Android or root."""

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu/data/zygisk/ZygiskBackend.kt"
OWNER = ".lunexsu_builtin_zygisk"


def discard_script(adb: Path, module_id: str = "zygisksu") -> str:
    source = SOURCE.read_text(encoding="utf-8")
    function = source.split("internal fun zygiskDiscardInstallScript(", 1)[1]
    template = re.search(r'return """(.*?)"""\.trimIndent\(\)', function, re.S).group(1)
    return textwrap.dedent(template).replace("${'$'}", "$").replace(
        "$ZYGISK_OWNER_MARKER", OWNER
    ).replace("$id", module_id).replace("/data/adb", str(adb)).strip()


class ZygiskRollbackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lunexsu-zygisk-")
        self.addCleanup(self.temp.cleanup)
        self.adb = Path(self.temp.name)
        self.staged = self.adb / "modules_update/zygisksu"
        self.live = self.adb / "modules/zygisksu"
        self.old = self.adb / "modules/rezygisk"
        self.old.mkdir(parents=True)
        (self.old / OWNER).touch()

    def payload(self, directory: Path):
        directory.mkdir(parents=True)
        (directory / "module.prop").write_text("id=zygisksu\n")
        (directory / "post-fs-data.sh").write_text("exit 99\n")
        (directory / OWNER).touch()

    def run_discard(self, prefix=""):
        result = subprocess.run(
            ["sh", "-c", prefix + discard_script(self.adb)], capture_output=True, text=True
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.old / OWNER).exists())
        self.assertFalse((self.old / "remove").exists())
        self.assertFalse((self.old / "disable").exists())

    def assert_quarantined(self, directory):
        self.assertTrue((directory / "remove").exists())
        self.assertTrue((directory / "disable").exists())
        self.assertFalse((directory / OWNER).exists())
        self.assertTrue((directory / "post-fs-data.sh").exists())

    def test_early_failure_with_no_live_placeholder_is_quarantined(self):
        self.payload(self.staged)
        self.run_discard()
        self.assertFalse(self.live.exists())
        self.assert_quarantined(self.staged)

    def test_failure_after_placeholder_creation_marks_both_copies(self):
        self.payload(self.staged)
        self.payload(self.live)
        self.run_discard()
        self.assert_quarantined(self.staged)
        self.assert_quarantined(self.live)

    def test_failure_before_extraction_is_a_noop(self):
        self.run_discard()
        self.assertFalse(self.live.exists())
        self.assertFalse(self.staged.exists())

    def test_live_only_and_repeated_cleanup_are_safe(self):
        self.payload(self.live)
        self.run_discard()
        self.run_discard()
        self.assert_quarantined(self.live)

    def test_boot_promotion_retains_marks_and_uninstaller(self):
        self.payload(self.staged)
        hook = self.adb / "installed-hook"
        hook.touch()
        (self.staged / "uninstall.sh").write_text(f'rm -f "{hook}"\n')
        self.run_discard()
        # Mirror handle_updated_modules' rename, followed by prune_modules before lifecycle scripts.
        self.live.parent.mkdir(exist_ok=True)
        self.staged.rename(self.live)
        self.assert_quarantined(self.live)
        subprocess.run(["sh", str(self.live / "uninstall.sh")], check=True)
        shutil.rmtree(self.live)
        self.assertFalse(hook.exists())
        self.assertTrue(self.old.exists())
        init = (ROOT / "userspace/ksud/src/init_event.rs").read_text(encoding="utf-8")
        self.assertLess(init.index("handle_updated_modules()"), init.index("prune_modules()"))
        self.assertLess(init.index("prune_modules()"), init.index('exec_stage_script("post-fs-data"'))

    def test_cleanup_io_error_is_not_reported_as_success(self):
        self.payload(self.staged)
        result = subprocess.run(
            ["sh", "-c", "touch() { return 1; };\n" + discard_script(self.adb)],
            capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
