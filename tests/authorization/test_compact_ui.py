"""Host-only guards for compact duration controls; no Android/device commands."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu/ui"


class CompactAuthorizationUiTests(unittest.TestCase):
    def test_duration_follows_the_superuser_switch_in_both_styles(self):
        component = (MAIN / "component/profile/AuthorizationConfig.kt").read_text()
        self.assertLess(component.index("superuserSwitch(!busy"), component.index("Column(Modifier"))
        for name in ("AppProfileMaterial.kt", "AppProfileMiuix.kt"):
            source = (MAIN / "screen/appprofile" / name).read_text()
            self.assertEqual(source.count("AuthorizationConfig("), 1)
            content = source.split("AuthorizationConfig(", 1)[1]
            following = "R.string.profile" if name == "AppProfileMaterial.kt" else "ProfileBox("
            self.assertLess(content.index("R.string.superuser"), content.index(following))

    def test_no_separate_status_description_or_grant_controls(self):
        component = (MAIN / "component/profile/AuthorizationConfig.kt").read_text()
        for resource in ("authorization_grant", "authorization_revoke", "authorization_renew",
                         "authorization_unauthorized", "authorization_description", "authorization_remaining"):
            self.assertNotIn("R.string." + resource, component)
        for token in ("FlowRow(", "OverlayDropdownPreference(", "ImeAction.Done", "KeyboardActions(onDone"):
            self.assertIn(token, component)

    def test_off_selection_is_a_draft_not_an_implicit_grant(self):
        component = (MAIN / "component/profile/AuthorizationConfig.kt").read_text()
        select = component.split("fun select(", 1)[1].split("superuserSwitch(", 1)[0]
        self.assertIn("authorizationSelectionUpdate(profile, mode, duration)", select)
        self.assertNotIn("authorizationGrant(", select)
        repository = (MAIN / "screen/appprofile/AuthorizationRepository.kt").read_text()
        selection = repository.split("fun authorizationSelectionUpdate(", 1)[1]
        self.assertIn("!profile.allowSu", selection)
        self.assertIn("candidate.authorizationMode == profile.authorizationMode", selection)
        self.assertIn("candidate.authorizationDurationSeconds == profile.authorizationDurationSeconds", selection)

    def test_fixture_uses_switch_and_keyboard_instead_of_removed_buttons(self):
        source = (ROOT / "manager/app/src/androidTest/java/com/shixiaoshi0417/lunexsu/AuthorizationUiDeviceTests.kt").read_text()
        for removed in ("Grant / apply", "Renew from now", 'click("Revoke"'):
            self.assertNotIn(removed, source)
        self.assertIn('putExtra("denied", true)', source)
        self.assertIn("ACTION_IME_ENTER", source)
        self.assertIn('getInt("writes", -1) == 0', source)


if __name__ == "__main__":
    unittest.main()
