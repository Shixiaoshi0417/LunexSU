"""Public Manager identity shared by Sui source builds and daemon packaging."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.shixiaoshi0417.lunexsu"


def binding(package=None, size=None, digest=None):
    defaults = (ROOT / "kernel/Kbuild").read_text(encoding="utf-8")
    if size is None:
        size = re.search(r"^KSU_EXPECTED_SIZE \?= (\S+)$", defaults, re.MULTILINE)[1]
    if digest is None:
        digest = re.search(r"^KSU_EXPECTED_HASH \?= (\S+)$", defaults, re.MULTILINE)[1]
    if isinstance(size, str):
        size = int(size, 16 if size.startswith("0x") else 10)
    package = PACKAGE if package is None else package
    if (package not in (PACKAGE, PACKAGE + ".pr") or type(size) is not int
            or not 1 <= size <= 1024 or not isinstance(digest, str)
            or re.fullmatch(r"[0-9a-f]{64}", digest) is None):
        raise ValueError("Invalid public Sui/Core Manager binding")
    return dict(package=package, certificate_size=size, certificate_sha256=digest)


def arguments(parser):
    parser.add_argument("--manager-package")
    parser.add_argument("--manager-size")
    parser.add_argument("--manager-hash")


def validate(value):
    if not isinstance(value, dict) or set(value) != {"package", "certificate_size", "certificate_sha256"}:
        raise ValueError("Missing or malformed Sui Manager binding")
    normalized = binding(value["package"], value["certificate_size"], value["certificate_sha256"])
    if normalized != value:
        raise ValueError("Noncanonical Sui Manager binding")
    return normalized


def from_arguments(args):
    return binding(args.manager_package, args.manager_size, args.manager_hash)
