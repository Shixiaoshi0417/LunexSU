"""Host-only fault tests of the actual production transaction functions.

Extract function bodies, not reimplementations. Kernel allocation/bitmap/RCU
primitives are mocked, so this does not replace a matching-kernel build or
kernel fault injection. All output follows TMPDIR (set it to a disposable D:
directory when running through WSL).
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
sepolicy = (root / "kernel/selinux/sepolicy.c").read_text()
rules = (root / "kernel/selinux/rules.c").read_text()


def body(source, signature):
    start = source.index(signature + "\n{")
    end = source.index("\n}\n", start) + len("\n}\n")
    return source[start:end]


with tempfile.TemporaryDirectory(prefix="lunexsu-policy-", dir=os.environ.get("TMPDIR", str(root))) as tmp:
    out = Path(tmp)
    env = dict(os.environ, TMPDIR=str(out))
    (out / "production_type.inc").write_text(body(
        sepolicy, "static bool add_type(struct policydb *db, const char *type_name, bool attr)"))
    start = rules.index("#define KSU_SEPOLICY_MAX_BATCH_SIZE")
    end = rules.index("static int apply_one_sepolicy_cmd", start)
    (out / "production_batch.inc").write_text(
        rules[start:end] + body(rules, "int handle_sepolicy(void __user *user_data, u64 data_len)"))
    (out / "production_builtin.inc").write_text(body(rules, "void apply_kernelsu_rules()"))
    for name in ("type_transaction_test", "batch_transaction_test"):
        exe = out / name
        subprocess.run([
            os.environ.get("CC", "gcc"), "-std=c11", "-Wall", "-Wextra", "-Werror", "-O1",
            "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-g",
            "-I", str(out), "-I", str(root),
            str(root / f"tests/selinux/{name}.c"), "-o", str(exe),
        ], check=True, env=env)
        subprocess.run([str(exe)], check=True, env=env)
