"""Compile the production authorization state machine with the host C compiler."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix="lunexsu-auth-") as output:
    exe = Path(output) / "state_test"
    subprocess.run([
        os.environ.get("CC", "gcc"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-pthread", "-I", str(root), str(root / "tests/authorization/state_test.c"), "-o", str(exe)
    ], check=True)
    subprocess.run([str(exe)], check=True)
