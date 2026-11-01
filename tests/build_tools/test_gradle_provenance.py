"""Run the actual provenance task wiring against a device-free Gradle build."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from sui_build_provenance import proof_path, verify
from sui_manager_binding import binding


class GradleProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.gradle = os.environ.get("GRADLE_TEST_BIN") or shutil.which("gradle")
        self.assertTrue(self.gradle, "Set GRADLE_TEST_BIN or install Gradle for this regression")
        self.temp = tempfile.TemporaryDirectory(prefix="lunex-gradle-provenance-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.source = self.repo / "userspace/sui"
        (self.source / "module").mkdir(parents=True)
        (self.source / "api").mkdir()
        (self.repo / "scripts").mkdir()
        (self.repo / "kernel").mkdir()
        for name in ("sui_build_provenance.py", "sui_manager_binding.py", "sui_source_inputs.py"):
            shutil.copyfile(ROOT / "scripts" / name, self.repo / "scripts" / name)
        shutil.copyfile(ROOT / "kernel/Kbuild", self.repo / "kernel/Kbuild")
        (self.source / "settings.gradle.kts").write_text('rootProject.name = "provenance-fixture"\ninclude(":module")\n')
        production = (ROOT / "userspace/sui/build.gradle.kts").read_text()
        wiring = production[production.index("// Managed release provenance"):]
        (self.source / "build.gradle.kts").write_text('import java.io.File\nimport java.util.Properties\nplugins { base }\n' + wiring)
        (self.source / "module/build.gradle.kts").write_text('''
plugins { base }
val preReleaseBuild = tasks.register("preReleaseBuild")
val compileRelease = tasks.register<Copy>("compileRelease") {
    dependsOn(preReleaseBuild)
    from("runtime.txt")
    into(layout.buildDirectory.dir("compiled"))
    doFirst { check(!rootProject.file("fail-compile").exists()) { "synthetic compile failure" } }
}
val assembleRelease = tasks.register("assembleRelease") { dependsOn(compileRelease) }
val zipRelease = tasks.register<Zip>("zipRelease") {
    dependsOn(assembleRelease)
    from(compileRelease)
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("out"))
    archiveFileName.set("Sui-fixture-release.zip")
}
assembleRelease.configure { finalizedBy(zipRelease) }
''')
        self.runtime = self.source / "module/runtime.txt"
        self.runtime.write_text("runtime A")
        self.archive = self.source / "out/Sui-fixture-release.zip"

    def build(self, *arguments, success=True):
        result = subprocess.run([self.gradle, "--offline", "--no-daemon", "--console=plain", *arguments],
                                cwd=self.source, text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_fresh_incremental_failed_and_recovered_builds(self):
        self.build("zipRelease")
        verify(self.source, self.archive, binding())
        self.runtime.write_text("runtime B")
        with self.assertRaisesRegex(ValueError, "build-time provenance"):
            verify(self.source, self.archive, binding())
        self.build("zipRelease")
        verify(self.source, self.archive, binding())
        with zipfile.ZipFile(self.archive) as archive:
            self.assertEqual(archive.read("runtime.txt"), b"runtime B")
        unchanged = self.build("zipRelease")
        self.assertIn(":module:compileRelease UP-TO-DATE", unchanged.stdout)
        verify(self.source, self.archive, binding())
        (self.source / "fail-compile").write_text("failure fixture")
        self.runtime.write_text("runtime C")
        self.build("zipRelease", success=False)
        self.assertFalse(proof_path(self.archive).exists())
        (self.source / "fail-compile").unlink()
        self.build("clean", "zipRelease")
        verify(self.source, self.archive, binding())

    def test_external_api_cannot_receive_vendored_source_provenance(self):
        (self.source / "local.properties").write_text("api.useLocal=true\napi.dir=../external-api\n")
        result = self.build("zipRelease", success=False)
        self.assertIn("Managed Sui provenance requires the vendored API source", result.stdout + result.stderr)
        self.assertFalse(proof_path(self.archive).exists())

    def test_gradle_receives_literal_signing_properties_without_serialization(self):
        # Synthetic strings only. The test asserts them in Gradle without printing
        # values and does not create, load or use a signing key.
        fixture = "  literal\\t\\u0041=:#!'\"\nsecond line  "
        project = self.repo / "signing-fixture"
        project.mkdir()
        (project / "settings.gradle.kts").write_text('rootProject.name="signing-transport"\n')
        literal = json.dumps(fixture)
        (project / "build.gradle.kts").write_text('tasks.register("verifyTransport") { doLast {\n'
            + '\n'.join(f'check(project.property("{name}") == {literal})' for name in ("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"))
            + '\n} }\n')
        env = dict(os.environ)
        env.update({f"ORG_GRADLE_PROJECT_{name}": fixture for name in ("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")})
        result = subprocess.run([self.gradle, "--offline", "--no-daemon", "--console=plain", "verifyTransport"],
                                cwd=project, env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
