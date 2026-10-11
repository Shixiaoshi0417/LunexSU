"""Cross-component identity and executable artifact packaging regressions."""
from pathlib import Path
import base64
import hashlib
import os
import re
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.shixiaoshi0417.lunexsu"
SOURCE = ROOT / "manager/app/src"


def read(name):
    return (ROOT / name).read_text(encoding="utf-8")


def shell_step(name, label):
    content = read(name)
    match = re.search(r"- name: " + re.escape(label) + r"\n(.*?)(?=\n\s*- name:|\Z)", content, re.S)
    if not match:
        raise AssertionError(f"Workflow step missing: {label}")
    body = match[1].split("run: |\n", 1)[1]
    lines = body.splitlines()
    indent = len(lines[0]) - len(lines[0].lstrip())
    commands = []
    for line in lines:
        if line.strip() and len(line) - len(line.lstrip()) < indent:
            break
        commands.append(line)
    return textwrap.dedent("\n".join(commands)).strip()


class IdentityTests(unittest.TestCase):
    def test_core_manager_daemon_and_pr_identity_match(self):
        self.assertIn(f'namespace = "{PACKAGE}"', read("manager/app/build.gradle.kts"))
        self.assertIn(f'"{PACKAGE}.pr" else "{PACKAGE}"', read("manager/app/build.gradle.kts"))
        self.assertIn(f"KSU_PACKAGE_NAME ?= {PACKAGE}\n", read("kernel/Kbuild"))
        self.assertIn(f"KSU_PACKAGE_NAME ?= {PACKAGE}.pr\n", read("kernel/Kbuild"))
        self.assertIn(f"KSU_PACKAGE_NAME={PACKAGE}", read("userspace/ksud/build.rs"))
        self.assertIn(f'{PACKAGE}.ui.MainActivity', read("userspace/ksud/src/late_load.rs"))
        self.assertIn(f'KSU_PACKAGE_NAME="{PACKAGE}.pr"', read(".github/workflows/ksud.yml"))

    def test_all_android_sources_and_jni_use_new_namespace(self):
        for suffix in ("*.kt", "*.java", "*.aidl"):
            for path in SOURCE.rglob(suffix):
                source = path.read_text(encoding="utf-8")
                self.assertNotIn("me.weishu.kernelsu", source, str(path))
                self.assertNotIn('System.loadLibrary("kernelsu")', source, str(path))
                declaration = re.search(r"^package ([\w.]+)", source, re.M)
                self.assertIsNotNone(declaration, str(path))
                self.assertTrue(declaration[1].startswith(PACKAGE), str(path))
                self.assertIn(declaration[1].replace(".", "/"), path.parent.as_posix())
        natives = read("manager/app/src/main/java/com/shixiaoshi0417/lunexsu/Natives.kt")
        jni = read("manager/app/src/main/cpp/jni.cc")
        for method in re.findall(r"external fun (\w+)\(", natives):
            self.assertIn(f"Java_com_shixiaoshi0417_lunexsu_Natives_{method}", jni, method)
        self.assertNotIn("Java_me_weishu", jni)
        self.assertIn('FindClass("com/shixiaoshi0417/lunexsu/Natives$Profile")', jni)
        self.assertIn('System.loadLibrary("lunexsu")', natives)
        self.assertIn("add_library(lunexsu", read("manager/app/src/main/cpp/CMakeLists.txt"))
        self.assertIn('System.loadLibrary("lunexsu")', read("manager/app/src/main/java/com/shixiaoshi0417/lunexsu/magica/AppZygotePreload.java"))
        self.assertIn("Java_com_shixiaoshi0417_lunexsu_magica_AppZygotePreload_forkDontCareAndExecKsud", jni)

    def test_no_hardcoded_upstream_ci_workspace(self):
        workflow = read(".github/workflows/ddk-lkm.yml")
        self.assertIn('safe.directory "$GITHUB_WORKSPACE"', workflow)
        self.assertNotIn("/__w/KernelSU/KernelSU", workflow)
        self.assertIn('OUTPUT_NAME="${{ inputs.kmi }}_lunexsu.ko"', workflow)

    def test_production_core_uses_actual_signing_certificate_and_fails_closed(self):
        script = shell_step(".github/workflows/build-manager.yml", "Extract production certificate hash and size")
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            store = base / "fixture.jks"
            password = "lunexsu-test-fixture"
            subprocess.run(["keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=LunexSU Test", "-storetype", "JKS", "-keystore", str(store), "-storepass", password, "-keypass", password], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["keytool", "-exportcert", "-alias", "fixture", "-keystore", str(store), "-storepass", password, "-file", str(base / "fixture.der")], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            fixture = (base / "fixture.der").read_bytes()
            kbuild = base / "kernel/Kbuild"
            kbuild.parent.mkdir()

            def write_defaults(size, digest):
                kbuild.write_bytes(f"KSU_EXPECTED_SIZE ?= {size}\nKSU_EXPECTED_HASH ?= {digest}\n".encode())

            # The step also requires the Kbuild defaults for non-CI Core builds to be this certificate.
            write_defaults(f"0x{len(fixture):04x}", hashlib.sha256(fixture).hexdigest())
            env = dict(os.environ, PROD_KEYSTORE=base64.b64encode(store.read_bytes()).decode(), PROD_KEYSTORE_PASSWORD=password, PROD_KEY_ALIAS="fixture", GITHUB_OUTPUT=str(base / "outputs"))
            subprocess.run(["bash", "-eu", "-c", script], cwd=base, env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            cert = (base / "production-cert.der").read_bytes()
            self.assertEqual(fixture, cert)
            output = (base / "outputs").read_text()
            self.assertIn(f"expected_hash={hashlib.sha256(cert).hexdigest()}", output)
            self.assertIn(f"expected_size=0x{len(cert):04x}", output)
            (base / "outputs").unlink()
            write_defaults(f"0x{len(fixture):04x}", "0" * 64)
            stale = subprocess.run(["bash", "-eu", "-c", script], cwd=base, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.assertNotEqual(0, stale.returncode)
            self.assertFalse((base / "outputs").exists())
            env["PROD_KEYSTORE"] = ""
            failure = subprocess.run(["bash", "-eu", "-c", script], cwd=base, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.assertNotEqual(0, failure.returncode)
            self.assertFalse((base / "outputs").exists())
        workflow = read(".github/workflows/build-manager.yml")
        self.assertIn("steps.extract_prod.outputs.expected_hash", workflow)
        self.assertIn("expected_hash: ${{ needs.generate-key.outputs.expected_hash", workflow)

    def test_core_defaults_match_documented_release_certificate(self):
        kbuild = read("kernel/Kbuild")
        size = re.search(r"^KSU_EXPECTED_SIZE \?= (0x[0-9a-f]{4})$", kbuild, re.M)[1]
        digest = re.search(r"^KSU_EXPECTED_HASH \?= ([0-9a-f]{64})$", kbuild, re.M)[1]
        signing = read("docs/apk-signing.md")
        self.assertIn(f"\n{digest}\n", signing)
        self.assertIn(f"`{size}`", signing)

    def test_production_signing_steps_share_secrets_and_fail_closed(self):
        workflow = read(".github/workflows/build-manager.yml")
        for label in ("Extract production certificate hash and size", "Write key", "Prepare signing inputs"):
            step = workflow.split(f"- name: {label}\n", 1)[1].split("\n      - name:", 1)[0]
            for variable, secret in (("PROD_KEYSTORE", "KEYSTORE"), ("PROD_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD"), ("PROD_KEY_ALIAS", "KEY_ALIAS")):
                self.assertIn(f"{variable}: ${{{{ secrets.{secret} }}}}", step)
        self.assertNotIn("secrets.PROD_", workflow)
        script = shell_step(".github/workflows/build-manager.yml", "Write key")
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            password = "fixture-$()'literal"
            env = dict(os.environ, PROD_KEYSTORE=base64.b64encode(b"fixture").decode(), PROD_KEYSTORE_PASSWORD=password, PROD_KEY_PASSWORD=password, PROD_KEY_ALIAS="fixture")
            subprocess.run(["bash", "-eu", "-c", script], cwd=base, env=env, check=True)
            self.assertEqual(b"fixture", (base / "key.jks").read_bytes())
            properties = (base / "gradle.properties").read_text()
            self.assertIn(f"KEY_PASSWORD={password}\n", properties)
            (base / "gradle.properties").unlink()
            env["PROD_KEY_PASSWORD"] = ""
            failure = subprocess.run(["bash", "-eu", "-c", script], cwd=base, env=env)
            self.assertNotEqual(0, failure.returncode)
            self.assertFalse((base / "gradle.properties").exists())

    def test_apk_signature_gate_rejects_mismatch_and_verification_failure(self):
        script = shell_step(".github/workflows/build-manager.yml", "Verify APK signature matches Core")
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tool = base / "sdk/build-tools/37/apksigner"
            tool.parent.mkdir(parents=True)
            (base / "dist").mkdir()
            (base / "dist/fixture.apk").write_bytes(b"fixture")
            tool.write_text("#!/bin/sh\nprintf '%s certificate SHA-256 digest: %s\\n' \"$TEST_SIGNER_LABEL\" \"$TEST_CERT_HASH\"\nexit \"$TEST_VERIFY_RESULT\"\n")
            tool.chmod(0o755)
            env = dict(os.environ, ANDROID_HOME=str(base / "sdk"), EXPECTED_CERT_HASH="a" * 64, TEST_CERT_HASH="a" * 64, TEST_VERIFY_RESULT="0", TEST_SIGNER_LABEL="Signer #1")
            for label in ("Signer #1", "V3.0 Signer:"):
                subprocess.run(["bash", "-eu", "-c", script], cwd=base, env=dict(env, TEST_SIGNER_LABEL=label), check=True)
            for setting, value in (("TEST_CERT_HASH", "b" * 64), ("TEST_VERIFY_RESULT", "1")):
                broken = dict(env, **{setting: value})
                result = subprocess.run(["bash", "-eu", "-c", script], cwd=base, env=broken)
                self.assertNotEqual(0, result.returncode)

    def test_public_lkm_names_pack_into_existing_daemon_abi(self):
        for workflow in ("ksud.yml", "ksud-extra.yml"):
            with tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                for arch in ("aarch64", "x86_64"):
                    artifact = base / f"{arch}-android12-5.10-lkm"
                    artifact.mkdir()
                    (artifact / "android12-5.10_lunexsu.ko").write_bytes(arch.encode())
                    (base / f"userspace/ksud/bin/{arch}").mkdir(parents=True)
                script = shell_step(f".github/workflows/{workflow}", "Prepare LKM files")
                subprocess.run(["bash", "-eu", "-c", script], cwd=base, check=True)
                for arch in ("aarch64", "x86_64"):
                    embedded = base / f"userspace/ksud/bin/{arch}/android12-5.10_kernelsu.ko"
                    self.assertEqual(arch.encode(), embedded.read_bytes())

    def test_release_files_are_branded_without_changing_runtime_binaries(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            for artifact, binary in (("ksud-aarch64-linux-android", "ksud"), ("ksuinit-aarch64", "ksuinit"), ("aarch64-android12-5.10-lkm", "android12-5.10_lunexsu.ko")):
                (base / artifact).mkdir()
                (base / artifact / binary).write_bytes(binary.encode())
            for label in ("Rename ksud", "Rename ksuinit", "Rename lkm"):
                subprocess.run(["bash", "-eu", "-c", shell_step(".github/workflows/release.yml", label)], cwd=base, check=True, stdout=subprocess.DEVNULL)
            self.assertEqual(b"ksud", (base / "ksud/LunexSU-ksud-aarch64-linux-android").read_bytes())
            self.assertEqual(b"ksuinit", (base / "ksuinit/LunexSU-ksuinit-aarch64").read_bytes())
            self.assertEqual(b"android12-5.10_lunexsu.ko", (base / "lkm/LunexSU-lkm-aarch64-android12-5.10.ko").read_bytes())

    def test_branding_does_not_rename_third_party_links(self):
        # Only this fork's own repository may carry the LunexSU name in a GitHub URL.
        links = re.compile(r"https://github\.com/([^\s\"'()<>`\\]+)")
        for base in ("manager/app/src/main", "website/docs"):
            for path in (ROOT / base).rglob("*"):
                if path.suffix not in {".kt", ".java", ".xml", ".md", ".json", ".ts", ".vue"} or "node_modules" in path.parts:
                    continue
                for link in links.findall(path.read_text(encoding="utf-8")):
                    if "lunexsu" in link.lower():
                        self.assertTrue(link.startswith("Shixiaoshi0417/LunexSU"), f"{path}: {link}")
        self.assertIn("https://github.com/tiann/KernelSU", read("manager/app/src/main/java/com/shixiaoshi0417/lunexsu/ui/screen/about/AboutScreen.kt"))
        self.assertIn("https://github.com/KernelSU-Modules-Repo/", read("manager/app/src/main/java/com/shixiaoshi0417/lunexsu/ui/screen/modulerepo/ModuleRepoScreen.kt"))

    def test_branding_suite_runs_in_ci(self):
        # Every file in this directory runs on push and pull request, so a broken
        # fixture cannot go unnoticed until an external review.
        workflow = read(".github/workflows/test-branding.yml")
        self.assertIn("tests/branding/test_*.py", workflow)
        for trigger in ("push:", "pull_request:"):
            self.assertIn(trigger, workflow)

    def test_upstream_compatibility_and_ui_attribution_remain(self):
        self.assertIn('"[ksu_driver]"', read("manager/app/src/main/cpp/ksu.cc"))
        self.assertIn('"/kernelsu.ko"', read("userspace/ksuinit/src/init.rs"))
        self.assertIn('"ksu/"', read("userspace/ksud/src/defs.rs"))
        self.assertIn("LyraVoid/FolkSU", read("README.md"))
        self.assertIn("LyraVoid/FolkSU", read("README_EN.md"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
