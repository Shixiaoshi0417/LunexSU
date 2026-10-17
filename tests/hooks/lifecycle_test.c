/* Compile production hook lifecycle functions against deterministic host mocks.
 * This is not a substitute for real SRCU/Tasks RCU or kernel patching tests.
 */
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define KERNEL_VERSION(a, b, c) ((a) * 10000 + (b) * 100 + (c))
#if TEST_VERSION == 510
#define LINUX_VERSION_CODE KERNEL_VERSION(5, 10, 0)
#else
#define LINUX_VERSION_CODE KERNEL_VERSION(6, 12, 0)
#endif
#define __init
#define __exit
#define __nocfi
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define READ_ONCE(v) __atomic_load_n(&(v), __ATOMIC_ACQUIRE)
#define WRITE_ONCE(v, x) __atomic_store_n(&(v), (x), __ATOMIC_RELEASE)
#define smp_load_acquire(p) __atomic_load_n((p), __ATOMIC_ACQUIRE)
#define smp_store_release(p, x) __atomic_store_n((p), (x), __ATOMIC_RELEASE)
#define smp_wmb() __atomic_thread_fence(__ATOMIC_RELEASE)
#define smp_rmb() __atomic_thread_fence(__ATOMIC_ACQUIRE)
static void quiet_log(const char *format, ...) { (void)format; }
#define pr_err(...) quiet_log(__VA_ARGS__)
#define pr_info(...) quiet_log(__VA_ARGS__)
#define pr_warn(...) quiet_log(__VA_ARGS__)
#define pr_err_ratelimited(...) quiet_log(__VA_ARGS__)
#define DEFINE_MUTEX(name) pthread_mutex_t name = PTHREAD_MUTEX_INITIALIZER
#define mutex_lock(p) assert(pthread_mutex_lock(p) == 0)
#define mutex_unlock(p) assert(pthread_mutex_unlock(p) == 0)
#define msleep(ms) sched_yield()
typedef uint32_t u32;
typedef uint64_t u64;
struct hlist_node { struct hlist_node *next; };
struct hlist_head { struct hlist_node *first; };
struct security_hook_list {
    struct hlist_node list;
    union { int (*setprocattr)(const char *, void *, size_t); } hook;
};
struct security_hook_heads { struct hlist_head setprocattr; struct hlist_head empty; };
#define hlist_entry(p, type, member) ((type *)((char *)(p) - offsetof(type, member)))
#define hlist_for_each_entry(e, h, member) \
    for (struct hlist_node *n = (h)->first; n && ((e) = hlist_entry(n, struct security_hook_list, member)); n = n->next)
struct static_call_key { void *func; };
struct lsm_static_call { struct security_hook_list *hl; struct static_call_key *key; void *trampoline; };
struct lsm_static_calls_table { struct lsm_static_call setprocattr; struct lsm_static_call empty; };
static struct security_hook_heads heads;
static struct security_hook_list core_entry;
static struct lsm_static_calls_table scalls;
static struct static_call_key key;
static u32 lsm_active_cnt = 1;
static int static_updates;
static int replacement(const char *name, void *value, size_t size);
static void __static_call_update(struct static_call_key *k, void *tramp, void *func)
{
    (void)tramp;
    k->func = func;
    static_updates++;
    if (func == (void *)replacement)
        replacement("current", NULL, 0);
}

struct srcu_struct { int readers; };
#define DEFINE_SRCU(name) struct srcu_struct name
static DEFINE_MUTEX(wait_lock);
static pthread_cond_t wait_cond = PTHREAD_COND_INITIALIZER;
static int grace_calls, drain_waiting, callback_started, callback_release;
static int preentry_pending, return_pending, hold_return, reader_acquired;
static int srcu_read_lock(struct srcu_struct *s)
{
    mutex_lock(&wait_lock);
    s->readers++;
    reader_acquired = 1;
    preentry_pending = 0;
    pthread_cond_broadcast(&wait_cond);
    mutex_unlock(&wait_lock);
    return 0;
}
static void srcu_read_unlock(struct srcu_struct *s, int idx)
{
    (void)idx;
    mutex_lock(&wait_lock);
    s->readers--;
    if (hold_return)
        return_pending = 1;
    pthread_cond_broadcast(&wait_cond);
    mutex_unlock(&wait_lock);
}
static void synchronize_srcu(struct srcu_struct *s)
{
    mutex_lock(&wait_lock);
    drain_waiting = 1;
    pthread_cond_broadcast(&wait_cond);
    while (s->readers)
        pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
}
static void tasks_grace(void)
{
    mutex_lock(&wait_lock);
    grace_calls++;
    pthread_cond_broadcast(&wait_cond);
    while (preentry_pending || return_pending)
        pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
}

/* PRODUCTION_LSM_HEADER */

static struct ksu_lsm_hook hook;
static int original_calls;
static int core_original(const char *name, void *value, size_t size)
{
    (void)name; (void)value; (void)size;
    return ++original_calls;
}
static int replacement(const char *name, void *value, size_t size)
{
    int idx = ksu_hook_read_lock();
    int (*orig)(const char *, void *, size_t) = ksu_lsm_hook_original(&hook);
    assert(orig == core_original);
    assert(hook.entry);
#if TEST_VERSION != 510
    assert(hook.scall);
#endif
    int ret = orig(name, value, size);
    srcu_read_unlock(&ksu_hook_srcu, idx);
    return ret;
}
static int tasks_available = 1, fail_patch, fail_restore, probe_publish, fail_before_write, patch_calls;
static unsigned long status_ops_address(void);
static unsigned long find_kernel_symbol_exact(const char *name)
{
    if (!strcmp(name, "synchronize_rcu_tasks"))
        return tasks_available ? (unsigned long)tasks_grace : 0;
    if (!strcmp(name, "security_hook_heads"))
        return (unsigned long)&heads;
    if (!strcmp(name, "static_calls_table"))
        return (unsigned long)&scalls;
    if (!strcmp(name, "lsm_active_cnt"))
        return (unsigned long)&lsm_active_cnt;
    if (!strcmp(name, "sel_handle_status_ops"))
        return status_ops_address();
    return 0;
}
static void *ksu_resolve_symbol_for_functable_hook(const char *name)
{
    (void)name;
    return core_original;
}
static bool kallsyms_lookup_size_offset(unsigned long addr, unsigned long *size, void *offset)
{
    (void)offset;
    *size = addr == (unsigned long)&heads ? sizeof(heads) : sizeof(scalls);
    return true;
}
#define KSU_PATCH_TEXT_FLUSH_DCACHE 1
static int ksu_patch_text(void *dst, void *src, size_t len, int flags)
{
    void *value = *(void **)src;
    (void)flags;
    patch_calls++;
    if (value == (void *)replacement && fail_before_write) {
        fail_before_write--;
        return -EFAULT;
    }
    if (value == (void *)core_original && fail_restore) {
        fail_restore--;
        return -EFAULT;
    }
    memcpy(dst, src, len);
    if (value == (void *)replacement && probe_publish)
        replacement("current", NULL, 0); /* Earliest possible callback. */
    if (value == (void *)replacement && fail_patch) {
        fail_patch--;
        return -EFAULT; /* Simulate a failed patch that did become visible. */
    }
    return 0;
}

/* PRODUCTION_LSM_SOURCE */

typedef int (*write_op_fn)(void);
struct inode { int unused; };
struct file;
typedef int (*sel_open_handle_status_fn)(struct inode *, struct file *);
struct file_operations {
    void *owner;
    sel_open_handle_status_fn open;
    void *read, *mmap;
};
struct file { void *private_data; const struct file_operations *f_op; };
static int module_refs, module_going, module_owner, status_core_page;
#define THIS_MODULE (&module_owner)
#define likely(x) (x)
static struct { unsigned int val; } current_uid(void)
{
    return (typeof(current_uid())){10000};
}
static const struct file_operations *fops_get(const struct file_operations *ops)
{
    if (ops->owner && module_going)
        return NULL;
    if (ops->owner)
        module_refs++;
    return ops;
}
static void replace_fops(struct file *file, const struct file_operations *ops)
{
    if (file->f_op->owner)
        module_refs--;
    file->f_op = ops;
}
static int context_orig(void) { return 1; }
static int access_orig(void) { return 2; }
static int status_orig(struct inode *inode, struct file *file)
{
    (void)inode;
    file->private_data = &status_core_page;
    return 0;
}
static int context_new(void) { return 4; }
static int access_new(void) { return 5; }
static int status_new(struct inode *inode, struct file *file) { (void)inode; (void)file; return 6; }
static write_op_fn orig_context_write, orig_access_write;
static sel_open_handle_status_fn orig_sel_open_handle_status, status_slot, *sel_open_handle_status_slot;
static write_op_fn context_slot, access_slot;
static write_op_fn *context_write, *access_write;
static struct file_operations fake_status_ops;
static bool fake_status_ops_initialized;
static struct file_operations status_core_ops = {.open = status_orig};
static unsigned long status_ops_address(void) { return (unsigned long)&status_core_ops; }
static bool context_write_installed, access_write_installed, status_open_installed;
static bool ksu_selinux_hide_enabled, ksu_selinux_hide_running;
static DEFINE_MUTEX(selinux_hide_mutex);
static struct { pthread_mutex_t status_lock; } selinux_state = { PTHREAD_MUTEX_INITIALIZER };
static struct { int key; } fake_status_initialize_key;
static int freed_pages, handler_registered = 1;
static void *fake_status;
static struct ksu_lsm_hook selinux_setprocattr_hook;
#define KSU_FEATURE_SELINUX_HIDE 4
#define static_key_disable(p) ((void)(p))
#define static_branch_unlikely(p) ((void)(p), false)
static void initialize_fake_status(void) {}
static void __free_page(void *page) { assert(page); freed_pages++; }
static void ksu_unregister_feature_handler(int id) { (void)id; handler_registered = 0; }
static int ksu_selinux_hide_enable(void) { return -EAGAIN; }
/* PRODUCTION_HIDE_LIFECYCLE */

static void reset_hook(void)
{
    hook = (struct ksu_lsm_hook)KSU_LSM_HOOK_INIT(setprocattr, "core_original", replacement, 0);
    core_entry.hook.setprocattr = core_original;
    heads.setprocattr.first = &core_entry.list;
    scalls.setprocattr.hl = &core_entry;
    scalls.setprocattr.key = &key;
    key.func = core_original;
    grace_calls = drain_waiting = callback_started = callback_release = 0;
    preentry_pending = return_pending = hold_return = reader_acquired = 0;
    probe_publish = 1;
}
static void *sleeping_callback(void *arg)
{
    (void)arg;
    int idx = ksu_hook_read_lock();
    mutex_lock(&wait_lock);
    callback_started = 1;
    pthread_cond_broadcast(&wait_cond);
    while (!callback_release)
        pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
    assert(ksu_lsm_hook_original(&hook) == (void *)core_original);
    srcu_read_unlock(&ksu_hook_srcu, idx);
    mutex_lock(&wait_lock);
    while (return_pending)
        pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
    return NULL;
}
static void *unhook_thread(void *arg) { ksu_lsm_unhook(arg); return NULL; }
static void wait_for(int *value)
{
    mutex_lock(&wait_lock);
    while (!*value)
        pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
}
static void lifecycle_tests(void)
{
    reset_hook();
    tasks_available = 0;
    assert(ksu_lsm_hook(&hook) == -EOPNOTSUPP);
    assert(!hook.entry);
    tasks_available = 1;
    assert(!ksu_lsm_hook(&hook));
    assert(original_calls == (TEST_VERSION == 510 ? 1 : 2));
    assert(ksu_lsm_hook(&hook) == -EALREADY);
    ksu_lsm_unhook(&hook);
    assert(!hook.original && !hook.entry && !ksu_lsm_hook_count);
    assert(grace_calls == 2);
    ksu_lsm_unhook(&hook); /* Idempotent. */
    assert(grace_calls == 2);

    reset_hook();
    fail_patch = fail_restore = 1;
    assert(ksu_lsm_hook(&hook) == -EFAULT);
    assert(!hook.original && !hook.entry && !ksu_lsm_hook_count);
    assert(core_entry.hook.setprocattr == core_original);
    assert(grace_calls == 2);

    reset_hook();
    int patches_before = patch_calls;
    fail_before_write = 1;
    assert(ksu_lsm_hook(&hook) == -EFAULT);
    assert(!hook.original && !hook.entry && !ksu_lsm_hook_count);
    assert(patch_calls == patches_before + 1); /* No retry of an unchanged slot. */

    reset_hook();
    hook.original = core_original;
    fail_patch = 1;
    assert(ksu_lsm_hook(&hook) == -EFAULT);
    assert(hook.original == (void *)core_original && !hook.entry);

    reset_hook();
    hook.offset = 1; /* Never publish a callback without a callable original. */
    assert(ksu_lsm_hook(&hook) == -ENOENT);
    assert(!hook.original && !hook.entry);

    reset_hook();
    assert(!ksu_lsm_hook(&hook));
    struct ksu_lsm_hook duplicate = (struct ksu_lsm_hook)KSU_LSM_HOOK_INIT(setprocattr, "core_original", context_new, 0);
    assert(ksu_lsm_hook(&duplicate) == -EBUSY);
    fail_restore = 1;
    ksu_lsm_unhook(&hook);
    assert(!hook.original && !hook.entry && !ksu_lsm_hook_count);

    reset_hook();
    assert(!ksu_lsm_hook(&hook));
    pthread_t reader, undo;
    hold_return = 1;
    assert(!pthread_create(&reader, NULL, sleeping_callback, NULL));
    wait_for(&callback_started);
    assert(!pthread_create(&undo, NULL, unhook_thread, &hook));
    wait_for(&drain_waiting);
    assert(hook.original == (void *)core_original);
    mutex_lock(&wait_lock);
    callback_release = 1;
    pthread_cond_broadcast(&wait_cond);
    mutex_unlock(&wait_lock);
    wait_for(&return_pending);
    assert(hook.original == (void *)core_original); /* Return window still live. */
    mutex_lock(&wait_lock);
    return_pending = 0;
    pthread_cond_broadcast(&wait_cond);
    mutex_unlock(&wait_lock);
    assert(!pthread_join(reader, NULL));
    assert(!pthread_join(undo, NULL));
    assert(!hook.original && !hook.entry && !ksu_lsm_hook_count);

    reset_hook();
    assert(!ksu_lsm_hook(&hook));
    preentry_pending = 1; /* A CPU fetched the old replacement but not entered. */
    reader_acquired = 0;
    assert(!pthread_create(&undo, NULL, unhook_thread, &hook));
    wait_for(&grace_calls);
    assert(hook.original == (void *)core_original);
    assert(!reader_acquired);
    assert(!pthread_create(&reader, NULL, sleeping_callback, NULL));
    wait_for(&drain_waiting);
    mutex_lock(&wait_lock);
    callback_release = 1;
    pthread_cond_broadcast(&wait_cond);
    mutex_unlock(&wait_lock);
    assert(!pthread_join(reader, NULL));
    assert(!pthread_join(undo, NULL));
    assert(!hook.original && !hook.entry);
}
static void hide_tests(void)
{
    struct inode inode = {0};
    struct file fake_file = {.f_op = &status_core_ops};
    struct file going_file = {.f_op = &status_core_ops};
    fake_status = &fake_status;
    hook_selinux_status_open();
    assert(status_open_installed && status_core_ops.open == my_sel_open_handle_status);
    ksu_selinux_hide_enabled = true;
    assert(!status_core_ops.open(&inode, &fake_file));
    assert(fake_file.private_data == fake_status && module_refs == 1);
    assert(fake_file.f_op == &fake_status_ops && fake_file.f_op->owner == THIS_MODULE);
    assert(!fake_file.f_op->open);
    module_going = 1;
    assert(!status_core_ops.open(&inode, &going_file));
    assert(going_file.private_data == &status_core_page && module_refs == 1);
    module_going = 0;
    assert(!selinux_hide_feature_set(0));
    assert(status_core_ops.open == status_orig && fake_file.private_data == fake_status);
    assert(module_refs == 1); /* File/mapping still pins module and fake page. */
    module_refs--; /* Last file/mapping release permits a normal module exit. */
    ksu_selinux_hide_exit();
    assert(!fake_status && freed_pages == 1);
    freed_pages = 0;
    handler_registered = 1;

    /* Default-off init still installs status open. Exit must undo it. */
    orig_sel_open_handle_status = status_orig;
    sel_open_handle_status_slot = &status_slot;
    status_slot = status_new;
    status_open_installed = true;
    fake_status = &fake_status;
    ksu_selinux_hide_exit();
    assert(status_slot == status_orig && !orig_sel_open_handle_status);
    assert(!status_open_installed && !fake_status && freed_pages == 1 && !handler_registered);
    ksu_selinux_hide_exit();
    assert(freed_pages == 1);

    /* Partial enable: context/status published, access not yet patched. */
    orig_context_write = context_orig;
    context_write = &context_slot;
    context_slot = context_new;
    context_write_installed = true;
    orig_access_write = access_orig;
    access_write = &access_slot;
    access_slot = access_orig;
    orig_sel_open_handle_status = status_orig;
    sel_open_handle_status_slot = &status_slot;
    status_slot = status_new;
    status_open_installed = true;
    assert(!selinux_hide_feature_set(0));
    assert(context_slot == context_orig && access_slot == access_orig && status_slot == status_orig);
    assert(!orig_context_write && !orig_access_write && !orig_sel_open_handle_status);
    assert(!context_write && !access_write && !sel_open_handle_status_slot);
    assert(!selinux_hide_feature_set(0));

    orig_context_write = context_orig;
    context_write = &context_slot;
    context_slot = context_new;
    context_write_installed = true;
    orig_access_write = access_orig;
    access_write = &access_slot;
    access_slot = access_new;
    access_write_installed = true;
    ksu_selinux_hide_running = ksu_selinux_hide_enabled = true;
    assert(!selinux_hide_feature_set(0));
    assert(context_slot == context_orig && access_slot == access_orig);
    assert(!ksu_selinux_hide_running && !ksu_selinux_hide_enabled);
}
int main(void)
{
    lifecycle_tests();
    hide_tests();
    printf("hook lifecycle: %d branch passed\n", TEST_VERSION);
    return 0;
}
