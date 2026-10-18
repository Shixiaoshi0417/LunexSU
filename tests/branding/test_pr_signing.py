"""PR builds must not produce or transport a private signing identity."""
from pathlib import Path
import importlib.util
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

from test_identity import shell_step

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("unsigned_apk", ROOT / ".github/scripts/check-pr-unsigned-apk.py")
UNSIGNED_APK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UNSIGNED_APK)


class PrSigningTests(unittest.TestCase):
    def test_only_public_bindings_are_job_outputs(self):
        source = (ROOT / ".github/workflows/build-manager.yml").read_text(encoding="utf-8")
        outputs = source.split("  generate-key:", 1)[1].split("    steps:", 1)[0]
        self.assertNotIn("keystore:", outputs)
        self.assertNotIn("password:", outputs)
        self.assertNotIn("steps.gen.outputs", source)
        self.assertNotIn("needs.generate-key.outputs.keystore", source)
        self.assertNotIn("PR_KEYSTORE", source)
        self.assertNotIn("Generate temporary keystore", source)

    def test_pr_binding_is_public_and_cannot_name_a_der_certificate(self):
        script = shell_step(".github/workflows/build-manager.yml", "Disable installable PR signing identities")
        # Windows' system bash can be a WSL launcher which drops Win32-only
        # environment variables. Use a native POSIX shell there, not that shim.
        bash = "bash"
        if os.name == "nt":
            git_bash = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe"
            if not git_bash.is_file():
                self.skipTest("This shell fixture requires Git for Windows bash or a POSIX host")
            bash = str(git_bash)
        self.assertIsNotNone(shutil.which(bash))
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            output = Path(directory) / "outputs"
            subprocess.run([bash, "-eu", "-c", script], check=True,
                           env=dict(os.environ, GITHUB_OUTPUT=str(output)),
                           stdout=subprocess.DEVNULL)
            self.assertEqual(output.read_text().splitlines(), [
                "expected_size=0x0001", "expected_hash=" + "0" * 64,
            ])

    def test_pr_artifacts_are_not_repacked_as_installable_pairs(self):
        source = (ROOT / ".github/workflows/build-manager.yml").read_text(encoding="utf-8")
        repack = source.split("  repack-manager:", 1)[1]
        self.assertIn("github.event_name != 'pull_request'", repack.split("runs-on:", 1)[0])
        self.assertIn("manager-pr-compile-only", source)
        self.assertNotIn("Write PR key", source)


class UnsignedApkTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "app.apk"

    def apk(self, extra=None, manifest=True):
        with zipfile.ZipFile(self.path, "w") as archive:
            if manifest:
                archive.writestr("AndroidManifest.xml", b"fixture manifest")
            archive.writestr("classes.dex", b"fixture dex")
            for name, data in (extra or {}).items():
                archive.writestr(name, data)
            archive.comment = b"ordinary ZIP comment"

    def test_unsigned_zip_passes(self):
        self.apk()
        UNSIGNED_APK.verify_unsigned(self.path)

    def test_jar_signature_entries_are_rejected(self):
        for suffix in ("SF", "RSA", "DSA", "EC"):
            with self.subTest(suffix=suffix):
                self.apk({f"META-INF/CERT.{suffix}": b"signature fixture"})
                with self.assertRaisesRegex(ValueError, "JAR signing"):
                    UNSIGNED_APK.verify_unsigned(self.path)

    def test_apk_signing_block_is_detected_outside_zip_entries(self):
        self.apk()
        data = bytearray(self.path.read_bytes())
        end = data.rfind(b"PK\x05\x06")
        directory = struct.unpack_from("<I", data, end + 16)[0]
        block = struct.pack("<QQ", 24, 24) + b"APK Sig Block 42"
        data[directory:directory] = block
        struct.pack_into("<I", data, end + len(block) + 16, directory + len(block))
        self.path.write_bytes(data)
        with zipfile.ZipFile(self.path) as archive:
            self.assertIsNone(archive.testzip())
        with self.assertRaisesRegex(ValueError, "APK signing block"):
            UNSIGNED_APK.verify_unsigned(self.path)

    def test_non_apk_archive_is_rejected(self):
        self.apk(manifest=False)
        with self.assertRaisesRegex(ValueError, "manifest or dex"):
            UNSIGNED_APK.verify_unsigned(self.path)

    def test_truncated_zip_is_rejected(self):
        self.apk()
        self.path.write_bytes(self.path.read_bytes()[:15])
        with self.assertRaises(zipfile.BadZipFile):
            UNSIGNED_APK.verify_unsigned(self.path)

    def test_empty_output_directory_is_rejected(self):
        result = subprocess.run([
            sys.executable, str(ROOT / ".github/scripts/check-pr-unsigned-apk.py"),
            self.directory.name,
        ], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No PR APKs found", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
