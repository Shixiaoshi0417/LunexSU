from pathlib import Path
import importlib.util
import tempfile
import unittest
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("repack", ROOT / "repack_apk.py")
repack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repack)


class BundledLkmTests(unittest.TestCase):
    def test_existing_daemon_without_modules_is_rejected(self):
        # A daemon binary existing in an APK does not imply it embeds an LKM.
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            with ZipFile(apk, "w") as output:
                output.writestr("lib/arm64-v8a/libksud.so", b"ELF daemon without modules")
            with self.assertRaisesRegex(RuntimeError, "Missing bundled LKM"):
                repack.assert_bundled_kmis(apk, ["arm64-v8a:android12-5.10"])

    def test_every_requested_abi_is_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            with ZipFile(apk, "w") as output:
                output.writestr("lib/arm64-v8a/libksud.so", b"android12-5.10_kernelsu.ko")
                output.writestr("lib/x86_64/libksud.so", b"no embedded LKM")
            repack.assert_bundled_kmis(apk, ["arm64-v8a:android12-5.10"])
            with self.assertRaisesRegex(RuntimeError, "x86_64"):
                repack.assert_bundled_kmis(apk, ["arm64-v8a:android12-5.10", "x86_64:android12-5.10"])

    def test_public_lunexsu_name_cannot_replace_the_runtime_asset_name(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            with ZipFile(apk, "w") as output:
                output.writestr("lib/arm64-v8a/libksud.so", b"android12-5.10_lunexsu.ko")
            with self.assertRaises(RuntimeError):
                repack.assert_bundled_kmis(apk, ["arm64-v8a:android12-5.10"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
