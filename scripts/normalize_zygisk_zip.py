#!/usr/bin/env python3
"""Repack a pinned ZIP using stored entries, preserving every file's bytes.

ZygiskNextNext uses ZIP/XZ (method 95), which BusyBox unzip cannot read. ksud
extracts the archive first, but customize.sh still calls unzip for verification.
Use a deterministic, universally supported container; do not change payloads.
"""

import io
import lzma
import struct
import sys
import zipfile
import zlib


def normalize(source, destination):
    raw = source.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive, zipfile.ZipFile(destination, "w") as output:
        for entry in sorted(archive.infolist(), key=lambda item: item.filename):
            if entry.compress_type == 95:
                offset = entry.header_offset
                assert raw[offset:offset + 4] == b"PK\x03\x04"
                name_len, extra_len = struct.unpack_from("<HH", raw, offset + 26)
                start = offset + 30 + name_len + extra_len
                data = lzma.decompress(raw[start:start + entry.compress_size])
                assert len(data) == entry.file_size
                assert zlib.crc32(data) == entry.CRC
            else:
                data = archive.read(entry)
            info = zipfile.ZipInfo(entry.filename, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o100644 << 16)
            output.writestr(info, data, compress_type=zipfile.ZIP_STORED)


if __name__ == "__main__":
    from pathlib import Path
    normalize(Path(sys.argv[1]), Path(sys.argv[2]))
