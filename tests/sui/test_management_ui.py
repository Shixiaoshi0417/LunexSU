"""Source guards for the Sui switch/management split; these are not device layout tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu/ui"
RES = ROOT / "manager/app/src/main/res"


def read(path):
    return (UI / path).read_text(encoding="utf-8")


class SuiManagementUiTests(unittest.TestCase):
    def test_settings_switch_sits_after_zygisk_and_replaces_the_old_entry(self):
        for page, card in (("SettingsMaterial.kt", "SuiCardMaterial("), ("SettingsMiuix.kt", "SuiCardMiuix(")):
            with self.subTest(page=page):
                source = read("screen/settings/" + page)
                self.assertLess(source.index("ZygiskCard"), source.index(card))
                self.assertEqual(1, source.count("actions.onOpenSui"))
                self.assertNotIn("sui_separate_permissions", source)

    def test_management_entry_appears_only_when_switched_on(self):
        material = read("screen/settings/SettingsSuiMaterial.kt")
        self.assertIn("item(visible = ui.managementVisible)", material)
        self.assertIn("if (SuiManager.state.value.managementVisible) onOpenSui()", material)
        miuix = read("screen/settings/SettingsSuiMiuix.kt")
        self.assertIn("if (ui.managementVisible) {", miuix)
        for source in (material, miuix):
            self.assertIn("checked = ui.switchChecked", source)
            self.assertIn("enabled = ui.switchEnabled", source)
            self.assertIn("onCheckedChange = { request = it }", source)
            self.assertIn("SuiSwitchDialog(request, ui) { request = null }", source)
            self.assertIn("rememberSuiSwitchRequest()", source)
        shared = read("screen/settings/SettingsSui.kt")
        self.assertIn("onPauseOrDispose { request.value = null }", shared)

    def test_switch_confirmation_is_consumed_and_rechecked(self):
        screen = read("screen/sui/SuiScreen.kt")
        dialog = screen.split("internal fun SuiSwitchDialog(", 1)[1].split("private fun SuiSearchField", 1)[0]
        self.assertIn("R.string.sui_enable_warning else R.string.sui_disable_warning", dialog)
        self.assertIn("enabled = ui.canSwitchTo(enable)", dialog)
        self.assertLess(dialog.index("onDismiss()"), dialog.index("SuiManager.state.value.canSwitchTo(enable)"))
        self.assertLess(dialog.index("SuiManager.state.value.canSwitchTo(enable)"), dialog.index("SuiManager.enable()"))
        # The management page itself no longer toggles the broker.
        page = screen.split("fun SuiScreen()", 1)[1].split("internal fun suiStatusText(", 1)[0]
        for forbidden in ("SuiManager.enable()", "SuiManager.disable()", "R.string.sui_enable"):
            self.assertNotIn(forbidden, page)

    def test_pages_use_the_shared_theme_bars_and_collapse_with_the_list(self):
        material = read("screen/sui/SuiScreenMaterial.kt")
        self.assertEqual(2, material.count("colors = expressiveTopAppBarColors()"))
        self.assertIn("ExpressiveScaffold(", material)
        self.assertIn("TopAppBarDefaults.pinnedScrollBehavior", material)
        self.assertIn("TopAppBarDefaults.exitUntilCollapsedScrollBehavior", material)
        self.assertIn(".nestedScroll(scrollBehavior.nestedScrollConnection)", material)
        self.assertIn("SegmentedItem(index = index, count = apps.size)", material)
        miuix = read("screen/sui/SuiScreenMiuix.kt")
        for token in ("MiuixScrollBehavior()", "BlurredBar(backdrop)", "Modifier.layerBackdrop(backdrop)",
                      ".nestedScroll(scrollBehavior.nestedScrollConnection)", "contentPadding = inner",
                      "windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.captionBar))"):
            self.assertIn(token, miuix)
        self.assertLess(miuix.index(".imePadding()"), miuix.index(".nestedScroll("))
        screen = read("screen/sui/SuiScreen.kt")
        self.assertIn("SuiScreenMiuix(", screen)
        self.assertIn("SuiScreenMaterial(", screen)
        susfs = read("screen/susfs/SusfsComponents.kt")
        self.assertIn("colors = expressiveTopAppBarColors())", susfs)

    def test_new_strings_exist_in_every_sui_locale(self):
        keys = {"sui_switch_title", "sui_switch_summary", "sui_payload_missing", "sui_apps", "sui_request_dialog"}
        for locale in ("values", "values-zh-rCN", "values-zh-rHK"):
            with self.subTest(locale=locale):
                names = {e.get("name") for e in ET.parse(RES / locale / "sui.xml").getroot()}
                self.assertFalse(keys - names)


if __name__ == "__main__":
    unittest.main()
