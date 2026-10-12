/* SPDX-License-Identifier: GPL-2.0 */
#include <linux/atomic.h>
#include <linux/cred.h>
#include <linux/hashtable.h>
#include <linux/init.h>
#include <linux/sched/signal.h>
#include <linux/slab.h>
#include <linux/spinlock.h>
#include <linux/tracepoint.h>
#include <trace/events/sched.h>

#include "infra/symbol_resolver.h"
#include "policy/allowlist.h"
#include "policy/app_session.h"
#include "policy/authorization_session.h"
#include "klog.h"

struct uid_run {
    struct hlist_node node;
    uid_t uid;
    struct ksu_app_run run;
};

struct app_task {
    struct hlist_node node;
    struct task_struct *task; /* identity only; never dereferenced after registration */
    struct uid_run *owner;
};

static DEFINE_HASHTABLE(uid_runs, 8);
static DEFINE_HASHTABLE(app_tasks, 10);
static DEFINE_SPINLOCK(session_lock);
static struct tracepoint *fork_tp, *exit_tp;
/* Skip the global lock entirely while no App run is being tracked. */
static atomic_t tracked_entries = ATOMIC_INIT(0);
static bool tracking, ready, failed;

static struct uid_run *find_run(uid_t uid, bool create)
{
    struct uid_run *entry;
    hash_for_each_possible (uid_runs, entry, node, uid) {
        if (entry->uid == uid)
            return entry;
    }
    if (!create)
        return NULL;
    entry = kzalloc(sizeof(*entry), GFP_ATOMIC);
    if (!entry) {
        failed = true;
        return NULL;
    }
    entry->uid = uid;
    hash_add(uid_runs, &entry->node, uid);
    return entry;
}

static struct app_task *find_task(struct task_struct *task)
{
    struct app_task *entry;
    hash_for_each_possible (app_tasks, entry, node, (unsigned long)task) {
        if (entry->task == task)
            return entry;
    }
    return NULL;
}

static void remove_task(struct app_task *entry)
{
    ksu_app_run_leave(&entry->owner->run);
    hash_del(&entry->node);
    atomic_dec(&tracked_entries);
    kfree(entry);
}

static void track_task(struct task_struct *task)
{
    const struct cred *cred;
    struct app_task *entry;
    struct uid_run *owner;
    unsigned long flags;
    uid_t uid;

    rcu_read_lock();
    cred = __task_cred(task);
    uid = cred->uid.val;
    spin_lock_irqsave(&session_lock, flags);
    if (!tracking)
        goto out;
    entry = find_task(task);
    if (entry && entry->owner->uid == uid)
        goto out;
    if (entry)
        remove_task(entry);
    if (!is_appuid(uid) || !READ_ONCE(task->mm) || (READ_ONCE(task->flags) & PF_EXITING))
        goto out;
    owner = find_run(uid, true);
    if (!owner)
        goto out;
    entry = kzalloc(sizeof(*entry), GFP_ATOMIC);
    if (!entry || !ksu_app_run_enter(&owner->run)) {
        kfree(entry);
        failed = true; /* incomplete tracking must never preserve a session grant */
        goto out;
    }
    entry->task = task;
    entry->owner = owner;
    hash_add(app_tasks, &entry->node, (unsigned long)task);
    atomic_inc(&tracked_entries);
out:
    spin_unlock_irqrestore(&session_lock, flags);
    rcu_read_unlock();
}

void ksu_app_session_update_current(void)
{
    /* The first process of a pre-granted stopped App has no entry yet, so this
     * path must always look; the fork probe can rely on its tracked parent. */
    track_task(current);
}

static void leave_task(struct task_struct *task)
{
    struct app_task *entry;
    unsigned long flags;
    spin_lock_irqsave(&session_lock, flags);
    entry = find_task(task);
    if (entry)
        remove_task(entry);
    spin_unlock_irqrestore(&session_lock, flags);
}

void ksu_app_session_leave_current(void)
{
    leave_task(current);
}

static void ksu_sched_fork_probe(void *unused, struct task_struct *parent, struct task_struct *child)
{
    unsigned long flags;
    bool ordinary;
    if (!atomic_read(&tracked_entries))
        return;
    spin_lock_irqsave(&session_lock, flags);
    ordinary = find_task(parent) != NULL;
    spin_unlock_irqrestore(&session_lock, flags);
    /* A detached Root su task must not create members even if a custom Root
     * profile keeps an App-range UID. Zygote children enter on setresuid. */
    if (ordinary)
        track_task(child);
}

/* Use the kernel-generated callback prototype, including downstream backports
 * of group_dead, instead of assuming a version number implies a signature. */
#ifdef KSU_SCHED_EXIT_GROUP_DEAD
static void ksu_sched_exit_probe(void *unused, struct task_struct *task, bool group_dead)
#else
static void ksu_sched_exit_probe(void *unused, struct task_struct *task)
#endif
{
    /* Not tracked when the last task left a run, but keep the counter exact. */
    if (!atomic_read(&tracked_entries))
        return;
    leave_task(task);
}

int ksu_app_session_bind(uid_t uid, u64 *epoch)
{
    struct uid_run *entry;
    unsigned long flags;
    int ret = -EOPNOTSUPP;
    spin_lock_irqsave(&session_lock, flags);
    if (!ready || failed)
        goto out;
    entry = find_run(uid, true);
    if (!entry) {
        ret = -ENOMEM;
        goto out;
    }
    *epoch = ksu_app_run_bind(&entry->run);
    ret = *epoch ? 0 : -EOVERFLOW;
out:
    spin_unlock_irqrestore(&session_lock, flags);
    return ret;
}

bool ksu_app_session_available(uid_t uid, u64 epoch, bool admission)
{
    struct uid_run *entry;
    struct app_task *task;
    unsigned long flags;
    bool available = false;
    spin_lock_irqsave(&session_lock, flags);
    if (!ready || failed)
        goto out;
    entry = find_run(uid, false);
    if (!entry || !ksu_app_run_available(&entry->run, epoch, admission))
        goto out;
    task = admission ? find_task(current) : NULL;
    available = !admission || (task && task->owner == entry);
out:
    spin_unlock_irqrestore(&session_lock, flags);
    return available;
}

void __init ksu_app_session_init(void)
{
    struct task_struct *p, *t;
    int ret;
    /* A module reload must not reuse pointers from the previous registration.
     * The tracepoint objects are not exported to modules, so resolve them through
     * the existing kallsyms helper; a missing object fails closed for Once. */
    check_trace_callback_type_sched_process_fork(ksu_sched_fork_probe);
    check_trace_callback_type_sched_process_exit(ksu_sched_exit_probe);
    fork_tp = (struct tracepoint *)find_kernel_symbol_exact("__tracepoint_sched_process_fork");
    exit_tp = (struct tracepoint *)find_kernel_symbol_exact("__tracepoint_sched_process_exit");
    if (!fork_tp || !exit_tp)
        goto unavailable;
    ret = tracepoint_probe_register(fork_tp, (void *)ksu_sched_fork_probe, NULL);
    if (ret)
        goto unavailable;
    ret = tracepoint_probe_register(exit_tp, (void *)ksu_sched_exit_probe, NULL);
    if (ret) {
        tracepoint_probe_unregister(fork_tp, (void *)ksu_sched_fork_probe, NULL);
        tracepoint_synchronize_unregister();
        goto unavailable;
    }
    WRITE_ONCE(tracking, true);
    /* Registration precedes seeding. Callbacks and seeding deduplicate task
     * pointers under the same lock; PF_EXITING tasks cannot be added after exit. */
    read_lock(&tasklist_lock);
    for_each_process_thread (p, t)
        track_task(t);
    read_unlock(&tasklist_lock);
    WRITE_ONCE(ready, true);
    return;
unavailable:
    fork_tp = exit_tp = NULL;
    pr_warn("app_session: lifecycle tracking unavailable; run-scoped grants fail closed\n");
}

void __exit ksu_app_session_exit(void)
{
    struct app_task *task;
    struct uid_run *entry;
    struct hlist_node *tmp;
    unsigned long flags;
    int bucket;
    if (fork_tp) {
        tracepoint_probe_unregister(fork_tp, (void *)ksu_sched_fork_probe, NULL);
        tracepoint_probe_unregister(exit_tp, (void *)ksu_sched_exit_probe, NULL);
        tracepoint_synchronize_unregister();
    }
    fork_tp = exit_tp = NULL;
    spin_lock_irqsave(&session_lock, flags);
    tracking = ready = false;
    hash_for_each_safe (app_tasks, bucket, tmp, task, node)
        remove_task(task);
    atomic_set(&tracked_entries, 0);
    hash_for_each_safe (uid_runs, bucket, tmp, entry, node) {
        hash_del(&entry->node);
        kfree(entry);
    }
    spin_unlock_irqrestore(&session_lock, flags);
}
