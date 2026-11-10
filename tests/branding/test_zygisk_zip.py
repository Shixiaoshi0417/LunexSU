#!/usr/bin/env python3
"""Offline tests for the BusyBox-compatible ZygiskNextNext ZIP container."""

import importlib.util
import io
import lzma
from pathlib import Path
import re
import struct
import tempfile
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("normalize_zygisk_zip", ROOT / "scripts/normalize_zygisk_zip.py")
NORMALIZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NORMALIZER)


def xz_zip(data):
    name = b"bin/arm64-v8a/injector"
    compressed = lzma.compress(data)
    crc = zlib.crc32(data)
    local = struct.pack("<4s5H3I2H", b"PK\x03\x04", 63, 0, 95, 0, 0,
                        crc, len(compressed), len(data), len(name), 0) + name + compressed
    central = struct.pack("<4s6H3I5H2I", b"PK\x01\x02", 63, 63, 0, 95, 0, 0,
                          crc, len(compressed), len(data), len(name), 0, 0, 0, 0, 0, 0) + name
    end = struct.pack("<4s4H2IH", b"PK\x05\x06", 0, 0, 1, 1, len(central), len(local), 0)
    return local + central + end


class ZygiskZipTests(unittest.TestCase):
    def normalize(self, raw):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.zip"
            target = Path(directory) / "target.zip"
            source.write_bytes(raw)
            NORMALIZER.normalize(source, target)
            return target.read_bytes()

    def test_xz_payload_is_unchanged_and_becomes_stored(self):
        data = b"\x7fELF" + bytes(range(256)) * 50
        normalized = self.normalize(xz_zip(data))
        with zipfile.ZipFile(io.BytesIO(normalized)) as archive:
            self.assertEqual(archive.read("bin/arm64-v8a/injector"), data)
            self.assertTrue(all(entry.compress_type == zipfile.ZIP_STORED for entry in archive.infolist()))

    def test_deflated_and_stored_files_remain_identical(self):
        source = io.BytesIO()
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("module.prop", "id=zygisknextsu\n", compress_type=zipfile.ZIP_DEFLATED)
            archive.writestr("customize.sh", "#!/system/bin/sh\n", compress_type=zipfile.ZIP_STORED)
        with zipfile.ZipFile(source) as before, zipfile.ZipFile(io.BytesIO(self.normalize(source.getvalue()))) as after:
            self.assertEqual(sorted(before.namelist()), sorted(after.namelist()))
            for name in before.namelist():
                self.assertEqual(before.read(name), after.read(name))

    def test_output_is_deterministic(self):
        source = xz_zip(b"same bytes")
        self.assertEqual(self.normalize(source), self.normalize(source))

    def test_wrong_crc_is_rejected(self):
        raw = bytearray(xz_zip(b"test"))
        central = raw.index(b"PK\x01\x02")
        struct.pack_into("<I", raw, central + 16, 0)
        with self.assertRaises(AssertionError):
            self.normalize(raw)

    def test_nextnext_archive_pins_match_documentation(self):
        build = (ROOT / "manager/app/build.gradle.kts").read_text(encoding="utf-8")
        docs = (ROOT / "docs/lunexsu-zygisk.md").read_text(encoding="utf-8")
        match = re.search(r'val nextNextZip = PinnedAsset\(\s*"([^"]+)",\s*"([0-9a-f]{64})"', build)
        self.assertIsNotNone(match)
        url, digest = match.groups()
        self.assertEqual(url, "https://github.com/VeryBaaad/ZNNext/releases/download/"
                              "v2.16.0/ZNNext-v2.16.0-371-52b358c-release.zip")
        self.assertEqual(digest, "0915c496189e2130b4cbd754f3de0cefc5b35a74951115d0e0610ce8b89f2534")
        stored = re.search(r'val nextNextStoredSha256 = "([0-9a-f]{64})"', build).group(1)
        self.assertEqual(stored, "61f06452f111f4a7b9e140f605bf6a0017952fff950e57f7648f26856a7ab88b")
        for value in (url.rsplit("/", 1)[-1], digest, stored):
            self.assertIn(value, docs)

    def test_nextnext_source_and_license_are_commit_pinned(self):
        commit = "52b358c6a9de6b2f32aa848a95121ae61c624c3b"
        build = (ROOT / "manager/app/build.gradle.kts").read_text(encoding="utf-8")
        docs = (ROOT / "docs/lunexsu-zygisk.md").read_text(encoding="utf-8")
        self.assertIn(f"https://raw.githubusercontent.com/VeryBaaad/ZNNext/{commit}/LICENSE", build)
        self.assertIn(f"https://github.com/VeryBaaad/ZNNext/tree/{commit}", docs)

    def test_demo_nextnext_version_matches_bundle(self):
        demo = ROOT / "manager/app/src/noRootDemo/java/com/shixiaoshi0417/lunexsu/data/zygisk/DemoBuiltinZygisk.kt"
        self.assertIn('ZygiskModule.NextNext.moduleId to ZygiskModuleVersion(installed = "2.16.0 (DEMO)")',
                      demo.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
