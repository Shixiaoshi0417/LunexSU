"""Hash source inputs, not generated output or machine-local SDK settings."""
import hashlib

EXCLUDED_DIRECTORIES = {".git", ".gradle", ".kotlin", ".idea", ".cxx", "build", "out", "__pycache__", ".externalNativeBuild"}
EXCLUDED_FILES = {"local.properties", ".DS_Store"}


def source_inputs(source):
    inputs = {}
    for path in sorted(source.rglob("*")):
        relative = path.relative_to(source)
        if (any(part in EXCLUDED_DIRECTORIES for part in relative.parts)
                or path.name in EXCLUDED_FILES or path.suffix == ".pyc"):
            continue
        if path.is_symlink():
            raise ValueError(f"Sui source provenance cannot omit linked input: {relative}")
        if path.is_file():
            inputs[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return inputs
