"""Every Manager locale carries the LunexSU strings with the English format arguments."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "manager/app/src/main/res"
FORMAT = re.compile(r"%(?:\d+\$)?[a-zA-Z]")
# Strings added by LunexSU (authorization, themes, fonts, home layouts, bundled LKMs).
LUNEXSU_KEYS = """about_ui_source authorization_boot_scope authorization_busy authorization_custom_minutes
authorization_grant authorization_inactive authorization_invalid_duration authorization_minutes authorization_mode
authorization_once authorization_permanent authorization_permanent_only authorization_read_failed
authorization_remaining authorization_renew authorization_revoke authorization_revoked authorization_saved
authorization_timed font_applied font_apply_failed font_mode_custom font_mode_custom_summary font_mode_system
font_preview font_restore_default font_restore_default_confirm font_restored font_select_file font_title
home_metric_battery_level home_metric_battery_temperature home_metric_cpu_frequency home_metric_cpu_temperature
home_metric_storage_internal home_metric_storage_ram home_running_mode home_running_status home_state_on
home_tile_device home_tile_kernel home_tile_monitor home_tile_stats home_tile_storage home_tile_system home_version
install_load_kmis_failed install_loading_kmis install_no_bundled_lkm install_no_matching_lkm metric_unavailable
settings_amoled settings_amoled_summary settings_color_contrast settings_color_contrast_high
settings_color_contrast_medium settings_color_contrast_standard settings_dynamic_color
settings_dynamic_color_summary settings_font_summary settings_home_hide_working_mode
settings_home_hide_working_mode_summary settings_home_layout settings_home_layout_circle
settings_home_layout_dashboard settings_home_layout_focus settings_home_layout_grid settings_home_layout_stats
settings_home_layout_summary settings_home_stats_top settings_key_color_reset
settings_key_color_reset_summary""".split()


def strings(path):
    return {e.get("name"): e.text or "" for e in ET.parse(path).getroot() if e.tag == "string"}


class TranslationTests(unittest.TestCase):
    def setUp(self):
        self.english = strings(RES / "values/strings.xml")
        self.locales = sorted(RES.glob("values-*/strings.xml"))

    def test_lunexsu_keys_exist_in_english(self):
        self.assertEqual(72, len(LUNEXSU_KEYS))
        self.assertFalse(set(LUNEXSU_KEYS) - set(self.english))

    def test_every_locale_translates_the_lunexsu_strings(self):
        self.assertGreaterEqual(len(self.locales), 46)
        for path in self.locales:
            missing = set(LUNEXSU_KEYS) - set(strings(path))
            self.assertFalse(missing, f"{path.parent.name}: {sorted(missing)}")

    def test_format_arguments_match_english(self):
        # A missing or extra argument throws at runtime only in that language.
        for path in self.locales:
            translated = strings(path)
            for key in LUNEXSU_KEYS:
                self.assertEqual(sorted(FORMAT.findall(self.english[key])), sorted(FORMAT.findall(translated[key])),
                                 f"{path.parent.name}/{key}")

    def test_quotes_are_escaped_for_aapt(self):
        for path in self.locales:
            for key, value in strings(path).items():
                if key in LUNEXSU_KEYS:
                    self.assertIsNone(re.search(r"(?<!\\)['\"]", value), f"{path.parent.name}/{key}: {value}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
