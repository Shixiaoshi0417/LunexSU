from pathlib import Path
import importlib.util
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("asset_check", ROOT / ".github/scripts/check-bundled-assets.py")
asset_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asset_check)


def elf_header(machine, kind, interpreter=False):
    data = bytearray(120 if kind != 1 else 64)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<HH", data, 16, kind, machine)
    if kind != 1:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<I", data, 64, 3 if interpreter else 1)
    return data


class BootAssetTests(unittest.TestCase):
    def test_complete_static_init_and_matching_module_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / "aarch64").mkdir()
            (base / "aarch64/ksuinit").write_bytes(elf_header(183, 2))
            (base / "aarch64/android12-5.10_kernelsu.ko").write_bytes(elf_header(183, 1))
            asset_check.check_assets(base, ["aarch64"], ["android12-5.10"])
            (base / "aarch64/ksuinit").unlink()
            with self.assertRaises(FileNotFoundError):
                asset_check.check_assets(base, ["aarch64"], ["android12-5.10"])

    def test_dynamic_init_cannot_be_shipped_as_early_boot_init(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ksuinit"
            path.write_bytes(elf_header(183, 3, interpreter=True))
            with self.assertRaisesRegex(ValueError, "statically linked"):
                asset_check.check_elf(path, 183)

    def test_wrong_architecture_or_executable_cannot_be_used_as_module(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "module.ko"
            for machine, kind in ((62, 1), (183, 2)):
                path.write_bytes(elf_header(machine, kind))
                with self.assertRaisesRegex(ValueError, "type or architecture"):
                    asset_check.check_elf(path, 183, module=True)

    def test_truncated_program_headers_fail_before_embedding(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ksuinit"
            path.write_bytes(elf_header(183, 2)[:80])
            with self.assertRaisesRegex(ValueError, "program header table"):
                asset_check.check_elf(path, 183)


if __name__ == "__main__":
    unittest.main(verbosity=2)
