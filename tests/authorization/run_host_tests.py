"""Compile the production authorization state machine with the host C compiler."""
import os
from pathlib import Path
import subprocess
import tempfile
import sys

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix="lunexsu-auth-") as output:
    for name in ("state_test", "session_test", "persistence_test"):
        exe = Path(output) / name
        subprocess.run([
            os.environ.get("CC", "gcc"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
            "-pthread", "-I", str(root), str(root / f"tests/authorization/{name}.c"), "-o", str(exe)
        ], check=True)
        subprocess.run([str(exe)], check=True)
    exe = Path(output) / "compat_test"
    subprocess.run([os.environ.get("CXX", "g++"), "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-I", str(root),
                    str(root / "tests/authorization/compat_test.cc"), "-o", str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
subprocess.run([sys.executable, str(root / "tests/authorization/run_path_tests.py")], check=True)
subprocess.run([sys.executable, str(root / "tests/authorization/run_session_tracker_tests.py")], check=True)
subprocess.run([sys.executable, str(root / "tests/authorization/run_dispatch_tests.py")], check=True)
