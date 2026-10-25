/* Production dispatcher/teardown + stack guards, mocked SRCU/Tasks RCU. */
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define __init
#define __exit
#define __nocfi
#define likely(x) (x)
#define READ_ONCE(x) __atomic_load_n(&(x), __ATOMIC_ACQUIRE)
#define WRITE_ONCE(x, v) __atomic_store_n(&(x), (v), __ATOMIC_RELEASE)
#define pr_info(...) ((void)0)
#define pr_err(...) ((void)0)
#define DEFINE_MUTEX(n) pthread_mutex_t n = PTHREAD_MUTEX_INITIALIZER
#define DEFINE_SPINLOCK(n) DEFINE_MUTEX(n)
#define mutex_lock(p) assert(!pthread_mutex_lock(p))
#define mutex_unlock(p) assert(!pthread_mutex_unlock(p))
#define spin_lock_irqsave(p, f) do { (f) = 0; mutex_lock(p); } while (0)
#define spin_unlock_irqrestore(p, f) do { (void)(f); mutex_unlock(p); } while (0)
struct task_struct { int id; };
static _Thread_local struct task_struct this_task;
#define current (&this_task)
struct hlist_node { struct hlist_node *next, **pprev; };
struct hlist_head { struct hlist_node *first; };
#define DEFINE_HASHTABLE(n, bits) struct hlist_head n
static void add_node(struct hlist_node *n, struct hlist_head *h)
{
    n->next = h->first;
    if (n->next) n->next->pprev = &n->next;
    h->first = n; n->pprev = &h->first;
}
static void hash_del(struct hlist_node *n)
{
    *n->pprev = n->next;
    if (n->next) n->next->pprev = n->pprev;
    n->next = NULL; n->pprev = NULL;
}
#define hash_add(h, n, key) add_node(n, &(h))
#define container_of(p, type, m) ((type *)((char *)(p) - offsetof(type, m)))
#define hash_for_each_possible_safe(h, pos, tmp, member, key) \
    for (struct hlist_node *n = (h).first; n && \
         ((tmp) = n->next, (pos) = container_of(n, __typeof__(*(pos)), member), 1); n = (tmp))

static DEFINE_MUTEX(wait_lock);
static pthread_cond_t wait_cond = PTHREAD_COND_INITIALIZER;
static int active, grace_calls, drain_started, hook_started, release_hook, preentry, return_pending, hold_return;
struct srcu_struct { int unused; };
#define DEFINE_SRCU(n) struct srcu_struct n
#define DEFINE_STATIC_SRCU(n) static struct srcu_struct n
static DEFINE_SRCU(ksu_hook_srcu);
#define smp_rmb() __atomic_thread_fence(__ATOMIC_ACQUIRE)
static void signal_waiters(void) { pthread_cond_broadcast(&wait_cond); }
static int srcu_read_lock(struct srcu_struct *srcu)
{
    mutex_lock(&wait_lock);
    active++; preentry = 0;
    signal_waiters();
    mutex_unlock(&wait_lock);
    return 0;
}
static void srcu_read_unlock(struct srcu_struct *srcu, int idx)
{
    mutex_lock(&wait_lock);
    assert(active > 0); active--;
    if (hold_return) return_pending = 1;
    signal_waiters();
    while (return_pending) pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
}
static void synchronize_srcu(struct srcu_struct *srcu)
{
    mutex_lock(&wait_lock); drain_started = 1; signal_waiters();
    while (active) pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
}
static void tasks_grace(void)
{
    mutex_lock(&wait_lock); grace_calls++; signal_waiters();
    while (preentry || return_pending) pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
}
static void (*ksu_synchronize_rcu_tasks)(void) = tasks_grace;
#include "synchronize.inc"
struct tracepoint { int unused; };
static struct tracepoint exit_tp;
static int lifecycle_ready, registered;
static int ksu_hook_lifecycle_ready(void) { return lifecycle_ready ? 0 : -EOPNOTSUPP; }
static unsigned long find_kernel_symbol_exact(const char *name) { return (unsigned long)&exit_tp; }
static int tracepoint_probe_register(struct tracepoint *tp, void *fn, void *data) { registered++; return 0; }
static void tracepoint_probe_unregister(struct tracepoint *tp, void *fn, void *data) { registered--; }
static void tracepoint_synchronize_unregister(void) {}
#define check_trace_callback_type_sched_process_exit(fn) ((void)fn)
#if TEST_GROUP_DEAD
#define KSU_SCHED_EXIT_GROUP_DEAD
#endif
#include "lifecycle.inc"

#define __NR_syscalls 16
struct pt_regs { long syscallno, orig_nr, orig_ax, ax; unsigned long a0, a7, orig_a0; };
#define PT_REGS_ORIG_SYSCALL(r) ((r)->orig_nr)
#include "riscv_regs.inc"
typedef long (*syscall_fn_t)(const struct pt_regs *);
typedef syscall_fn_t sys_call_ptr_t;
typedef long (*ksu_syscall_hook_fn)(int, const struct pt_regs *);
static syscall_fn_t table[__NR_syscalls], *ksu_syscall_table = table;
static ksu_syscall_hook_fn syscall_hooks[__NR_syscalls];
static int ksu_dispatcher_nr = 9;
static struct { int nr; syscall_fn_t orig; } hooked_entries[16];
static int hooked_count;
static DEFINE_MUTEX(hooked_entries_lock);
static int restore_count;
static void ksu_hook_restore_slot(void **slot, void *orig, const char *name)
{
    *slot = orig; restore_count++;
}
#include TEST_ARCH

static struct pt_regs regs_for(int nr)
{
    return (struct pt_regs){.syscallno = 9, .orig_nr = nr, .orig_ax = 9, .ax = nr,
                            .a7 = 9, .a0 = nr, .orig_a0 = 1234};
}
static long original(const struct pt_regs *regs) { return 73; }
static long blocked_handler(int nr, const struct pt_regs *regs)
{
    assert(nr == 2);
#if TEST_arm64
    assert(regs->syscallno == 2 && regs->orig_nr == 2);
#elif TEST_x86_64
    assert(regs->orig_ax == 2);
#else
    assert(regs->a7 == 2 && regs->orig_a0 == 1234);
#endif
    mutex_lock(&wait_lock); hook_started = 1; signal_waiters();
    while (!release_hook) pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
    assert(ksu_dispatcher_nr == 9 && syscall_hooks[2] == blocked_handler);
    return 42;
}
static void *dispatch_thread(void *unused)
{
    struct pt_regs regs = regs_for(2);
    assert(ksu_syscall_dispatcher(&regs) == 42);
    return NULL;
}
static void *exit_thread(void *unused) { ksu_syscall_hook_exit(); return NULL; }
static void wait_for(int *p)
{
    mutex_lock(&wait_lock);
    while (!*p) pthread_cond_wait(&wait_cond, &wait_lock);
    mutex_unlock(&wait_lock);
}
static void set(int *p) { mutex_lock(&wait_lock); *p = 1; signal_waiters(); mutex_unlock(&wait_lock); }
static void reset(void)
{
    assert(!active && !syscall_guards.first);
    grace_calls = drain_started = hook_started = release_hook = preentry = return_pending = hold_return = 0;
    ksu_dispatcher_nr = 9;
    for (int i = 0; i < __NR_syscalls; i++) table[i] = original;
    table[9] = ksu_syscall_dispatcher;
    syscall_hooks[2] = blocked_handler;
    hooked_entries[0].nr = 9; hooked_entries[0].orig = original; hooked_count = 1;
}
int main(void)
{
    assert(ksu_syscall_lifecycle_init() == -EOPNOTSUPP && !registered);
    lifecycle_ready = 1;
    assert(!ksu_syscall_lifecycle_init() && registered == 1);
    reset();
    /* Every malformed/spurious path must release its read-side lifetime. */
    struct pt_regs regs = regs_for(9);
    assert(ksu_syscall_dispatcher(&regs) == -ENOSYS && !active);
    regs = regs_for(__NR_syscalls + 1);
    assert(ksu_syscall_dispatcher(&regs) == -ENOSYS && !active);
    regs = regs_for(3);
    assert(ksu_syscall_dispatcher(&regs) == 73 && !active);

    for (int delayed = 0; delayed < 2; delayed++) {
        reset();
        pthread_t worker, unload;
        if (delayed) {
            preentry = 1;
            assert(!pthread_create(&unload, NULL, exit_thread, NULL));
            wait_for(&grace_calls);
            assert(ksu_dispatcher_nr == 9 && syscall_hooks[2]);
        }
        hold_return = 1;
        assert(!pthread_create(&worker, NULL, dispatch_thread, NULL));
        wait_for(&hook_started);
        if (!delayed) assert(!pthread_create(&unload, NULL, exit_thread, NULL));
        wait_for(&drain_started);
        assert(ksu_dispatcher_nr == 9 && syscall_hooks[2] && active == 1);
        assert(table[9] == original); /* Admission restored before draining. */
        set(&release_hook);
        wait_for(&return_pending);
        assert(ksu_dispatcher_nr == 9); /* SRCU is done; epilogue still live. */
        mutex_lock(&wait_lock); return_pending = 0; signal_waiters(); mutex_unlock(&wait_lock);
        assert(!pthread_join(worker, NULL));
        assert(!pthread_join(unload, NULL));
        assert(!active && !syscall_guards.first && !syscall_hooks[2] && ksu_dispatcher_nr == -1);
    }
    reset();
    /* Fatal exec never unwinds its dispatcher. Exit removes all nested guards
     * for this task, but must leave another task's guards alone. */
    struct ksu_syscall_guard a, b, other;
    struct task_struct remote = {0};
    ksu_syscall_enter(&other); other.task = &remote;
    ksu_syscall_enter(&a); ksu_syscall_enter(&b);
#if TEST_GROUP_DEAD
    ksu_syscall_task_exit(NULL, current, true);
#else
    ksu_syscall_task_exit(NULL, current);
#endif
    assert(active == 1);
    ksu_syscall_leave(&other);
    assert(!active && !syscall_guards.first);
    ksu_syscall_hook_exit();
    ksu_syscall_lifecycle_exit();
    assert(!registered);
    printf("syscall lifecycle: %s, exit signature %d passed\n", TEST_ARCH, TEST_GROUP_DEAD);
}
