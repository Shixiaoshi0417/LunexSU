/* SPDX-License-Identifier: GPL-2.0 WITH Linux-syscall-note */
#ifndef __LUNEXSU_AUTHORIZATION_H
#define __LUNEXSU_AUTHORIZATION_H

#include <linux/types.h>
#include "uapi/app_profile.h"

#define KSU_AUTH_PERMANENT 0
#define KSU_AUTH_ONCE 1
#define KSU_AUTH_TIMED 2
#define KSU_AUTH_KEEP 0
#define KSU_AUTH_GRANT 1
#define KSU_AUTH_INACTIVE 0
#define KSU_AUTH_ACTIVE 1
#define KSU_AUTH_BUSY 2
#define KSU_AUTH_MAX_SECONDS (365ULL * 24 * 60 * 60)

/* v1: full profile and policy are one transaction. All times are durations,
 * never wall-clock timestamps. generation is an optimistic concurrency token.
 * KEEP cannot rearm, renew, or convert a temporary grant to permanent.
 */
struct ksu_auth_profile_cmd {
    struct app_profile profile;
    __u32 mode;
    __u32 operation;
    __aligned_u64 duration_seconds;
    __aligned_u64 generation;
    __aligned_u64 remaining_seconds;
    __u32 state;
    __u32 reserved;
};

#endif
