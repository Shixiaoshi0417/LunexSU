"""Stage the same source-build Sui artifact into supported embedded ABI trees."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

from sui_source_inputs import source_inputs
from prepare_sui_artifact import locked_version
import sui_manager_binding


def stage(artifact, source, assets, manager_binding=None):
    manager_binding = sui_manager_binding.binding() if manager_binding is None else manager_binding
    metadata = json.loads((artifact / "metadata.json").read_text(encoding="utf-8"))
    manager_binding = sui_manager_binding.validate(manager_binding)
    sui_manager_binding.validate(metadata.get("manager_binding"))
    lock = json.loads((source / "SOURCE.lock.json").read_text(encoding="utf-8"))
    version = locked_version(source)
    archive = artifact / "sui.zip"
    if (type(metadata.get("schema")) is not int or metadata.get("schema") != 1
            or metadata.get("manager_binding") != manager_binding
            or metadata.get("version") != version["version"]
            or type(metadata.get("version_code")) is not int
            or metadata.get("version_code") != int(version["version_code"])
            or metadata.get("source_commit") != lock["commit"]
            or metadata.get("source_inputs") != source_inputs(source)
            or hashlib.sha256(archive.read_bytes()).hexdigest() != metadata.get("zip_sha256")):
        raise ValueError("Sui artifact provenance or digest mismatch")
    if (assets / "riscv64/sui").exists():
        raise ValueError("Unexpected unsupported RISC-V Sui payload")
    spec = importlib.util.spec_from_file_location("sui_package", source / "package.py")
    package = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(package)
    for target in ("aarch64", "x86_64"):
        package.package(archive, assets / target, target, source,
                        metadata["version"], metadata["version_code"], force=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    sui_manager_binding.arguments(parser)
    args = parser.parse_args()
    stage(args.artifact, args.source, args.assets, sui_manager_binding.from_arguments(args))
