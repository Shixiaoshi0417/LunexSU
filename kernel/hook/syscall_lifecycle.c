/* SPDX-License-Identifier: GPL-2.0 */
#include <linux/hashtable.h>
#include <linux/spinlock.h>
#include <linux/tracepoint.h>
#include <trace/events/sched.h>

#include "hook/lsm_hook.h"
#include "hook/syscall_lifecycle.h"
#include "infra/symbol_resolver.h"

static DEFINE_HASHTABLE(syscall_guards, 8);
static DEFINE_SPINLOCK(syscall_guard_lock);
static struct tracepoint *syscall_exit_tp;
/* Syscall handlers can install/undo LSM hooks during boot. A separate domain
 * avoids waiting on their own read section when that operation drains LSMs.
 */
DEFINE_STATIC_SRCU(syscall_srcu);

void ksu_syscall_enter(struct ksu_syscall_guard *guard)
{
    unsigned long flags;

    guard->idx = srcu_read_lock(&syscall_srcu);
    smp_rmb();
    guard->task = current;
    spin_lock_irqsave(&syscall_guard_lock, flags);
    hash_add(syscall_guards, &guard->node, (unsigned long)current);
    spin_unlock_irqrestore(&syscall_guard_lock, flags);
}

void ksu_syscall_leave(struct ksu_syscall_guard *guard)
{
    unsigned long flags;

    spin_lock_irqsave(&syscall_guard_lock, flags);
    hash_del(&guard->node);
    spin_unlock_irqrestore(&syscall_guard_lock, flags);
    srcu_read_unlock(&syscall_srcu, guard->idx);
}

#ifdef KSU_SCHED_EXIT_GROUP_DEAD
static void ksu_syscall_task_exit(void *unused, struct task_struct *task, bool group_dead)
#else
static void ksu_syscall_task_exit(void *unused, struct task_struct *task)
#endif
{
    struct ksu_syscall_guard *guard;
    struct hlist_node *tmp;
    unsigned long flags;

    /* do_exit() cannot return through any of this task's guarded frames. The
     * records live on its still-valid stack; no other task removes them.
     */
    spin_lock_irqsave(&syscall_guard_lock, flags);
    hash_for_each_possible_safe (syscall_guards, guard, tmp, node, (unsigned long)task) {
        if (guard->task != task)
            continue;
        hash_del(&guard->node);
        srcu_read_unlock(&syscall_srcu, guard->idx);
    }
    spin_unlock_irqrestore(&syscall_guard_lock, flags);
}

void ksu_syscall_synchronize(void)
{
    ksu_hook_synchronize_srcu(&syscall_srcu);
}

int __init ksu_syscall_lifecycle_init(void)
{
    int ret = ksu_hook_lifecycle_ready();

    if (ret)
        return ret;
    check_trace_callback_type_sched_process_exit(ksu_syscall_task_exit);
    syscall_exit_tp = (struct tracepoint *)find_kernel_symbol_exact("__tracepoint_sched_process_exit");
    if (!syscall_exit_tp)
        return -EOPNOTSUPP;
    return tracepoint_probe_register(syscall_exit_tp, (void *)ksu_syscall_task_exit, NULL);
}

void __exit ksu_syscall_lifecycle_exit(void)
{
    /* All syscall entry points have already been restored and drained. */
    tracepoint_probe_unregister(syscall_exit_tp, (void *)ksu_syscall_task_exit, NULL);
    tracepoint_synchronize_unregister();
    /* Also covers final module_put() return windows in task_work/dentries. */
    ksu_hook_synchronize();
}
