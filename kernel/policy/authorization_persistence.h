/* SPDX-License-Identifier: GPL-2.0 */
#ifndef __LUNEXSU_AUTHORIZATION_PERSISTENCE_H
#define __LUNEXSU_AUTHORIZATION_PERSISTENCE_H

/* Production transaction ordering, also exercised by the host fault tests.
 * prepare persists a rejection marker and its directory before any replacement.
 * write/sync operate on a separate staging file. The loader ignores staging
 * files and refuses the live snapshot while the rejection marker exists.
 */
struct ksu_auth_storage_ops {
    int (*prepare)(void *ctx);
    int (*write)(void *ctx);
    int (*sync)(void *ctx);
    int (*replace)(void *ctx);
    int (*sync_directory)(void *ctx);
    void (*cleanup)(void *ctx);
};

static inline bool ksu_auth_snapshot_loadable(int guard_lookup_error)
{
    return guard_lookup_error == -ENOENT;
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
