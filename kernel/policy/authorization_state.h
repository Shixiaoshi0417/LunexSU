/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __LUNEXSU_AUTHORIZATION_STATE_H
#define __LUNEXSU_AUTHORIZATION_STATE_H

#include "uapi/authorization.h"

#ifdef __KERNEL__
#include <linux/compiler.h>
#define KSU_AUTH_WRITE(value, updated) WRITE_ONCE(value, updated)
#else
#define KSU_AUTH_WRITE(value, updated) ((value) = (updated))
#endif

/* Locking belongs to allowlist.c. This state machine is also compiled verbatim
 * by the host tests; no alternate implementation or clock is used in tests.
 */
struct ksu_authorization_state {
    __u32 mode;
    __u32 once; /* 0: spent/inactive, 1: available, 2: reserved */
    __u64 deadline_ns;
    __u64 duration_seconds;
};

static inline bool ksu_auth_available(const struct ksu_authorization_state *s, __u64 now)
{
    switch (s->mode) {
    case KSU_AUTH_PERMANENT:
        return true;
    case KSU_AUTH_ONCE:
        return s->once == 1;
    case KSU_AUTH_TIMED:
        return s->deadline_ns && now < s->deadline_ns;
    default:
        return false;
    }
}

static inline bool ksu_auth_grant(struct ksu_authorization_state *s, __u32 mode, __u64 seconds, __u64 now)
{
    if (mode > KSU_AUTH_TIMED || (mode == KSU_AUTH_TIMED && (!seconds || seconds > KSU_AUTH_MAX_SECONDS ||
                                                             now > (~(__u64)0) - seconds * 1000000000ULL)))
        return false;
    s->mode = mode;
    s->once = mode == KSU_AUTH_ONCE ? 1 : 0;
    s->deadline_ns = mode == KSU_AUTH_TIMED ? now + seconds * 1000000000ULL : 0;
    s->duration_seconds = mode == KSU_AUTH_TIMED ? seconds : 0;
    return true;
}

static inline bool ksu_auth_reserve(struct ksu_authorization_state *s, __u64 now, bool su_exec)
{
    if (!ksu_auth_available(s, now) || (s->mode == KSU_AUTH_ONCE && !su_exec))
        return false;
    if (s->mode == KSU_AUTH_ONCE)
        KSU_AUTH_WRITE(s->once, 2);
    return true;
}

static inline void ksu_auth_finish(struct ksu_authorization_state *s, bool success)
{
    if (s->mode == KSU_AUTH_ONCE && s->once == 2)
        KSU_AUTH_WRITE(s->once, success ? 0 : 1);
}

static inline void ksu_auth_invalidate(struct ksu_authorization_state *s)
{
    KSU_AUTH_WRITE(s->once, 0);
    KSU_AUTH_WRITE(s->deadline_ns, 0);
}

#undef KSU_AUTH_WRITE
#endif
