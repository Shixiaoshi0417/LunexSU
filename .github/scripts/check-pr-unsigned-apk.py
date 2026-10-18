"""Reject signed or malformed APKs before publishing compile-only PR artifacts."""
import argparse
from pathlib import Path
import struct
import zipfile


def verify_unsigned(path):
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        if not {"AndroidManifest.xml", "classes.dex"} <= names:
            raise ValueError("Missing APK manifest or dex")
        if archive.testzip() is not None:
            raise ValueError("Corrupt APK entry")
        for name in names:
            upper = name.upper()
            if upper.startswith("META-INF/") and upper.endswith((".SF", ".RSA", ".DSA", ".EC")):
                raise ValueError("APK contains a JAR signing entry")

    # APK v2/v3 signatures are outside ZIP entries, immediately before the
    # central directory. These release APKs use ordinary, single-disk ZIPs.
    with path.open("rb") as stream:
        stream.seek(0, 2)
        size = stream.tell()
        tail_size = min(size, 22 + 65535)
        stream.seek(size - tail_size)
        tail = stream.read(tail_size)
        offset = tail.rfind(b"PK\x05\x06")
        if offset < 0 or len(tail) - offset < 22:
            raise ValueError("Missing ZIP end record")
        _, disk, directory_disk, disk_entries, entries, directory_size, directory_offset, comment_size = struct.unpack_from("<4s4H2IH", tail, offset)
        if (disk or directory_disk or disk_entries != entries or entries == 0xffff
                or len(tail) - offset != 22 + comment_size
                or directory_offset + directory_size != size - tail_size + offset):
            raise ValueError("Unsupported or inconsistent ZIP directory")
        if directory_offset >= 16:
            stream.seek(directory_offset - 16)
            if stream.read(16) == b"APK Sig Block 42":
                raise ValueError("APK contains an APK signing block")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    paths = sorted(args.directory.glob("*.apk"))
    if not paths:
        raise SystemExit("No PR APKs found")
    for path in paths:
        verify_unsigned(path)
        print(f"Unsigned PR APK: {path.name}")


if __name__ == "__main__":
    main()
