"""Host-only source/provenance contract for the dedicated Sui build job."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import zipfile

from test_packaging import SOURCE, elf, packager

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_sui_artifact import prepare, locked_version
from stage_sui_payload import stage
from sui_source_inputs import source_inputs
from sui_manager_binding import binding


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="lunex-sui-artifact-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        for name in (*packager.NOTICES, "package.py", "payload-version.txt"):
            destination = self.source / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SOURCE / name, destination)
        (self.source / "out").mkdir()
        self.archive = self.source / "out/Sui-v13.5.4.3_567-fixture-release.zip"
        version = locked_version(self.source)
        self.module_prop = ('id=lunexsu-sui\n'
                            f'version=v{version["version"]} ({version["version_code"]}-{version["upstream_commit"][:8]}-release)\n'
                            f'versionCode={version["version_code"]}\n')
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr("module.prop", self.module_prop)
            for abi in packager.ABI_ELF:
                for _, name, _ in packager.PRIMARY_FILES:
                    archive.writestr(f"lib/{abi}/{name}", elf(abi))
            for _, name, _ in packager.SHARED_FILES:
                archive.writestr(name, b"synthetic fixture, never executed\n" + binding()["certificate_sha256"].encode("ascii"))
        self.artifact = self.root / "artifact"

    def test_same_source_artifact_produces_both_abi_trees(self):
        inputs = source_inputs(self.source)
        metadata = prepare(self.source, self.artifact)
        self.assertEqual(inputs, metadata["source_inputs"])
        self.assertEqual(binding(), metadata["manager_binding"])
        self.assertEqual(hashlib.sha256(self.archive.read_bytes()).hexdigest(), metadata["zip_sha256"])
        stage(self.artifact, self.source, self.root / "assets")
        for target, abi in (("aarch64", "arm64-v8a"), ("x86_64", "x86_64")):
            manifest = json.loads((self.root / f"assets/{target}/sui/payload.json").read_text())
            self.assertEqual(abi, manifest["primary_abi"])
        self.assertFalse((self.root / "assets/riscv64/sui").exists())

    def test_changed_archive_or_source_is_rejected_before_staging(self):
        prepare(self.source, self.artifact)
        original = (self.artifact / "metadata.json").read_bytes()
        for field, value in (("schema", 2), ("schema", True), ("version", "99"),
                             ("version_code", 999999), ("version_code", True), ("source_commit", "00" * 20),
                             ("source_inputs", {}), ("zip_sha256", "00" * 32),
                             ("manager_binding", None), ("manager_binding", binding(size=1, digest="00" * 32))):
            with self.subTest(field=field):
                metadata = json.loads(original)
                metadata[field] = value
                (self.artifact / "metadata.json").write_text(json.dumps(metadata))
                with self.assertRaises(ValueError):
                    stage(self.artifact, self.source, self.root / "assets")
                self.assertFalse((self.root / "assets").exists())
        (self.artifact / "metadata.json").write_bytes(original)
        (self.source / "new-runtime-source.java").write_text("changed source")
        with self.assertRaises(ValueError):
            stage(self.artifact, self.source, self.root / "assets")
        self.assertFalse((self.root / "assets").exists())

    def test_core_and_sui_public_bindings_must_agree_before_staging(self):
        prepare(self.source, self.artifact)
        for expected in (binding(package="com.shixiaoshi0417.lunexsu.pr"), binding(size=1, digest="00" * 32)):
            with self.assertRaises(ValueError):
                stage(self.artifact, self.source, self.root / "assets", expected)
            self.assertFalse((self.root / "assets").exists())
        for package, size, digest in (("com.shixiaoshi0417.lunexsu.demo", 803, "00"*32),
                                      ("com.shixiaoshi0417.lunexsu", True, "00"*32),
                                      ("com.shixiaoshi0417.lunexsu", 0, "00"*32),
                                      ("com.shixiaoshi0417.lunexsu", 803, "FF"*32)):
            with self.assertRaises(ValueError): binding(package, size, digest)

    def test_generated_outputs_and_sdk_paths_do_not_change_source_identity(self):
        original = source_inputs(self.source)
        for name in ("out/new.zip", "api/build/generated.txt", ".gradle/cache", "local.properties"):
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("machine-local generated output")
        self.assertEqual(original, source_inputs(self.source))

    def test_missing_ambiguous_outputs_or_existing_artifact_are_retained(self):
        self.archive.rename(self.archive.with_suffix(".ignored"))
        with self.assertRaises(ValueError):
            prepare(self.source, self.artifact)
        self.archive.with_suffix(".ignored").rename(self.archive)
        duplicate = self.archive.with_name("Sui-other-release.zip")
        shutil.copyfile(self.archive, duplicate)
        with self.assertRaises(ValueError):
            prepare(self.source, self.artifact)
        duplicate.unlink()
        prepare(self.source, self.artifact)
        original = (self.artifact / "metadata.json").read_bytes()
        with self.assertRaises(FileExistsError):
            prepare(self.source, self.artifact)
        self.assertEqual(original, (self.artifact / "metadata.json").read_bytes())

    def test_invalid_build_or_unsupported_existing_tree_does_not_publish(self):
        with zipfile.ZipFile(self.archive, "a") as archive:
            archive.writestr("untrusted/../path", b"bad")
        with self.assertRaises(ValueError):
            prepare(self.source, self.artifact)
        self.assertFalse(self.artifact.exists())

    def test_built_module_identity_must_match_its_locked_source(self):
        original = self.archive.read_bytes()
        for old, new in (("lunexsu-sui", "finasu-sui"), ("-release)", "-debug)"),
                         ("versionCode=130504", "versionCode=1")):
            with self.subTest(new=new):
                temporary = self.archive.with_suffix(".new")
                with zipfile.ZipFile(self.archive) as source, zipfile.ZipFile(temporary, "w") as target:
                    for entry in source.infolist():
                        target.writestr(entry, self.module_prop.replace(old, new).encode()
                                        if entry.filename == "module.prop" else source.read(entry))
                temporary.replace(self.archive)
                with self.assertRaises(ValueError):
                    prepare(self.source, self.artifact)
                self.assertFalse(self.artifact.exists())
                self.archive.write_bytes(original)

    def test_workflows_share_one_build_without_including_demo_or_device_tasks(self):
        workflow = (ROOT / ".github/workflows/sui.yml").read_text(encoding="utf-8")
        self.assertIn("bash gradlew zipRelease", workflow)
        self.assertNotIn("flashRelease", workflow)
        self.assertNotIn("adb ", workflow)
        build = (ROOT / ".github/workflows/build-manager.yml").read_text(encoding="utf-8")
        self.assertEqual(2, build.count("needs: [generate-key, build-lkm, build-ksuinit, build-sui]"))
        self.assertEqual(2, build.count("pack_sui: true"))
        assets = (ROOT / "userspace/ksud/src/assets.rs").read_text(encoding="utf-8")
        self.assertIn('file.starts_with("sui/")', assets)


if __name__ == "__main__":
    unittest.main()
