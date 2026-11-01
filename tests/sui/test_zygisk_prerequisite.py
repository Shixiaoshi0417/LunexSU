"""Host source guards; the Rust harness exercises the real filesystem detector."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu"
RUST = ROOT / "userspace/ksud/src/sui"


class ZygiskPrerequisiteTests(unittest.TestCase):
    def test_popup_cannot_enable_either_feature(self):
        source = (MAIN / "ui/screen/sui/SuiScreen.kt").read_text()
        branch = source.split("if (enable && !ui.status.zygisk.ready)", 1)[1].split("return", 1)[0]
        self.assertIn("R.string.sui_zygisk_required", branch)
        self.assertIn("R.string.sui_zygisk_instructions", branch)
        self.assertIn("SuiManager.refresh(false)", branch)
        for mutation in ("SuiManager.enable", "SuiManager.prepare", "BuiltinZygisk", "setZygisk"):
            self.assertNotIn(mutation, branch)
        self.assertIn("LaunchedEffect(enable) { SuiManager.refresh(false) }", source)

    def test_fresh_status_is_checked_before_any_preparation(self):
        source = (MAIN / "data/sui/SuiManager.kt").read_text()
        enable = source.split("fun enable()", 1)[1].split("fun disable()", 1)[0]
        self.assertLess(enable.index('command("status")'), enable.index('command("prepare")'))
        self.assertLess(enable.index("check(latest.zygisk.ready)"), enable.index('command("prepare")'))
        self.assertLess(enable.index('command("prepare")'), enable.index('command("enable")'))
        self.assertIn("finally { refreshLater() }", enable)

    def test_status_and_enable_share_the_same_read_only_detector(self):
        status = (RUST / "mod.rs").read_text()
        self.assertIn("provider::inspect(", status)
        self.assertLess(status.index('value["zygisk"]'), status.index("if unsafe { libc::geteuid() } == 0 && status.payload_available"))
        runtime = (RUST / "runtime.rs").read_text()
        provider = runtime.split("fn provider()", 1)[1].split("fn ", 1)[0]
        self.assertIn("super::provider::inspect(", provider)
        self.assertIn(".require_ready()", provider)
        self.assertGreaterEqual(runtime.count("provider()?"), 2)
        detector = (RUST / "provider.rs").read_text()
        for forbidden in (".lunexsu_builtin_zygisk", "create_dir", "remove_file", "Command::", "zygisk/", "pidof"):
            self.assertNotIn(forbidden, detector)
        self.assertIn('const IDS: [&str; 2] = ["rezygisk", "zygisksu"]', detector)
        self.assertIn("libc::AT_SYMLINK_NOFOLLOW", detector)

    def test_clickability_and_enable_eligibility_are_separate(self):
        source = (MAIN / "data/sui/SuiModels.kt").read_text()
        self.assertIn("(!enabled || status.zygisk.ready)", source)
        clickability = source.split("val switchEnabled:", 1)[1].split("fun canSwitchTo", 1)[0]
        self.assertNotIn("zygisk.ready", clickability)

    def test_demo_uses_only_the_in_memory_provider_state_and_the_same_gate(self):
        demo = (ROOT / "manager/app/src/noRootDemo/java/com/shixiaoshi0417/lunexsu/data/sui/SuiManager.kt").read_text()
        self.assertIn("BuiltinZygisk.demoState", demo)
        self.assertIn("if (!checked.canSwitchTo(true)) checked", demo)
        for forbidden in ("withRootShell", "getKsuDaemonPath", "/data/adb", "command("):
            self.assertNotIn(forbidden, demo)

    def test_every_sui_locale_explains_external_support_and_non_ready_states(self):
        keys = {"sui_zygisk_required", "sui_zygisk_missing", "sui_zygisk_pending",
                "sui_zygisk_conflict", "sui_zygisk_unavailable", "sui_zygisk_instructions", "sui_refresh"}
        for locale in ("values", "values-zh-rCN", "values-zh-rHK"):
            strings = {item.get("name"): item.text for item in ET.parse(
                ROOT / "manager/app/src/main/res" / locale / "sui.xml").getroot()}
            self.assertFalse(keys - strings.keys())
            for provider in ("ReZygisk", "NeoZygisk", "Zygisk Next"):
                self.assertIn(provider, strings["sui_zygisk_instructions"])


if __name__ == "__main__":
    unittest.main()
