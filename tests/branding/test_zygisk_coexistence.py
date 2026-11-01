"""Host-only fixtures for the production status shell and boot/UI wiring."""
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu"
OWNER = ".lunexsu_builtin_zygisk"


def status_script(adb):
    source = (MAIN / "data/zygisk/ZygiskBackend.kt").read_text()
    body = source.split("internal fun zygiskStatusScript()", 1)[1].split("internal sealed interface ZygiskStep", 1)[0]
    lines = [json.loads('"' + value.replace(r'\$', '$') + '"')
             for value in re.findall(r'appendLine\("((?:[^"\\]|\\.)*)"\)', body)]
    per_module, monitor = lines[:-1], lines[-1]
    return "pidof() { return 1; };\n" + "\n".join(
        line.replace("$ZYGISK_OWNER_MARKER", OWNER).replace("$id", module_id)
            .replace("/data/adb", str(adb))
        for module_id in ("rezygisk", "zygisksu", "zygisknextsu") for line in per_module
    ) + "\n" + monitor


class ZygiskCoexistenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="lunexsu-zygisk-coexistence-")
        self.addCleanup(self.temp.cleanup)
        self.adb = Path(self.temp.name)

    def module(self, root="modules", owned=True):
        path = self.adb / root / "rezygisk"
        path.mkdir(parents=True)
        if owned:
            (path / OWNER).touch()
        return path

    def flags(self):
        result = subprocess.run(["sh", "-c", status_script(self.adb)], text=True,
                                capture_output=True, timeout=10, check=True)
        return next(line.split()[2:] for line in result.stdout.splitlines()
                    if line.startswith("module rezygisk "))

    def test_owned_live_and_staged_copies_are_consistent(self):
        self.module()
        self.module("modules_update")
        self.assertEqual(self.flags(), ["1", "1", "1", "0", "0", "0", "0"])

    def test_foreign_copy_is_not_adopted_by_an_owned_other_copy(self):
        for owned_root in ("modules", "modules_update"):
            with self.subTest(owned_root=owned_root):
                owned = self.module(owned_root)
                other_root = "modules_update" if owned_root == "modules" else "modules"
                other = self.module(other_root, False)
                self.assertEqual(self.flags()[-1], "1")
                self.assertFalse((other / OWNER).exists())
                (owned / OWNER).unlink()
                owned.rmdir()
                other.rmdir()

    def test_symlink_owner_and_directory_are_not_ownership(self):
        path = self.module(owned=False)
        sentinel = self.adb / "owner"
        sentinel.touch()
        (path / OWNER).symlink_to(sentinel)
        self.assertEqual(self.flags()[-1], "1")
        (path / OWNER).unlink()
        path.rmdir()
        path.symlink_to(self.adb, target_is_directory=True)
        self.assertEqual(self.flags()[-1], "1")

    def test_dangling_symlink_and_plain_file_still_occupy_the_id(self):
        path = self.module(owned=False)
        path.rmdir()
        path.symlink_to(self.adb / "absent")
        self.assertEqual((self.flags()[0], self.flags()[-1]), ("1", "1"))
        path.unlink()
        path.write_text("not a module directory")
        self.assertEqual((self.flags()[0], self.flags()[-1]), ("1", "1"))

    def test_boot_guard_precedes_module_policy_and_startup(self):
        init = (ROOT / "userspace/ksud/src/init_event.rs").read_text()
        self.assertLess(init.index("handle_updated_modules()"), init.index("zygisk::reconcile("))
        self.assertLess(init.index("zygisk::reconcile("), init.index("module::load_sepolicy_rule()"))
        self.assertLess(init.index("zygisk::reconcile("), init.index('exec_stage_script("post-fs-data"'))
        module = (ROOT / "userspace/ksud/src/module.rs").read_text()
        self.assertIn("zygisk::active_allowed(", module)
        regenerate = module.split("pub fn regenerate_preinit()", 1)[1]
        self.assertLess(regenerate.index("zygisk::filter_next_boot("), regenerate.index("regenerate_preinit_rc("))
        self.assertLess(regenerate.index("zygisk::filter_next_boot("), regenerate.index("preinit::sync("))

    def test_enable_requires_visible_confirmation_and_background_dismissal(self):
        vm = (MAIN / "ui/viewmodel/SettingsViewModel.kt").read_text()
        enable = vm.split("fun setZygiskEnabled(", 1)[1].split("fun dismissZygiskEnableDialog", 1)[0]
        self.assertIn("showZygiskEnableDialog = true", enable)
        self.assertNotIn("applyZygisk(BuiltinZygisk.defaultBackend", enable)
        confirm = vm.split("fun confirmZygiskEnable()", 1)[1].split("fun setZygiskBackend", 1)[0]
        self.assertLess(confirm.index("dismissZygiskEnableDialog()"), confirm.index("applyZygisk("))
        self.assertIn("if (!state.canConfirmZygiskEnable) return", confirm)
        self.assertIn("applyZygisk(state.zygiskSelected)", confirm)
        screen = (MAIN / "ui/screen/settings/SettingsScreen.kt").read_text()
        self.assertGreaterEqual(screen.count("dismissZygiskEnableDialog()"), 2)
        self.assertIn("ZygiskEnableDialog(uiState, actions)", screen)

    def test_both_dialog_styles_use_the_same_guard_and_translations(self):
        dialog = (MAIN / "ui/screen/settings/SettingsZygiskEnableDialog.kt").read_text()
        for token in ("UiMode.Miuix", "WindowDialog(", "ExpressiveDialog(", "FolkTextButton(",
                      "state.canConfirmZygiskEnable", "verticalScroll("):
            self.assertIn(token, dialog)
        for locale in ("values", "values-zh-rCN", "values-zh-rHK"):
            strings = (ROOT / "manager/app/src/main/res" / locale / "strings.xml").read_text()
            self.assertIn('name="settings_zygisk_enable_warning"', strings)

    def test_ownership_mutations_refresh_next_boot_snapshot(self):
        source = (MAIN / "data/zygisk/BuiltinZygisk.kt").read_text()
        self.assertGreaterEqual(source.count('ksud("initrc refresh")'), 3)
        self.assertLess(source.index(".conflictFor(target, nextNext)"), source.index("val steps = planZygiskChange"))
        self.assertIn("Incomplete Zygisk module ownership", source)


if __name__ == "__main__":
    unittest.main()
