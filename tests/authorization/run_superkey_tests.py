"""Exercise production superkey/identity gates with mocked kernel plumbing.

SHA-256 is mocked here; Manager's JVM tests check PBKDF2/verifier test vectors.
This checks authorization boundaries, not real APK recognition, seccomp or IOCTL
delivery. Those still need the matching Core and signed Manager on a device.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]


def without_includes(path):
    return "\n".join(line for line in path.read_text().splitlines()
                     if not line.startswith("#include"))


stub = r'''
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#define __user
#define __ro_after_init
#define READ_ONCE(x) (x)
#define WRITE_ONCE(x, value) ((x) = (value))
#define unlikely(x) (x)
#define DEFINE_MUTEX(name) int name
#define GFP_KERNEL 0
#define HZ 100
#define IS_ERR(ptr) ((intptr_t)(ptr) < 0)
#define time_before(a, b) ((long)((a) - (b)) < 0)
static unsigned long jiffies = 1000;
static uid_t caller = 10001;
static struct { uid_t val; } current_uid(void) { return (typeof(current_uid())){caller}; }
static void mutex_lock(int *lock) { assert(!*lock); *lock = 1; }
static void mutex_unlock(int *lock) { assert(*lock); *lock = 0; }
static int copy_to_user(void *dst, const void *src, size_t n) {
    if (!dst || !src) return 1; memcpy(dst, src, n); return 0;
}
#define copy_from_user copy_to_user
static void memzero_explicit(void *p, size_t n) { memset(p, 0, n); }
#define kfree_sensitive free
static int crypto_failure, allocation_failure, invalidate_during_hash;
static void *kzalloc(size_t n, int flags) { return allocation_failure ? NULL : calloc(1, n); }
struct crypto_shash { int unused; };
struct shash_desc { struct crypto_shash *tfm; };
static struct crypto_shash engine;
static struct crypto_shash *crypto_alloc_shash(const char *name, int type, int mask) {
    assert(strcmp(name, "sha256") == 0);
    return crypto_failure ? (void *)(intptr_t)-EINVAL : &engine;
}
static size_t crypto_shash_descsize(struct crypto_shash *tfm) { return 0; }
static void crypto_free_shash(struct crypto_shash *tfm) { assert(tfm == &engine); }
static int crypto_memneq(const void *a, const void *b, size_t n) { return memcmp(a, b, n); }
static int crypto_shash_digest(struct shash_desc *desc, const unsigned char *src,
                               size_t n, unsigned char *digest);
struct ksu_superkey_salt_cmd { unsigned char salt[16]; };
struct ksu_superkey_auth_cmd { unsigned char proof[32]; };
bool ksu_superkey_authorized(uid_t uid);
void ksu_superkey_revoke(void);
'''

test = r'''
uid_t ksu_manager_appid = KSU_INVALID_APPID;
static unsigned char correct_proof[32];
static int crypto_shash_digest(struct shash_desc *desc, const unsigned char *src,
                               size_t n, unsigned char *digest) {
    assert(desc->tfm == &engine && n == 32);
    memset(digest, memcmp(src, correct_proof, n) ? 0 : 0x42, 32);
    if (invalidate_during_hash) ksu_manager_appid = KSU_INVALID_APPID;
    return 0;
}
int main(void) {
    struct ksu_superkey_auth_cmd auth;
    struct ksu_superkey_salt_cmd salt;
    ksu_set_manager_appid(caller);
    assert(is_manager() && is_uid_manager(caller)); // Unconfigured = legacy behavior.
    assert(ksu_superkey_salt(&salt) == -EACCES);
    assert(ksu_superkey_auth(&auth) == -EACCES);
    ksu_superkey_config[16] = 1;
    memset(ksu_superkey_config + 20, 0x24, 16);
    memset(ksu_superkey_config + 36, 0x42, 32);
    memset(correct_proof, 0x17, sizeof(correct_proof));
    assert(ksu_is_manager_appid_valid() && is_uid_manager_candidate(caller));
    assert(!is_manager() && !is_uid_manager(caller));
    assert(ksu_superkey_salt(&salt) == 0 && salt.salt[0] == 0x24);
    assert(ksu_superkey_salt(NULL) == -EFAULT);
    assert(ksu_superkey_auth(NULL) == -EFAULT);
    memset(auth.proof, 0, sizeof(auth.proof));
    assert(ksu_superkey_auth(&auth) == -EACCES && !is_manager());
    memcpy(auth.proof, correct_proof, sizeof(auth.proof));
    assert(ksu_superkey_auth(&auth) == -EACCES); // Rate limit even with correct key.
    jiffies += 2 * HZ;
    caller++;
    assert(ksu_superkey_salt(&salt) == -EACCES);
    assert(ksu_superkey_auth(&auth) == -EACCES); // Unrecognized apps cannot authenticate/lock out.
    caller--;
    assert(ksu_superkey_auth(&auth) == 0 && is_manager());
    assert(!is_uid_manager(caller + KSU_PER_USER_RANGE)); // Full UID, not appid authentication.
    ksu_set_manager_appid(caller);
    assert(is_manager()); // Unchanged candidate must not revoke a valid session.
    ksu_invalidate_manager_uid();
    assert(!ksu_is_manager_appid_valid() && !ksu_superkey_authorized(caller));
    ksu_set_manager_appid(caller);
    jiffies += 2 * HZ;
    crypto_failure = 1;
    assert(ksu_superkey_auth(&auth) == -EACCES && !is_manager());
    crypto_failure = 0;
    jiffies += 2 * HZ;
    allocation_failure = 1;
    assert(ksu_superkey_auth(&auth) == -EACCES && !is_manager());
    allocation_failure = 0;
    jiffies += 2 * HZ;
    invalidate_during_hash = 1;
    assert(ksu_superkey_auth(&auth) == -EACCES && !is_manager());
    invalidate_during_hash = 0;
    ksu_set_manager_appid(caller);
    ksu_superkey_config[17] = 1; // Unknown record format fails closed.
    jiffies += 2 * HZ;
    assert(ksu_superkey_salt(&salt) == -EACCES && ksu_superkey_auth(&auth) == -EACCES);
    assert(!is_manager());
    puts("PASS: superkey candidate, full-UID authentication, revocation, retry limit and fail-closed paths");
}
'''

with tempfile.TemporaryDirectory(prefix="lunexsu-superkey-") as output:
    path = Path(output)
    source = stub + without_includes(root / "kernel/manager/manager_identity.h")
    source += without_includes(root / "kernel/manager/superkey.c") + test
    (path / "test.c").write_text(source)
    exe = path / "test"
    subprocess.run([os.environ.get("CC", "gcc"), "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                    "-Wno-unused-parameter", "-Wno-misleading-indentation",
                    str(path / "test.c"), "-o", str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
