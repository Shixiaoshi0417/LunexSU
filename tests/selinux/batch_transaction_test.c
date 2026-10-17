/* SPDX-License-Identifier: GPL-2.0 */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "uapi/selinux.h"

typedef uint8_t u8;
typedef uint32_t u32;
typedef uint64_t u64;
#define __user
#define GFP_KERNEL 0
#define ALL NULL
#define KERNEL_SU_DOMAIN "su"
#define KERNEL_SU_FILE "ksu_file"
#define POLICYDB_VERSION_XPERMS_IOCTL 30
#define pr_info(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)
#define pr_err(...) do { if (0) fprintf(stderr, __VA_ARGS__); } while (0)
#define IS_ERR(p) ((intptr_t)(p) < 0 && (intptr_t)(p) >= -4095)
#define PTR_ERR(p) ((intptr_t)(p))
struct policydb { unsigned changes, policyvers; };
struct sidtab { int unused; };
struct selinux_policy { struct policydb policydb; struct sidtab *sidtab; int latest_granting; };
static struct { struct selinux_policy *policy; int policy_mutex; } selinux_state;
static struct selinux_policy *backup_sepolicy;
static bool allocation_fails, copy_fails, dup_fails;
static unsigned destroyed, published, reset, locks, apply_calls, fail_command, type_calls, fail_type;
static struct selinux_policy *original;
static void *kvmalloc(size_t size, int flags) { (void)flags; return allocation_fails ? NULL : malloc(size); }
static void kvfree(void *p) { free(p); }
static void *kzalloc(size_t size, int flags) { (void)flags; return calloc(1, size); }
static void kfree(void *p) { free(p); }
static int copy_from_user(void *dst, const void *src, size_t size)
{
    if (copy_fails) return 1;
    memcpy(dst, src, size);
    return 0;
}
static int getenforce(void) { return 1; }
static void mutex_lock(int *lock) { (void)lock; assert(!locks++); }
static void mutex_unlock(int *lock) { (void)lock; assert(locks-- == 1); }
#define lockdep_is_held(lock) (locks == 1)
#define rcu_dereference_protected(p, held) (assert(held), (p))
#define rcu_assign_pointer(p, value) do { assert(locks); published++; (p) = (value); } while (0)
static void synchronize_rcu(void) { assert(locks && published); }
static void reset_avc_cache(void) { assert(locks); reset++; }
static struct selinux_policy *ksu_dup_sepolicy(struct selinux_policy *old)
{
    if (dup_fails) return (void *)(intptr_t)-ENOMEM;
    struct selinux_policy *p = malloc(sizeof(*p));
    assert(p); *p = *old; return p;
}
static void ksu_destroy_sepolicy(struct selinux_policy *p) { assert(p); destroyed++; free(p); }
static int policydb_load_isids(struct policydb *db, void *sidtab) { (void)db; assert(sidtab); return 0; }

static bool ksu_type(struct policydb *db, const char *name, const char *attr)
{
    (void)name; (void)attr;
    db->changes++; return ++type_calls != fail_type;
}
static bool ksu_permissive(struct policydb *db, const char *name) { (void)name; db->changes++; return true; }
static bool ksu_typeattribute(struct policydb *db, const char *name, const char *attr)
{ (void)name; (void)attr; db->changes++; return true; }
static bool ksu_allow(struct policydb *db, const char *s, const char *t, const char *c, const char *p)
{ (void)s; (void)t; (void)c; (void)p; db->changes++; return true; }
static bool ksu_allowxperm(struct policydb *db, const char *s, const char *t, const char *c, const char *p)
{ return ksu_allow(db, s, t, c, p); }

/* Only the operation itself is stubbed: it deliberately mutates its private
 * copy before failing, as helpers can do on an allocation fault. The parser,
 * cursor, transaction loop and ownership/publication paths are production. */
struct sepol_data;
static int apply_one_sepolicy_cmd(struct policydb *db, const struct sepol_data *header, const char **args)
{
    (void)header; (void)args;
    db->changes++;
    return ++apply_calls == fail_command ? -EINVAL : 0;
}
#include "production_batch.inc"
#include "production_builtin.inc"

static void initialize(void)
{
    assert(!locks);
    original = calloc(1, sizeof(*original));
    assert(original);
    original->policydb.policyvers = POLICYDB_VERSION_XPERMS_IOCTL;
    selinux_state.policy = original;
    allocation_fails = copy_fails = dup_fails = false;
    destroyed = published = reset = apply_calls = fail_command = type_calls = fail_type = 0;
}
static void finish(bool committed)
{
    assert(!locks && published == (unsigned)committed && reset == (unsigned)committed);
    if (!committed) {
        assert(selinux_state.policy == original && original->policydb.changes == 0);
    }
    free(selinux_state.policy);
    if (backup_sepolicy) { free(backup_sepolicy->sidtab); free(backup_sepolicy); backup_sepolicy = NULL; }
}

int main(void)
{
    assert(sepol_require_not_all(NULL, "test") == -EINVAL);
    assert(sepol_require_not_all("type", "test") == 0);
    unsigned char payload[128], *p = payload;
    for (unsigned cmd = 0; cmd < 3; ++cmd) {
        struct sepol_data header = {KSU_SEPOLICY_CMD_ATTR, 0};
        uint32_t len = 1;
        memcpy(p, &header, sizeof(header)); p += sizeof(header);
        memcpy(p, &len, sizeof(len)); p += sizeof(len);
        *p++ = 'a'; *p++ = 0;
    }
    size_t size = (size_t)(p - payload);
    for (unsigned fault = 0; fault <= 3; ++fault) {
        initialize(); fail_command = fault;
        int ret = handle_sepolicy(payload, size);
        assert(ret == (fault ? -EINVAL : 3));
        assert(apply_calls == (fault ? fault : 3));
        assert(destroyed == 1);
        finish(fault == 0);
    }
    for (unsigned fault = 0; fault < 4; ++fault) {
        initialize();
        if (fault == 0) allocation_fails = true;
        if (fault == 1) copy_fails = true;
        if (fault == 2) dup_fails = true;
        int ret = handle_sepolicy(payload, size - (fault == 3));
        assert(ret == (fault == 0 || fault == 2 ? -ENOMEM : fault == 1 ? -EFAULT : -EINVAL));
        assert(destroyed == (unsigned)(fault == 3));
        finish(false);
    }
    /* Boot rules must not publish a partially prepared required type. */
    for (unsigned fault = 0; fault <= 2; ++fault) {
        initialize(); fail_type = fault;
        apply_kernelsu_rules();
        assert(destroyed == 1);
        finish(fault == 0);
    }
    puts("PASS: production batch and builtin transactions, first/middle/last command failure, malformed tail, copy/dup/allocation failure, all-or-nothing publication");
}
