"""Compile the production GET handler with policy both enabled and disabled."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "kernel/supercall/dispatch.c").read_text()
handler = source.split("static int do_get_auth_profile(void __user *arg)", 1)[1]
handler = "static int do_get_auth_profile(void __user *arg)" + handler.split(
    "static int do_set_auth_profile", 1)[0]
stub = r'''
#include <assert.h>
#include <errno.h>
#include <string.h>
#define __user
struct ksu_auth_profile_cmd { struct { char key[16]; } profile; };
static int reads, writes, queries;
static int copy_from_user(void *dst, void *src, size_t size) {
    reads++; if (!src) return 1; memcpy(dst, src, size); return 0;
}
static int copy_to_user(void *dst, void *src, size_t size) {
    writes++; memcpy(dst, src, size); return 0;
}
static int ksu_get_auth_profile(struct ksu_auth_profile_cmd *cmd) {
    queries++; return cmd->profile.key[0] ? 0 : -ENOENT;
}
'''
test = r'''
int main(void) {
    struct ksu_auth_profile_cmd cmd = { .profile.key = "app" };
#ifdef CONFIG_KSU_DISABLE_POLICY
    assert(do_get_auth_profile(&cmd) == -EOPNOTSUPP);
    assert(do_get_auth_profile(NULL) == -EOPNOTSUPP);
    assert(reads == 0 && writes == 0 && queries == 0);
#else
    assert(do_get_auth_profile(NULL) == -EFAULT);
    assert(do_get_auth_profile(&cmd) == 0);
    cmd.profile.key[0] = 0;
    assert(do_get_auth_profile(&cmd) == -ENOENT);
    memset(cmd.profile.key, 'x', sizeof(cmd.profile.key));
    assert(do_get_auth_profile(&cmd) == -EINVAL);
    assert(queries == 2 && writes == 1);
#endif
}
'''
with tempfile.TemporaryDirectory(prefix="lunexsu-dispatch-") as output:
    path = Path(output)
    (path / "test.c").write_text(stub + handler + test)
    for disabled in (False, True):
        exe = path / f"dispatch-{disabled}"
        subprocess.run([os.environ.get("CC", "gcc"), "-std=gnu11", "-Wall", "-Wextra",
                        "-Werror", "-Wno-unused-function", "-Wno-unused-parameter",
                        *(["-DCONFIG_KSU_DISABLE_POLICY"] if disabled else []),
                        str(path / "test.c"), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)
print("PASS: production auth GET reports unsupported without policy; enabled GET preserves errors")
