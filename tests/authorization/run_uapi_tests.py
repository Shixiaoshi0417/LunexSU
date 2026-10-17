"""Check fork-specific version gates and the root-only lookup dispatch entry."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[2]
for path, constant in (
    ("userspace/ksud/src/debug.rs", "LOOKUP_SYMBOL_UAPI_VERSION"),
    ("userspace/ksud/src/module.rs", "PREINIT_VOLD_UAPI_VERSION"),
):
    source = (root / path).read_text()
    match = re.search(rf"const {constant}: u32 = (\d+);", source)
    assert match and int(match[1]) == 8, (path, "old Lunex UAPI 6/7 must not enable upstream UAPI 6 features")
    assert f"ksucalls::get_info().uapi_version >= {constant}" in source

dispatch = (root / "kernel/supercall/dispatch.c").read_text()
entry = re.search(r"\{\s*\.cmd = KSU_IOCTL_LOOKUP_SYMBOLS,(.*?)\n\s*\}", dispatch, re.S)
assert entry and ".perm_check = only_root" in entry[1]
assert ".allow_su_session" not in entry[1]
assert "cmd == ksu_ioctl_handlers[i].cmd" in dispatch

preinit = root / "manager/app/src/main/java/com/shixiaoshi0417/lunexsu/ui/screen/module/ModulePreinit.kt"
assert "package com.shixiaoshi0417.lunexsu.ui.screen.module" in preinit.read_text()
print("PASS: new symbol/preinit gates require Lunex UAPI v8; symbol lookup remains root-only")
