/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __LUNEXSU_AUTHORIZATION_SESSION_H
#define __LUNEXSU_AUTHORIZATION_SESSION_H

#include <linux/types.h>

/* Serialized by the tracker lock. Threads count too: a leader exiting while
 * another thread remains does not end an application's run. Root su tasks
 * leave this count before returning to userspace. Epochs never resurrect. */
struct ksu_app_run {
    __u64 epoch;
    __u32 live;
};

static inline bool ksu_app_run_enter(struct ksu_app_run *run)
{
    if (run->live == ~(__u32)0 || (!run->live && run->epoch == ~(__u64)0))
        return false;
    if (!run->live)
        ++run->epoch;
    ++run->live;
    return true;
}

static inline void ksu_app_run_leave(struct ksu_app_run *run)
{
    if (run->live)
        --run->live;
}

/* A pregrant waits for exactly the next run; a live grant binds the current run. */
static inline __u64 ksu_app_run_bind(const struct ksu_app_run *run)
{
    if (run->live)
        return run->epoch;
    return run->epoch == ~(__u64)0 ? 0 : run->epoch + 1;
}

static inline bool ksu_app_run_available(const struct ksu_app_run *run, __u64 token, bool admission)
{
    if (!token)
        return false;
    if (run->epoch < token)
        return !admission; /* pending pregrant is visible, but cannot admit an untracked caller */
    return run->epoch == token && run->live;
}

#endif
