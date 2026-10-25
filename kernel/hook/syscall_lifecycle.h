/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __KSU_SYSCALL_LIFECYCLE_H
#define __KSU_SYSCALL_LIFECYCLE_H

#include <linux/list.h>
#include <linux/sched.h>

/* Stack-owned while a syscall replacement is running. The exit tracepoint
 * removes abandoned guards if a core syscall calls do_exit() instead of
 * returning (notably exec after its point of no return).
 */
struct ksu_syscall_guard {
    struct hlist_node node;
    struct task_struct *task;
    int idx;
};

int ksu_syscall_lifecycle_init(void);
void ksu_syscall_lifecycle_exit(void);
void ksu_syscall_enter(struct ksu_syscall_guard *guard);
void ksu_syscall_leave(struct ksu_syscall_guard *guard);
void ksu_syscall_synchronize(void);

#endif
