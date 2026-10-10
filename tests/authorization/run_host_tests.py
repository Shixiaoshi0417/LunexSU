"""Compile the production authorization state machine with the host C compiler."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix="lunexsu-auth-") as output:
    for name in ("state_test", "persistence_test"):
        exe = Path(output) / name
        subprocess.run([
            os.environ.get("CC", "gcc"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-pthread", "-I", str(root), str(root / f"tests/authorization/{name}.c"), "-o", str(exe)
        ], check=True)
        subprocess.run([str(exe)], check=True)
