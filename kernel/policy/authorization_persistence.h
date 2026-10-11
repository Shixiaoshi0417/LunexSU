/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __LUNEXSU_AUTHORIZATION_PERSISTENCE_H
#define __LUNEXSU_AUTHORIZATION_PERSISTENCE_H

#ifdef __KERNEL__
#include <linux/string.h>
#else
#include <stdbool.h>
#include <stddef.h>
#include <string.h>
#endif
#include "uapi/app_profile.h"

/* Production transaction ordering, also exercised by the host fault tests.
 * prepare appends the transaction's uid to a rejection marker and persists it
 * and its directory before any replacement. write/sync operate on a separate
 * staging file. The loader ignores staging files and, while the marker exists,
 * restores no Root for the uids it lists.
 */
struct ksu_auth_storage_ops {
    int (*prepare)(void *ctx);
    int (*write)(void *ctx);
    int (*sync)(void *ctx);
    int (*replace)(void *ctx);
    int (*sync_directory)(void *ctx);
    void (*cleanup)(void *ctx);
};

/* Every snapshot is the committed in-memory list plus one replacement, so only
 * the uids of unfinished transactions can hold a rejected candidate or a stale
 * grant. Revoking exactly those keeps every other grant and profile.
 */
#define KSU_AUTH_GUARD_MAX_UIDS 64

struct ksu_auth_guard {
    __u32 uids[KSU_AUTH_GUARD_MAX_UIDS];
    size_t count;
    bool all; /* unreadable or overflowing marker: revoke Root from every record */
};

/* A torn trailing uid comes from a prepare that never completed, which happens
 * before any staging write or rename, so it cannot have changed a record.
 */
static inline void ksu_auth_guard_parse(struct ksu_auth_guard *guard, const void *data, size_t len)
{
    size_t count = len / sizeof(__u32);

    guard->all = count > KSU_AUTH_GUARD_MAX_UIDS;
    guard->count = guard->all ? 0 : count;
    memcpy(guard->uids, data, guard->count * sizeof(__u32));
}

static inline bool ksu_auth_guard_covers(const struct ksu_auth_guard *guard, __u32 uid)
{
    size_t i;

    if (guard->all)
        return true;
    for (i = 0; i < guard->count; i++) {
        if (guard->uids[i] == uid)
            return true;
    }
    return false;
}

/* A record that must not restore Root: no grant, default non-root policy. */
static inline void ksu_auth_strip_root(struct app_profile *profile)
{
    if (!profile->allow_su)
        return;
    profile->allow_su = false;
    memset(&profile->rp_config, 0, sizeof(profile->rp_config));
    profile->nrp_config.use_default = true;
}

static inline int ksu_auth_store(const struct ksu_auth_storage_ops *ops, void *ctx)
{
    int ret = ops->prepare(ctx);
    if (!ret)
        ret = ops->write(ctx);
    if (!ret)
        ret = ops->sync(ctx);
    if (!ret)
        ret = ops->replace(ctx);
    if (!ret)
        ret = ops->sync_directory(ctx);
    if (ret)
        return ret; /* Leave the rejection marker intact, even after rename. */

    /* The replacement is now durable. Cleanup cannot turn a committed grant
     * into a rejected request; a retained marker only causes reboot to deny it.
     */
    ops->cleanup(ctx);
    return 0;
}

#endif
