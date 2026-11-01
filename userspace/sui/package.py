"""Map a Sui release zip into ksud's RustEmbed payload layout under bin/<bindir>/sui."""
import argparse
import hashlib
import json
import os
import shutil
import stat
import struct
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

TARGETS = {
    "aarch64": ("arm64-v8a", "armeabi-v7a"),
    "armv7": ("armeabi-v7a", None),
    "x86_64": ("x86_64", "x86"),
    "i686": ("x86", None),
}

ABI_ELF = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86_64": (2, 62),
    "x86": (1, 3),
}

PRIMARY_FILES = (
    ("bin/sui", "libmain.so", 0o755),
    ("bin/sepolicy_checker", "libsepolicy_checker.so", 0o755),
    ("librish.so", "librish.so", 0o644),
    ("zygisk.so", "libsui.so", 0o644),
)

SHARED_FILES = (
    ("sui.dex", "sui.dex", 0o600),
    ("sepolicy.rule", "sepolicy.rule", 0o644),
    ("rish", "rish", 0o770),
)

NOTICES = ("LICENSE", "UPSTREAM.md", "api/LICENSE", "SOURCE.lock.json", "LUNEXSU.md")
MAX_ENTRIES = 2048
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024


def validate_archive(archive):
    entries = archive.infolist()
    if len(entries) > MAX_ENTRIES:
        raise ValueError("Too many Sui archive entries")
    names = set()
    total = 0
    for entry in entries:
        name = entry.orig_filename
        path = PurePosixPath(name)
        if (not name or name != entry.filename or name.startswith("/") or "\\" in name or ":" in name
                or any(ord(char) < 32 for char in name)
                or any(part in ("", ".", "..") for part in name.rstrip("/").split("/"))
                or path.as_posix() != name.rstrip("/")):
            raise ValueError(f"Unsafe Sui archive path: {name}")
        folded = name.rstrip("/").casefold()
        if folded in names:
            raise ValueError(f"Duplicate Sui archive entry: {name}")
        names.add(folded)
        kind = stat.S_IFMT(entry.external_attr >> 16)
        if kind not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise ValueError(f"Special Sui archive entry: {name}")
        if entry.flag_bits & 1 or entry.file_size > MAX_FILE_BYTES:
            raise ValueError(f"Encrypted or oversized Sui archive entry: {name}")
        total += entry.file_size
        if total > MAX_TOTAL_BYTES:
            raise ValueError("Sui archive exceeds the size limit")


def validate_elf(data, elf_class, machine, name):
    if (len(data) < 20 or data[:4] != b"\x7fELF" or data[4] != elf_class
            or data[5] != 1
            or struct.unpack_from("<H", data, 16)[0] != 3
            or struct.unpack_from("<H", data, 18)[0] != machine):
        raise ValueError(f"Invalid Android ELF architecture/type: {name}")


def package(zip_path, assets, target, source_dir, version, version_code, force=False):
    if target not in TARGETS:
        raise ValueError(f"Unsupported Sui target: {target}")
    if (not version or len(version) > 128 or not version.isascii()
            or any(ord(char) < 33 or ord(char) > 126 for char in version)
            or not isinstance(version_code, int) or isinstance(version_code, bool)
            or not 0 < version_code <= 0x7fffffff):
        raise ValueError("Invalid Sui payload version")
    abi, secondary = TARGETS[target]
    elf_class, machine = ABI_ELF[abi]
    destination = assets / "sui"
    if assets.is_symlink() or destination.is_symlink():
        raise ValueError("Refusing a symlink payload destination")
    if (destination.exists() or destination.is_symlink()) and not force:
        raise FileExistsError(f"Refusing to overwrite existing package: {destination}")

    files = []
    with zipfile.ZipFile(zip_path) as archive:
        validate_archive(archive)
        names = set(archive.namelist())

        def read(name):
            if name not in names:
                raise ValueError(f"Missing {name} in {zip_path.name}")
            data = archive.read(name)
            if not data:
                raise ValueError(f"Empty Sui archive member: {name}")
            return data

        for relative, source, mode in PRIMARY_FILES:
            name = f"lib/{abi}/{source}"
            data = read(name)
            validate_elf(data, elf_class, machine, name)
            files.append((Path(relative), data, mode))

        if secondary:
            secondary_class, secondary_machine = ABI_ELF[secondary]
            name = f"lib/{secondary}/libsui.so"
            data = read(name)
            validate_elf(data, secondary_class, secondary_machine, name)
            files.append((Path(f"zygisk.{secondary}.so"), data, 0o644))

        for relative, source, mode in SHARED_FILES:
            files.append((Path(relative), read(source), mode))

    for relative in NOTICES:
        source = source_dir / relative
        if not source.is_file() or not source.read_bytes():
            raise ValueError(f"Missing upstream notice: {source}")
        files.append((Path("licenses") / relative.replace("/", "_"), source.read_bytes(), 0o644))

    manifest = {
        "schema": 1,
        "version": version,
        "version_code": version_code,
        "primary_abi": abi,
        "secondary_abi": secondary,
        "files": [
            {
                "name": relative.as_posix(),
                "mode": mode,
                "sha256": hashlib.sha256(data).hexdigest(),
            }
            for relative, data, mode in files
        ],
    }

    assets.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".sui-", dir=assets) as temporary:
        root = Path(temporary) / "sui"
        for relative, data, mode in files:
            output = root / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
            output.chmod(mode)
        (root / "payload.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        (root / "payload.json").chmod(0o644)
        stale = None
        if destination.exists() or destination.is_symlink():
            stale = destination.with_name(f".sui-stale-{os.getpid()}")
            if stale.exists() or stale.is_symlink():
                raise FileExistsError(f"Retaining previous package recovery directory: {stale}")
            destination.rename(stale)
        try:
            root.rename(destination)
        except OSError:
            if stale is not None:
                stale.rename(destination)
            raise
        if stale is not None:
            shutil.rmtree(stale, ignore_errors=True)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--source", type=Path, default=Path(__file__).parent)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--force", action="store_true",
                         help="replace a build output (not an Android runtime directory)")
    args = parser.parse_args()
    print(package(args.zip, args.assets, args.target, args.source, args.version,
                  args.version_code, args.force))
