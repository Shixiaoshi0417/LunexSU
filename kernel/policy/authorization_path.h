/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __LUNEXSU_AUTHORIZATION_PATH_H
#define __LUNEXSU_AUTHORIZATION_PATH_H

#include <linux/fs.h>
#include <linux/errno.h>
#include <linux/rcupdate.h>
#include <linux/fs_struct.h>
#include <linux/pid.h>
#include <linux/sched/task.h>
#include <linux/version.h>

/* Credentials do not bypass Android's per-app tmpfs/bind-mount isolation.
 * Resolve from Android init's root, without changing the caller's namespace
 * or fs_struct. Use its PID namespace (also works in Android containers).
 */
static inline int ksu_auth_init_root(struct path *root)
{
    struct task_struct *init;
    int ret = -ESRCH;

    rcu_read_lock();
    init = get_pid_task(find_vpid(1), PIDTYPE_PID);
    rcu_read_unlock();
    if (!init)
        return ret;
    task_lock(init);
    if (init->fs) {
        get_fs_root(init->fs, root);
        ret = 0;
    }
    task_unlock(init);
    put_task_struct(init);
    return ret;
}

static inline struct file *ksu_auth_open_root(const struct path *root, const char *name, int flags, umode_t mode)
{
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 14, 0)
    return file_open_root(root, name, flags, mode);
#else
    return file_open_root(root->dentry, root->mnt, name, flags, mode);
#endif
}

#endif
