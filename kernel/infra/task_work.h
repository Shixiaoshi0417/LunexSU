/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __KSU_TASK_WORK_H
#define __KSU_TASK_WORK_H

#include <linux/errno.h>
#include <linux/module.h>
#include <linux/task_work.h>

/* The callback owns one module reference after successful publication. It must
 * module_put(THIS_MODULE) as its last operation, including on early returns.
 * A sleeping target may keep unload busy until it runs the callback. Exit also
 * drains Tasks RCU to cover the instructions after the callback's final put.
 */
static inline int ksu_task_work_add(struct task_struct *task, struct callback_head *work)
{
    int ret;

    if (!try_module_get(THIS_MODULE))
        return -ENODEV;
    ret = task_work_add(task, work, TWA_RESUME);
    if (ret)
        module_put(THIS_MODULE);
    return ret;
}

#endif
