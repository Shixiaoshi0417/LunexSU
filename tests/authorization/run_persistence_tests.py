"""Independent host-only tests for pending-marker recovery.

Compile the production pure helpers and extracted production kernel I/O
functions. Kernel file/credential calls are mocked in guard_io_test; the
persistence test uses only disposable host files. TMPDIR controls all output.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "kernel/policy/allowlist.c").read_text()


def body(signature):
    start = source.index(signature + "\n{")
    end = source.index("\n}\n", start) + len("\n}\n")
    return source[start:end]


with tempfile.TemporaryDirectory(prefix="lunexsu-persistence-", dir=os.environ.get("TMPDIR", str(root))) as tmp:
    out = Path(tmp)
    env = dict(os.environ, TMPDIR=str(out))
    (out / "production_guard_io.inc").write_text(
        body("static int prepare_allowlist_store(void *ctx)") +
        body("static bool read_allowlist_guard(struct ksu_auth_guard *guard)"))
    for name in ("persistence_test", "guard_io_test"):
        exe = out / name
        subprocess.run([
            os.environ.get("CC", "gcc"), "-std=c11", "-O1", "-Wall", "-Wextra", "-Werror",
            "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-g",
            "-I", str(out), "-I", str(root), str(root / f"tests/authorization/{name}.c"), "-o", str(exe),
        ], check=True, env=env)
        subprocess.run([str(exe)], check=True, env=env)
