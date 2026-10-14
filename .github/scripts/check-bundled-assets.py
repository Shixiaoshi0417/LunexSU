"""Validate boot-patching prerequisites before embedding a release daemon."""
import argparse
from pathlib import Path
import struct

MACHINES = {"aarch64": 183, "x86_64": 62, "riscv64": 243}


def check_elf(path, machine, module=False):
    data = path.read_bytes()
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"Missing or invalid ELF64 asset: {path}")
    kind, actual_machine = struct.unpack_from("<HH", data, 16)
    if actual_machine != machine or kind not in ((1,) if module else (2, 3)):
        raise ValueError(f"Wrong ELF type or architecture: {path}")
    if not module:
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        if count and (size < 56 or offset + size * count > len(data)):
            raise ValueError(f"Invalid program header table: {path}")
        for index in range(count):
            if struct.unpack_from("<I", data, offset + index * size)[0] == 3:
                raise ValueError(f"ksuinit must be statically linked: {path}")


def check_assets(base, architectures, kmis):
    for arch in architectures:
        folder = base / arch
        check_elf(folder / "ksuinit", MACHINES[arch])
        for kmi in kmis:
            check_elf(folder / f"{kmi}_kernelsu.ko", MACHINES[arch], module=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=Path("userspace/ksud/bin"))
    parser.add_argument("--arch", choices=MACHINES, action="append", required=True)
    parser.add_argument("--kmi", action="append", required=True)
    args = parser.parse_args()
    check_assets(args.base, args.arch, args.kmi)
    print("Bundled LKM and static ksuinit assets verified")
