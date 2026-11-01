"""Host-only payload tests: synthetic files, no native/Android code execution."""
import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "userspace/sui"
spec = importlib.util.spec_from_file_location("sui_package", SOURCE / "package.py")
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


def elf(abi):
    data = bytearray(64)
    elf_class, machine = packager.ABI_ELF[abi]
    data[:7] = b"\x7fELF" + bytes((elf_class, 1, 1))
    struct.pack_into("<HHI", data, 16, 3, machine, 1)
    return bytes(data)


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="lunex-sui-package-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / "source.zip"
        self.members = {}
        for abi in packager.ABI_ELF:
            for _, library, _ in packager.PRIMARY_FILES:
                self.members[f"lib/{abi}/{library}"] = elf(abi)
        for _, source, _ in packager.SHARED_FILES:
            self.members[source] = b"synthetic fixture, never executed\n"
        self.write_zip()

    def write_zip(self):
        with zipfile.ZipFile(self.archive, "w") as archive:
            for name, data in self.members.items():
                archive.writestr(name, data)

    def build(self, target="aarch64", assets=None, **kwargs):
        return packager.package(self.archive, assets or self.root / "assets", target,
                                SOURCE, "13.5.4.3", 567, **kwargs)

    def test_all_supported_targets_have_exact_architectures_modes_and_hashes(self):
        for target, (primary, secondary) in packager.TARGETS.items():
            with self.subTest(target=target):
                result = self.build(target, self.root / target)
                manifest = json.loads((result / "payload.json").read_text())
                self.assertEqual(1, manifest["schema"])
                self.assertEqual(primary, manifest["primary_abi"])
                self.assertEqual(secondary, manifest["secondary_abi"])
                expected = {name for name, _, _ in packager.PRIMARY_FILES}
                expected |= {name for name, _, _ in packager.SHARED_FILES}
                expected |= {"licenses/" + name.replace("/", "_") for name in packager.NOTICES}
                if secondary:
                    expected.add(f"zygisk.{secondary}.so")
                self.assertEqual(expected, {entry["name"] for entry in manifest["files"]})
                for entry in manifest["files"]:
                    self.assertNotIn("\\", entry["name"])
                    data = (result / entry["name"]).read_bytes()
                    self.assertEqual(hashlib.sha256(data).hexdigest(), entry["sha256"])
                    self.assertIn(entry["mode"], (0o600, 0o644, 0o755, 0o770))
                self.assertEqual(elf(primary), (result / "zygisk.so").read_bytes())
                for name in packager.NOTICES:
                    self.assertEqual((SOURCE / name).read_bytes(),
                                     (result / "licenses" / name.replace("/", "_")).read_bytes())

    def test_payload_bytes_and_manifest_are_deterministic(self):
        first = self.build(assets=self.root / "first")
        second = self.build(assets=self.root / "second")
        def contents(directory):
            return {path.relative_to(directory).as_posix(): path.read_bytes()
                    for path in directory.rglob("*") if path.is_file()}
        self.assertEqual(contents(first), contents(second))

    def test_missing_empty_or_wrong_architecture_never_changes_previous_payload(self):
        destination = self.build()
        original = (destination / "payload.json").read_bytes()
        required = "lib/arm64-v8a/libsui.so"
        for invalid in (None, b"", elf("x86_64"), b"not an ELF"):
            with self.subTest(invalid=invalid):
                if invalid is None:
                    self.members.pop(required, None)
                else:
                    self.members[required] = invalid
                self.write_zip()
                with self.assertRaises(ValueError):
                    self.build(force=True)
                self.assertEqual(original, (destination / "payload.json").read_bytes())
        self.assertEqual([], list(destination.parent.glob(".sui-*")))

    def test_duplicate_and_case_colliding_members_are_rejected(self):
        for name in ("sui.dex", "SUI.DEX"):
            with self.subTest(name=name):
                self.write_zip()
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(self.archive, "a") as archive:
                        archive.writestr(name, b"ambiguous")
                with self.assertRaisesRegex(ValueError, "Duplicate"):
                    self.build()
                self.assertFalse((self.root / "assets").exists())

    def test_noncanonical_paths_are_rejected_even_when_not_selected(self):
        for index, name in enumerate(("../outside", "/outside", "C:/outside", "a\\b", "a//b", "./a", "a/../b", "a\x00b")):
            with self.subTest(name=name):
                self.write_zip()
                with zipfile.ZipFile(self.archive, "a") as archive:
                    entry = zipfile.ZipInfo("placeholder")
                    entry.filename = name
                    archive.writestr(entry, b"bad path")
                with self.assertRaisesRegex(ValueError, "Unsafe"):
                    self.build(assets=self.root / f"path-{index}")

    def test_symlink_and_special_archive_members_are_rejected(self):
        for kind in (stat.S_IFLNK, stat.S_IFIFO, stat.S_IFCHR, stat.S_IFSOCK):
            with self.subTest(kind=kind):
                self.write_zip()
                entry = zipfile.ZipInfo("special")
                entry.create_system = 3
                entry.external_attr = (kind | 0o777) << 16
                with zipfile.ZipFile(self.archive, "a") as archive:
                    archive.writestr(entry, b"target")
                with self.assertRaisesRegex(ValueError, "Special"):
                    self.build()

    def test_declared_limits_are_checked_before_member_decompression(self):
        for name, value in (("MAX_ENTRIES", 1), ("MAX_FILE_BYTES", 10), ("MAX_TOTAL_BYTES", 100)):
            with self.subTest(limit=name), patch.object(packager, name, value):
                with self.assertRaises(ValueError), patch.object(zipfile.ZipFile, "read", side_effect=AssertionError("read before limit check")):
                    self.build()

    def test_encrypted_entries_are_rejected(self):
        entry = zipfile.ZipInfo("encrypted")
        entry.flag_bits = 1
        class Archive:
            def infolist(self):
                return [entry]
        with self.assertRaisesRegex(ValueError, "Encrypted"):
            packager.validate_archive(Archive())

    def test_bad_crc_is_rejected_without_publishing(self):
        with zipfile.ZipFile(self.archive) as archive:
            entry = archive.getinfo("lib/arm64-v8a/libmain.so")
        data = bytearray(self.archive.read_bytes())
        name_length, extra_length = struct.unpack_from("<HH", data, entry.header_offset + 26)
        data[entry.header_offset + 30 + name_length + extra_length] ^= 1
        self.archive.write_bytes(data)
        with self.assertRaises(zipfile.BadZipFile):
            self.build()
        self.assertFalse((self.root / "assets").exists())

    def test_refuses_implicit_replacement_and_retains_stale_recovery_directory(self):
        result = self.build()
        with self.assertRaises(FileExistsError):
            self.build()
        stale = result.with_name(f".sui-stale-{packager.os.getpid()}")
        stale.mkdir()
        (stale / "recovery").write_text("keep")
        with self.assertRaises(FileExistsError):
            self.build(force=True)
        self.assertEqual("keep", (stale / "recovery").read_text())
        self.assertTrue((result / "payload.json").exists())

    def test_failed_build_publication_restores_previous_output(self):
        result = self.build()
        original = (result / "payload.json").read_bytes()
        rename = Path.rename
        def fail_new(source, target):
            if source.name == "sui" and source.parent.name.startswith(".sui-"):
                raise OSError("injected build publication failure")
            return rename(source, target)
        with patch.object(Path, "rename", fail_new), self.assertRaises(OSError):
            self.build(force=True)
        self.assertEqual(original, (result / "payload.json").read_bytes())

    def test_rejects_unsupported_target_and_invalid_version(self):
        with self.assertRaises(ValueError):
            self.build("riscv64")
        for version, code in (("", 1), ("x\ny", 1), ("x y", 1), ("x", 0), ("x", True), ("x", 1 << 31)):
            with self.subTest(version=version, code=code), self.assertRaises(ValueError):
                packager.package(self.archive, self.root / "invalid", "aarch64", SOURCE, version, code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
