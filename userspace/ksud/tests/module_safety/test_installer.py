"""Run the real installer with fake extraction/Android commands in local fixtures.

Run with Python 3 on Linux/WSL; all files stay beside this test under fixtures/.
No Android device, root, mount, SELinux, or real module installation is used.
"""

import ctypes
import errno
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
INSTALLER = HERE.parent.parent / "src" / "installer.sh"


class InstallerTransactions(unittest.TestCase):
    def test_normalized_installer_shell_syntax(self):
        # Match the LF Git blob, even in an autocrlf Windows checkout.
        result = subprocess.run(
            ["sh", "-n"], input=INSTALLER.read_text(), text=True, capture_output=True
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_directory_exchange_is_atomic_or_fails_without_losing_old_payload(self):
        first = self.root / "new-payload"
        second = self.root / "working-payload"
        first.mkdir()
        second.mkdir()
        (first / "version").write_text("new")
        (second / "version").write_text("old")
        libc = ctypes.CDLL(None, use_errno=True)
        if not hasattr(libc, "renameat2"):
            self.skipTest("Host libc does not expose renameat2")
        exchange = libc.renameat2
        exchange.argtypes = [
            ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint
        ]
        exchange.restype = ctypes.c_int
        result = exchange(-100, os.fsencode(first), -100, os.fsencode(second), 2)
        if result == 0:
            self.assertEqual((second / "version").read_text(), "new")
            self.assertEqual((first / "version").read_text(), "old")
        else:
            self.assertIn(ctypes.get_errno(), [errno.EINVAL, errno.ENOSYS, errno.EOPNOTSUPP])
            self.assertEqual((second / "version").read_text(), "old")
            self.assertEqual((first / "version").read_text(), "new")

    def setUp(self):
        fixtures = HERE / "fixtures"
        fixtures.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="installer-", dir=fixtures)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.live = self.root / "adb" / "modules" / "example"
        self.pending = self.root / "adb" / "modules_update" / "example"
        self.staged = self.root / "private" / "modules" / "example"
        self.live.mkdir(parents=True)
        self.pending.mkdir(parents=True)
        (self.live / "module.prop").write_text("id=example\nversion=old\n")
        (self.live / "disable").touch()
        (self.live / "remove").touch()
        (self.pending / "version").write_text("previous successful update")
        (self.root / "module.prop").write_text("id=example\nname=Example\nauthor=Test\n")

    def run_installer(
        self, customize="true\n", extract_fails=False, legacy=None, module_id="example"
    ):
        (self.root / "customize.sh").write_text(customize)
        if legacy is not None:
            (self.root / "install.sh").write_text(legacy)
        script = """
. INSTALLER_PATH
NVBASE="$FIXTURE/adb"
# Only these fake Android/extraction commands are used by install_module.
mount_partitions() { :; }
api_level_arch_detect() { :; }
chcon() { :; }
set_perm_recursive() { :; }
handle_partition() { :; }
check_managed_features() { :; }
ui_print() { :; }
print_title() { :; }
is_legacy_script() { [ "$LEGACY" = true ]; }
grep_prop() {
  case "$1" in id) echo "$FAKE_MODID";; name) echo Example;; author) echo Test;; esac
}
unzip() {
  case "$*" in
    *install.sh*) cp "$FIXTURE/install.sh" "$TMPDIR/install.sh";;
    *" module.prop "*) cp "$FIXTURE/module.prop" "$TMPDIR/module.prop";;
    *" customize.sh "*) cp "$FIXTURE/customize.sh" "$MODPATH/customize.sh";;
    *)
      [ "$FAIL_EXTRACT" = true ] && return 9
      cp "$FIXTURE/module.prop" "$MODPATH/module.prop"
      ;;
  esac
}
install_module
""".replace(". INSTALLER_PATH", INSTALLER.read_text())
        environment = dict(os.environ)
        environment.update(
            FIXTURE=str(self.root),
            BOOTMODE="true",
            OUTFD="1",
            ZIPFILE=str(self.root / "fake.zip"),
            KSU_MODULE_STAGING_ROOT=str(self.staged.parent),
            KSU_MODULE="example",
            FAKE_MODID=module_id,
            KSU_INSTALL_TMPDIR=str(self.root / "private" / "tmp"),
            FAIL_EXTRACT=str(extract_fails).lower(),
            LEGACY=str(legacy is not None).lower(),
        )
        return subprocess.run(
            ["sh", "-c", script], env=environment, capture_output=True, timeout=10
        )

    def assert_live_untouched(self):
        self.assertEqual(
            (self.live / "module.prop").read_text(), "id=example\nversion=old\n"
        )
        self.assertTrue((self.live / "disable").exists())
        self.assertTrue((self.live / "remove").exists())
        self.assertFalse((self.live / "update").exists())
        self.assertEqual(
            (self.pending / "version").read_text(), "previous successful update"
        )

    def test_extraction_failure_aborts_without_touching_live_or_pending(self):
        result = self.run_installer(extract_fails=True)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse(self.staged.exists())
        self.assert_live_untouched()

    def test_plain_nonzero_exit_stays_nonpromotable(self):
        result = self.run_installer(customize="exit 7\n")
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assert_live_untouched()

    def test_nonzero_customization_return_is_not_masked(self):
        result = self.run_installer(customize="false\n")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse(self.staged.exists())
        self.assert_live_untouched()

    def test_abort_removes_only_private_module(self):
        result = self.run_installer(customize="abort 'fake module failure'\n")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse(self.staged.exists())
        self.assert_live_untouched()

    def test_legacy_callback_failure_is_not_masked(self):
        result = self.run_installer(
            legacy="print_modname() { :; }\non_install() { return 9; }\n"
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse(self.staged.exists())
        self.assert_live_untouched()

    def test_second_parser_cannot_redirect_module_path(self):
        result = self.run_installer(module_id="../../escaped")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertFalse((self.root / "private" / "escaped").exists())
        self.assert_live_untouched()

    def test_success_and_hot_request_remain_private_until_rust_publication(self):
        result = self.run_installer(
            customize='touch "$MODPATH/completed"\nMODULE_HOT_INSTALL_REQUEST=true\n'
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.staged / "completed").is_file())
        self.assertTrue((self.staged / ".ksu_hot_install").is_file())
        self.assert_live_untouched()


if __name__ == "__main__":
    unittest.main()
