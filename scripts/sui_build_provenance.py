"""Record inputs before Gradle compilation and bind them to its completed ZIP."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile

from sui_source_inputs import source_inputs
import sui_manager_binding


def proof_path(archive):
    return archive.with_name(archive.name + ".provenance.json")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix=".provenance-", delete=False) as output:
        temporary = Path(output.name)
        try:
            json.dump(value, output, indent=2, sort_keys=True)
            output.write("\n")
            output.close()
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def check_archive_path(source, archive):
    if (archive.parent.resolve() != (source / "out").resolve()
            or not archive.name.endswith("-release.zip") or archive.is_symlink()):
        raise ValueError("Expected a generated Sui release ZIP under source/out")


def begin(source, snapshot, archive, manager_binding):
    check_archive_path(source, archive)
    proof_path(archive).unlink(missing_ok=True)
    snapshot.unlink(missing_ok=True)
    # A failed/incomplete build must not leave an old ZIP available for a new
    # attestation. Only the ZIP is regenerated; Gradle may reuse compile outputs.
    archive.unlink(missing_ok=True)
    value = {
        "schema": 1,
        "archive_name": archive.name,
        "manager_binding": sui_manager_binding.validate(manager_binding),
        "source_inputs": source_inputs(source),
    }
    write_json(snapshot, value)
    return value


def finish(source, snapshot, archive, manager_binding):
    check_archive_path(source, archive)
    value = json.loads(snapshot.read_text(encoding="utf-8"))
    expected = {
        "schema": 1,
        "archive_name": archive.name,
        "manager_binding": sui_manager_binding.validate(manager_binding),
        "source_inputs": source_inputs(source),
    }
    if not isinstance(value, dict) or type(value.get("schema")) is not int or value != expected:
        raise ValueError("Sui source or Manager binding changed during the build")
    value["zip_sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
    write_json(proof_path(archive), value)
    snapshot.unlink()
    return value


def verify(source, archive, manager_binding):
    try:
        value = json.loads(proof_path(archive).read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError("Sui ZIP has no build-time provenance; run Gradle zipRelease") from error
    expected = {
        "schema": 1,
        "archive_name": archive.name,
        "manager_binding": sui_manager_binding.validate(manager_binding),
        "source_inputs": source_inputs(source),
        "zip_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
    }
    if not isinstance(value, dict) or type(value.get("schema")) is not int or value != expected:
        raise ValueError("Sui ZIP build-time provenance does not match the source or binding")
    return value


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("begin", "finish"))
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    sui_manager_binding.arguments(parser)
    args = parser.parse_args()
    operation = begin if args.phase == "begin" else finish
    operation(args.source, args.snapshot, args.archive, sui_manager_binding.from_arguments(args))
