"""Host regressions of extracted production functions (no kernel is executed)."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def function(text, name):
    match = re.search(r"^[^\n;{}]*\b" + name + r"\([^;{}]*\)\s*\{", text, re.MULTILINE)
    if not match:
        raise AssertionError(f"missing function {name}")
    end, depth = match.end(), 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[match.start():end]


def source(path):
    return (ROOT / "kernel" / path).read_text()


def stripped(text):
    return re.sub(r"^#include[^\n]*", "", text, flags=re.MULTILINE)


class Wiring(unittest.TestCase):
    def test_async_producers_and_final_puts(self):
        for path, callback in (("policy/allowlist.c", "do_persistent_allow_list"),
                               ("manager/pkg_observer.c", "ksu_track_throne_tw_func"),
                               ("supercall/supercall.c", "ksu_install_fd_tw_func")):
            text = source(path)
            self.assertNotRegex(text, r"(?<!ksu_)task_work_add\(")
            self.assertTrue(function(text, callback).rstrip().endswith("module_put(THIS_MODULE);\n}"))
        text = function(source("core/init.c"), "kernelsu_exit")
        self.assertLess(text.index("ksu_syscall_hook_manager_exit"), text.index("ksu_allowlist_exit"))
        self.assertGreater(text.index("ksu_syscall_lifecycle_exit"), text.index("put_cred"))

    def test_all_dispatchers_restore_drain_clear(self):
        for arch in ("arm64", "x86_64", "riscv64"):
            text = source(f"hook/{arch}/syscall_hook.c")
            body = function(text, "ksu_syscall_dispatcher")
            self.assertLess(body.index("ksu_syscall_enter"), body.index("orig_nr"))
            self.assertEqual(len(re.findall(r"\breturn\b", body)), 1)
            exit_body = function(text, "ksu_syscall_hook_exit")
            self.assertLess(exit_body.index("ksu_hook_restore_slot"), exit_body.index("ksu_syscall_synchronize"))
            self.assertLess(exit_body.index("ksu_syscall_synchronize"), exit_body.index("memset(syscall_hooks"))
        manager = function(source("hook/syscall_hook_manager.c"), "ksu_syscall_hook_manager_exit")
        self.assertNotIn("ksu_unregister_syscall_hook", manager)
        self.assertLess(manager.index("ksu_syscall_hook_exit"), manager.index("ksu_sucompat_exit"))
        for name in ("ksu_sys_read", "ksu_sys_fstat"):
            body = function(source("runtime/ksud_integration.c"), name)
            self.assertLess(body.index("ksu_syscall_enter"), body.index("orig_sys_"))
            self.assertIn("ksu_syscall_leave", body)

    def test_global_x86_entry_pinned_before_publication(self):
        body = function(source("hook/x86_64/syscall_hook.c"), "ksu_syscall_hook_init")
        self.assertLess(body.index("__module_get"), body.index("patch_abs_jump"))

    def test_runtime_producers_stop_before_work_and_data_teardown(self):
        text = source("runtime/ksud_integration.c")
        init = function(text, "ksu_ksud_init")
        self.assertLess(init.index("INIT_WORK"), init.index("ksu_syscall_table_hook"))
        self.assertLess(init.index("INIT_WORK"), init.index("input_hook_accepting = true"))
        self.assertLess(init.index("input_hook_accepting = true"), init.index("ret = register_kprobe"))
        exit_body = function(text, "ksu_ksud_exit")
        self.assertLess(exit_body.index("input_hook_accepting = false"), exit_body.index("unregister_input_hook();"))
        self.assertLess(exit_body.index("unregister_input_hook();"), exit_body.index("cancel_work_sync(&"))
        self.assertLess(exit_body.index("cancel_work_sync(&"), exit_body.index("free_module_rc();"))
        core = function(source("core/init.c"), "kernelsu_exit")
        self.assertLess(core.index("ksu_syscall_hook_manager_exit"), core.index("ksu_ksud_exit"))
        self.assertLess(core.index("ksu_supercalls_exit"), core.index("ksu_ksud_exit"))


def run_native():
    wrapper = source("infra/file_wrapper.c")
    create = function(wrapper, "ksu_create_file_wrapper")
    fields = sorted(set(re.findall(r"p->ops\.(\w+)", create)))
    callbacks = sorted(set(re.findall(r"\bksu_wrapper_\w+", create)) - {"ksu_wrapper_release"})
    with tempfile.TemporaryDirectory(prefix="kernel-lifetime-", dir=os.environ.get("TMPDIR", "/tmp/opencode")) as tmp:
        out = Path(tmp)
        (out / "wrapper_fields.inc").write_text("\n".join(f"void *{name};" for name in fields))
        (out / "wrapper_callbacks.inc").write_text("\n".join(f"#define {name} ((void *)1)" for name in callbacks))
        (out / "wrapper.inc").write_text("\n\n".join(function(wrapper, name) for name in (
            "ksu_wrapper_open", "ksu_wrapper_release", "ksu_create_file_wrapper", "ksu_release_file_wrapper",
            "ksu_wrapper_d_dname", "ksu_wrapper_d_release", "ksu_install_file_wrapper")))
        (out / "groups.inc").write_text(function(source("policy/app_profile.c"), "setup_groups"))
        (out / "task_work.inc").write_text(function(source("infra/task_work.h"), "ksu_task_work_add"))
        (out / "task_callbacks.inc").write_text("\n\n".join(function(source(path), name) for path, name in (
            ("policy/allowlist.c", "do_persistent_allow_list"),
            ("manager/pkg_observer.c", "ksu_track_throne_tw_func"),
            ("supercall/supercall.c", "ksu_install_fd_tw_func"))))
        (out / "filename.inc").write_text(function(source("selinux/sepolicy.c"), "add_filename_trans"))
        (out / "lifecycle.inc").write_text(stripped(source("hook/syscall_lifecycle.h")) + "\n" +
                                         stripped(source("hook/syscall_lifecycle.c")))
        (out / "synchronize.inc").write_text("\n".join(function(source("hook/lsm_hook.c"), name) for name in (
            "ksu_hook_synchronize_srcu", "ksu_hook_synchronize")))
        for arch in ("arm64", "x86_64", "riscv64"):
            text = source(f"hook/{arch}/syscall_hook.c")
            (out / f"{arch}.inc").write_text("\n\n".join(function(text, name) for name in (
                "ksu_syscall_dispatcher", "ksu_syscall_hook_exit")))
        (out / "riscv_regs.inc").write_text(stripped(source("hook/riscv64/syscall_regs.h")))
        runtime = source("runtime/ksud_integration.c")
        (out / "runtime_state.inc").write_text(
            runtime[runtime.index("static struct work_struct stop_input_hook_work;"):runtime.index("#define MAX_ARG_STRINGS")] +
            runtime[runtime.index("static ssize_t (*orig_read)"):runtime.index("// Prefer /metadata")] +
            runtime[runtime.index("static char *module_rc_buf;"):runtime.index("static struct file *open_module_rc")])
        (out / "runtime.inc").write_text("\n\n".join(function(runtime, name) for name in (
            "free_module_rc", "read_proxy", "read_iter_proxy", "release_proxy", "ksu_install_rc_hook",
            "unregister_input_hook", "do_stop_input_hook", "ksu_stop_input_hook_runtime", "ksu_ksud_init", "ksu_ksud_exit")))
        variants = [("ownership", [f"-DTEST_VERSION={v}"]) for v in (510, 601, 606, 612)]
        variants += [("filename", []), ("runtime", [])]
        variants += [("syscall", [f'-DTEST_ARCH="{arch}.inc"', f"-DTEST_{arch}=1", f"-DTEST_GROUP_DEAD={gd}"])
                     for arch in ("arm64", "x86_64", "riscv64") for gd in (0, 1)]
        for index, (name, defines) in enumerate(variants):
            exe = out / f"{name}-{index}"
            subprocess.run([os.environ.get("CC", "cc"), "-std=gnu11", "-O1", "-g", "-Wall", "-Wextra", "-Werror",
                            "-Wno-unused-parameter", "-Wno-unused-function", "-Wno-sign-compare", "-pthread",
                            "-fsanitize=address,undefined", "-fno-omit-frame-pointer", *defines,
                            "-I", str(out), str(ROOT / f"tests/kernel_lifetime/{name}_test.c"), "-o", str(exe)], check=True)
            subprocess.run([str(exe)], check=True, timeout=30)


if __name__ == "__main__":
    result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(Wiring))
    if not result.wasSuccessful():
        raise SystemExit(1)
    run_native()
