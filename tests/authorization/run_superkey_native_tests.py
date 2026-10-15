"""Exercise the production native superkey bridge without an Android device."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / "manager/app/src/main/cpp/ksu.cc").read_text()
bridge = source.split("template<typename... Args>", 1)[1]
bridge = "template<typename... Args>" + bridge.split("struct ksu_get_info_cmd get_info()", 1)[0]
stub = r'''
#include <cassert>
#include <cstdint>
#include <cstring>
#include <utility>
struct ksu_get_info_cmd { unsigned version; };
struct ksu_superkey_salt_cmd { uint8_t salt[16]; };
struct ksu_superkey_auth_cmd { uint8_t proof[32]; };
enum { KSU_IOCTL_SUPERKEY_SALT = 150, KSU_IOCTL_SUPERKEY_AUTH = 151 };
static int fd = -1, available_fd = -1, wiped, attempts;
static bool accepted, manager;
static int scan_driver_fd() { return available_fd; }
static bool is_manager() { return manager; }
static void clear_superkey(void *p, size_t size) { ++wiped; memset(p, 0, size); }
// A fallback to reboot(2) can kill an unprivileged app under Android seccomp.
long syscall(long, ...) = delete;
static int ioctl(int driver, unsigned long op, void *p) {
    ++attempts;
    if (driver < 0) return -1;
    assert(driver == 42);
    if (op == KSU_IOCTL_SUPERKEY_SALT) {
        memset(static_cast<ksu_superkey_salt_cmd *>(p)->salt, 7, 16);
        return 0;
    }
    assert(op == KSU_IOCTL_SUPERKEY_AUTH);
    assert(static_cast<ksu_superkey_auth_cmd *>(p)->proof[0] == 9);
    return accepted ? 0 : -1;
}
'''
test = r'''
int main() {
    uint8_t salt[16] = {}, proof[32] = {9};
    assert(!superkey_salt(salt));
    assert(salt[0] == 0 && attempts == 1);
    g_version.version = 123;
    assert(!superkey_auth(proof));
    assert(g_version.version == 0 && wiped == 1);
    assert(proof[0] == 9); // JNI owns and clears the input buffer separately.
    available_fd = 42;
    assert(superkey_salt(salt) && salt[0] == 7);
    accepted = true;
    manager = false;
    assert(!superkey_auth(proof)); // An ioctl result alone never grants Manager.
    manager = true;
    assert(superkey_auth(proof));
    accepted = false;
    assert(!superkey_auth(proof));
    assert(wiped == 4);
}
'''
with tempfile.TemporaryDirectory(prefix="lunexsu-superkey-native-") as output:
    path = Path(output)
    (path / "test.cc").write_text(stub + bridge + test)
    subprocess.run([os.environ.get("CXX", "g++"), "-std=c++17", "-Wall", "-Wextra",
                    "-Werror", str(path / "test.cc"), "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
print("PASS: native superkey bridge fails safely without Core and refreshes authenticated identity")
