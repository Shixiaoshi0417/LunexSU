#ifndef __KSU_H_LSM_HOOK
#define __KSU_H_LSM_HOOK

#include <linux/lsm_hooks.h>
#include <linux/srcu.h>
#include <linux/stddef.h>
#include <linux/version.h>

#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0)
#define KSU_LSM_HOOK_HEADS_TYPE struct lsm_static_calls_table
#else
#define KSU_LSM_HOOK_HEADS_TYPE struct security_hook_heads
#endif

struct ksu_lsm_hook {
    const char *head_name;
    const char *target_name;
    size_t head_offset;
    size_t hook_offset;
    void *replacement;
    /* Published with release ordering before any replacement becomes callable. */
    void *original;
    struct security_hook_list *entry;
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 12, 0)
    struct lsm_static_call *scall;
#else
    struct security_hook_list list;
#endif
    // the offset from target_name to the real hook target
    // for example, we'd like to hook task_free, but no target_name can be found
    // (because none of lsm hook it), then we can find task_alloc and set offset
    // to 1
    int offset;
};

/* Process-context replacements may sleep. Hold this across the complete
 * callback, including calls to the original. Call sites must not voluntarily
 * schedule between fetching the replacement and entering it. Do not acquire
 * the hook-management mutex in callbacks.
 * Use ksu_hook_read_lock(), then srcu_read_unlock(&ksu_hook_srcu, idx).
 */
extern struct srcu_struct ksu_hook_srcu;
int ksu_hook_lifecycle_ready(void);
void ksu_hook_synchronize(void);
void ksu_hook_restore_slot(void **slot, void *original, const char *name);

static inline int ksu_hook_read_lock(void)
{
    int idx = srcu_read_lock(&ksu_hook_srcu);

    /* Core dispatchers do not acquire-load our patched function pointers.
     * Order their load before callback metadata reads, pairing with the write
     * barrier before publication. Do not rely on control dependencies alone.
     */
    smp_rmb();
    return idx;
}

static inline void *ksu_lsm_hook_original(struct ksu_lsm_hook *hook)
{
    return smp_load_acquire(&hook->original);
}

// clang-format off
#define KSU_LSM_HOOK_INIT(member, target_symbol, replacement_fn, off)                                                  \
    {                                                                                                                  \
        .head_name = #member,                                                                                          \
        .target_name = target_symbol,                                                                                  \
        .head_offset = offsetof(KSU_LSM_HOOK_HEADS_TYPE, member),                                                      \
        .hook_offset = offsetof(struct security_hook_list, hook.member),                                               \
        .replacement = (void *)(replacement_fn),                                                                       \
        .offset = off,                                                                                                 \
    }
// clang-format on

// This API implements runtime patching of existing LSM hook slots. It is a
// workaround for out-of-tree modules, not the normal LSM registration path via
// security_add_hooks(). The security framework does not expose a dedicated
// runtime lock for security_hook_heads mutations; callers rely on LunexSU's
// internal serialization plus text patching / RCU synchronization in the
// implementation to keep replacement and restoration coherent.

// --- Direct LSM hook patching API (hook/unhook) ---
// Replace the hook function in security_hook_heads[@head_name] that currently
// points to @target_name with @replacement.
// Saves the original handler into hook->original and records the patched entry
// for restoration by ksu_lsm_unhook() or the global ksu_lsm_hook_exit().
// Empty slots are rejected: successful registration always has an original.
int ksu_lsm_hook(struct ksu_lsm_hook *hook);

// Restore the LSM hook entry previously patched by ksu_lsm_hook() back to the
// original handler saved in hook->original, and remove it from global tracking.
// Waits for sleeping callbacks and pre-entry/return instruction windows before
// clearing metadata. Restoration failures are retried rather than permitting
// module unload with a live replacement. Forced module unload is unsupported.
void ksu_lsm_unhook(struct ksu_lsm_hook *hook);

// --- BPF LSM chaining API (register/unregister) ---
// Register a handler by replacing the BPF LSM implementation for this hook.
// If hook->target_name is NULL, the target symbol defaults to "bpf_lsm_<hook>".
// The replacement can call hook->original to run the original BPF LSM handler
// after LunexSU-specific logic.
int ksu_register_lsm_hook(struct ksu_lsm_hook *hook);

// Undo ksu_register_lsm_hook() and restore the original BPF LSM handler.
void ksu_unregister_lsm_hook(struct ksu_lsm_hook *hook);

// --- Global lifecycle for tracked hooks ---
// Initialize the internal tracking state used by hook/unhook and
// register/unregister. Safe to call more than once.
void ksu_lsm_hook_init(void);

// Restore all currently tracked LSM hooks in reverse order and clear the
// internal registry. Call this from module exit to avoid leaving patched LSM
// hook slots behind.
void ksu_lsm_hook_exit(void);

#endif
