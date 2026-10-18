"""Hash source inputs, not generated output or machine-local SDK settings."""
import hashlib

EXCLUDED_DIRECTORIES = {".git", ".gradle", ".idea", ".cxx", "build", "out", "__pycache__", ".externalNativeBuild"}
EXCLUDED_FILES = {"local.properties", ".DS_Store"}


def source_inputs(source):
    return {
        path.relative_to(source).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(source.rglob("*"))
        if path.is_file() and not path.is_symlink()
        and not any(part in EXCLUDED_DIRECTORIES for part in path.relative_to(source).parts)
        and path.name not in EXCLUDED_FILES and path.suffix != ".pyc"
    }
