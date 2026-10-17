"""PR builds must not produce or transport a private signing identity."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

from test_identity import shell_step

ROOT = Path(__file__).resolve().parents[2]


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
