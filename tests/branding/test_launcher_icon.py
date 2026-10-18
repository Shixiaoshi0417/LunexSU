"""Check launcher resource geometry, not screenshots from third-party viewers."""
from pathlib import Path
import os
import subprocess
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "manager/app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def load_resource(name):
    path = RES / name
    revision = os.environ.get("LUNEXSU_LAUNCHER_REVISION")
    if revision:
        data = subprocess.check_output(["git", "show", f"{revision}:{path.relative_to(ROOT).as_posix()}"], cwd=ROOT)
        return ET.fromstring(data)
    return ET.parse(path).getroot()


def fraction(name):
    root = load_resource("drawable/" + name)
    value = root.get(ANDROID + "inset")
    if not value or not value.endswith("%"):
        raise AssertionError(f"{name}: launcher padding must scale with its bounds")
    return float(value[:-1]) / 100


class LauncherIconTests(unittest.TestCase):
    def test_both_foregrounds_share_the_original_safe_ratio(self):
        colored = fraction("ic_launcher_foreground.xml")
        mono = fraction("ic_launcher_monochrome.xml")
        self.assertAlmostEqual(colored, 18 / 108, places=7)
        self.assertEqual(colored, mono)

    def test_small_preview_artwork_does_not_collapse_at_high_density(self):
        inset = fraction("ic_launcher_foreground.xml")
        # InsetDrawable uses int(bounds * fraction), independent of density.
        for density in (1, 1.5, 2, 3, 4):
            for size in (16, 24, 32, 48, 72, 108):
                with self.subTest(density=density, size=size):
                    bounds = round(size * density)
                    content = bounds - 2 * int(bounds * inset)
                    self.assertGreater(content, 0)
                    self.assertLessEqual(abs(content / bounds - 2 / 3), 2 / bounds + 0.000001)

    def test_standard_canvas_preserves_previous_placement(self):
        for name in ("ic_launcher_foreground.xml", "ic_launcher_monochrome.xml"):
            inset = fraction(name)
            for density in (1, 2, 3, 4):
                bounds = 108 * density
                self.assertEqual(bounds - 2 * int(bounds * inset), 72 * density)

    def test_adaptive_layers_and_artwork_are_not_replaced(self):
        icon = load_resource("mipmap-anydpi/ic_launcher.xml")
        self.assertEqual(icon.tag, "adaptive-icon")
        for layer, resource in (("foreground", "ic_launcher_foreground"), ("monochrome", "ic_launcher_monochrome")):
            self.assertEqual(icon.find(layer).get(ANDROID + "drawable"), "@drawable/" + resource)
        for resource, bitmap in (("ic_launcher_foreground", "lunexsu_logo"), ("ic_launcher_monochrome", "lunexsu_monochrome")):
            wrapper = load_resource("drawable/" + resource + ".xml")
            self.assertEqual(wrapper.get(ANDROID + "drawable"), "@drawable/" + bitmap)


if __name__ == "__main__":
    unittest.main()
