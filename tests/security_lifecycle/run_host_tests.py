"""Compile pure production lifecycle helpers; never run Android/device commands."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix="lunexsu-lifecycle-", dir=os.environ.get("TMPDIR", str(root))) as tmp:
    executable = Path(tmp) / ("lifecycle.exe" if os.name == "nt" else "lifecycle")
    subprocess.run(["rustc", "--edition=2024", "--test", str(root / "tests/security_lifecycle/host.rs"),
                    "-D", "warnings", "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
