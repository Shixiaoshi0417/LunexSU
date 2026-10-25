"""Collect a checked local source build; never downloads or installs Sui."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import zipfile

from sui_source_inputs import source_inputs
import sui_manager_binding
from sui_build_provenance import verify as verify_build_provenance


def locked_version(source):
    return dict(line.strip().split("=", 1) for line in
                (source / "payload-version.txt").read_text(encoding="utf-8").splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def prepare(source, output, manager_binding=None):
    manager_binding = sui_manager_binding.binding() if manager_binding is None else manager_binding
    manager_binding = sui_manager_binding.validate(manager_binding)
    if output.exists():
        raise FileExistsError(output)
    archives = list((source / "out").glob("*-release.zip"))
    if len(archives) != 1:
        raise ValueError(f"Expected exactly one Sui release ZIP, found {len(archives)}")
    provenance = verify_build_provenance(source, archives[0], manager_binding)
    version = locked_version(source)
    spec = importlib.util.spec_from_file_location("sui_package", source / "package.py")
    package = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(package)
    # The ARM64 and x86_64 packages each validate their matching 32-bit library.
    with tempfile.TemporaryDirectory(prefix="lunex-sui-verify-") as temporary:
        for target in ("aarch64", "x86_64"):
            package.package(archives[0], Path(temporary) / target, target, source,
                            version["version"], int(version["version_code"]))
    with zipfile.ZipFile(archives[0]) as archive:
        # The production certificate pin must actually be present in the compiled DEX.
        # Invalid one-byte PR identities may be constant-folded to a permanently closed gate.
        if manager_binding["certificate_size"] > 1 and manager_binding["certificate_sha256"].encode("ascii") not in archive.read("sui.dex"):
            raise ValueError("Compiled Sui DEX does not carry the declared Manager certificate pin")
        if archive.getinfo("module.prop").file_size > 8192:
            raise ValueError("Oversized module identity")
        properties = dict(line.strip().split("=", 1) for line in
                          archive.read("module.prop").decode("utf-8").splitlines()
                          if "=" in line and not line.lstrip().startswith("#"))
    expected_name = f'v{version["version"]} ({version["version_code"]}-{version["upstream_commit"][:8]}-release)'
    if (properties.get("id") != "lunexsu-sui" or properties.get("version") != expected_name
            or properties.get("versionCode") != version["version_code"]):
        raise ValueError("Built module identity does not match the locked source")
    metadata = {
        "schema": 1,
        "manager_binding": manager_binding,
        "version": version["version"],
        "version_code": int(version["version_code"]),
        "zip_sha256": provenance["zip_sha256"],
        "source_commit": json.loads((source / "SOURCE.lock.json").read_text(encoding="utf-8"))["commit"],
        "source_inputs": provenance["source_inputs"],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".sui-artifact-", dir=output.parent) as temporary:
        staged = Path(temporary) / "payload"
        staged.mkdir()
        shutil.copyfile(archives[0], staged / "sui.zip")
        if (hashlib.sha256((staged / "sui.zip").read_bytes()).hexdigest() != metadata["zip_sha256"]
                or source_inputs(source) != metadata["source_inputs"]):
            raise ValueError("Sui ZIP or source changed while preparing the artifact")
        (staged / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        staged.rename(output)
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    sui_manager_binding.arguments(parser)
    args = parser.parse_args()
    result = prepare(args.source, args.output, sui_manager_binding.from_arguments(args))
    print(json.dumps({key: value for key, value in result.items() if key != "source_inputs"}))
