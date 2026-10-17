"""Host tests of production hook code with mocked kernel patch/grace primitives.

Run with Python on a POSIX host, or set CC to a suitable host C compiler.
No kernel is built or executed. Kernel primitives and hardware ordering still
require target-kernel validation; these tests check our lifecycle protocol.
"""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
LSM = ROOT / "kernel/hook/lsm_hook.c"
HIDE = ROOT / "kernel/feature/selinux_hide.c"


def without_includes(text):
    return re.sub(r"^#include[^\n]*", "", text, flags=re.MULTILINE)


def function(text, name):
    """Extract a production function definition, excluding forward declarations."""
    match = re.search(r"^[^\n;{}]*\b" + name + r"\([^;{}]*\)\s*\{", text, re.MULTILINE)
    if not match:
        raise AssertionError(f"missing function {name}")
    start = match.start()
    pos = match.end()
    depth = 1
    while depth:
        depth += (text[pos] == "{") - (text[pos] == "}")
        pos += 1
    return text[start:pos]


class StructuralTests(unittest.TestCase):
    def test_all_callbacks_have_sleepable_read_sections(self):
        text = HIDE.read_text()
        for name in ("my_write_context", "my_write_access", "my_setprocattr", "my_sel_open_handle_status"):
            body = function(text, name)
            self.assertLess(body.index("ksu_hook_read_lock"), body.index("= handle_"))
            self.assertLess(body.index("= handle_"), body.index("srcu_read_unlock"))
        reader = function((ROOT / "kernel/hook/lsm_hook.h").read_text(), "ksu_hook_read_lock")
        self.assertLess(reader.index("srcu_read_lock"), reader.index("smp_rmb"))

    def test_restore_then_drain_then_clear(self):
        body = function(HIDE.read_text(), "ksu_selinux_hide_unhook")
        for flag in ("context_write_installed", "access_write_installed", "status_open_installed"):
            self.assertIn(f"if ({flag})", body)
        self.assertLess(body.rindex("ksu_hook_restore_slot"), body.index("ksu_hook_synchronize"))
        self.assertLess(body.index("ksu_hook_synchronize"), body.index("smp_store_release"))
        restore = function(LSM.read_text(), "ksu_hook_restore_slot")
        self.assertIn("while (READ_ONCE(*slot) != original)", restore)

    def test_default_off_exit_unconditionally_unhooks(self):
        body = function(HIDE.read_text(), "ksu_selinux_hide_exit")
        self.assertNotIn("if (ksu_selinux_hide_running)", body)
        self.assertLess(body.index("ksu_unregister_feature_handler"), body.index("mutex_lock(&selinux_hide_mutex)"))
        self.assertLess(body.index("ksu_selinux_hide_disable"), body.index("__free_page"))

    def test_fake_files_pin_module_and_operations_are_immutable(self):
        text = HIDE.read_text()
        body = function(text, "handle_sel_open_status")
        self.assertLess(body.index("fops_get(&fake_status_ops)"), body.index("filp->private_data = fake_status"))
        self.assertIn("replace_fops(filp, ops)", body)
        install = function(text, "hook_selinux_status_open")
        self.assertIn("if (!fake_status_ops_initialized)", install)
        self.assertIn("fake_status_ops.owner = THIS_MODULE", install)
        self.assertIn("fake_status_ops.open = NULL", install)

    def test_originals_published_before_both_lsm_variants(self):
        body = function(LSM.read_text(), "ksu_lsm_hook")
        self.assertEqual(body.count("smp_store_release(&hook->original, selected_origin)"), 2)
        for variant in body.split("smp_store_release(&hook->original, selected_origin);")[1:]:
            self.assertLess(variant.index("ksu_lsm_hook_patch_slot"), variant.index("pr_info"))


def run_native():
    header = without_includes((ROOT / "kernel/hook/lsm_hook.h").read_text())
    source = without_includes(LSM.read_text())
    hide = HIDE.read_text()
    # Exercise the actual selinux teardown/feature/exit code, not a parallel
    # Python state model. Large policy parsing functions are intentionally not
    # part of this focused host harness.
    hide_parts = [function(hide, name) for name in (
        "ksu_selinux_hide_unhook", "ksu_selinux_hide_disable", "selinux_hide_feature_set", "ksu_selinux_hide_exit",
        "handle_sel_open_status", "my_sel_open_handle_status", "hook_selinux_status_open"
    )]
    test = (ROOT / "tests/hooks/lifecycle_test.c").read_text()
    generated = test.replace("/* PRODUCTION_LSM_HEADER */", header).replace("/* PRODUCTION_LSM_SOURCE */", source)
    generated = generated.replace("/* PRODUCTION_HIDE_LIFECYCLE */", "\n\n".join(hide_parts))
    with tempfile.TemporaryDirectory(prefix="lunexsu-hooks-") as temp:
        src = Path(temp) / "hook_test.c"
        src.write_text(generated)
        for version in (510, 612):
            exe = Path(temp) / f"hooks-{version}"
            subprocess.run([os.environ.get("CC", "cc"), "-std=gnu11", "-O2", "-Wall", "-Wextra",
                            "-Werror", "-Wno-sign-compare", "-Wno-unused-function", "-pthread",
                            f"-DTEST_VERSION={version}", str(src), "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True, timeout=20)


if __name__ == "__main__":
    result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(StructuralTests))
    if not result.wasSuccessful():
        raise SystemExit(1)
    run_native()
