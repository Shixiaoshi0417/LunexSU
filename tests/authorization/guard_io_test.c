/* SPDX-License-Identifier: GPL-2.0 */
#define _DEFAULT_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include "kernel/policy/authorization_persistence.h"

typedef uint32_t u32;
typedef int64_t loff_t;
#define KERNEL_SU_ALLOWLIST_PENDING "marker"
#define IS_ERR(p) ((intptr_t)(p) < 0 && (intptr_t)(p) >= -4095)
#define PTR_ERR(p) ((intptr_t)(p))
struct dentry { struct dentry *d_parent; };
struct path { void *mnt; struct dentry *dentry; };
struct inode { unsigned i_mode; loff_t size; };
struct file { struct path f_path; struct inode inode; };
struct perm_data { struct app_profile profile; };
struct allowlist_store { struct perm_data *replacement; struct file *directory, *staging; };

static unsigned char marker[512];
static struct dentry parent, entry;
static struct file guard_file, directory;
static int open_error;
static bool read_error, resize_during_read;
static ssize_t read_limit, write_limit;
static unsigned reads, writes, closes, syncs;

static struct inode *file_inode(struct file *fp) { return &fp->inode; }
static loff_t i_size_read(struct inode *inode) { return inode->size; }
static struct file *filp_open(const char *name, int flags, unsigned mode)
{
    (void)name; (void)flags; (void)mode;
    return open_error ? (void *)(intptr_t)-open_error : &guard_file;
}
static struct file *ksu_auth_open_root(const struct path *root, const char *name, int flags, unsigned mode)
{
    assert(root == &directory.f_path);
    return filp_open(name, flags, mode);
}
static int filp_close(struct file *fp, int unused)
{
    (void)unused; assert(fp == &guard_file); closes++; return 0;
}
static ssize_t kernel_read(struct file *fp, void *data, size_t size, loff_t *off)
{
    assert(fp == &guard_file && *off == 0 && size <= sizeof(marker));
    reads++;
    if (read_error)
        return -EIO;
    if (read_limit >= 0 && (size_t)read_limit < size)
        size = (size_t)read_limit;
    memcpy(data, marker, size);
    *off += (loff_t)size;
    if (resize_during_read)
        fp->inode.size++;
    return (ssize_t)size;
}
static ssize_t kernel_write(struct file *fp, const void *data, size_t size, loff_t *off)
{
    assert(fp == &guard_file && *off >= 0 && (size_t)*off + size <= sizeof(marker));
    writes++;
    if (write_limit >= 0 && (size_t)write_limit < size)
        size = (size_t)write_limit;
    memcpy(marker + *off, data, size);
    *off += (loff_t)size;
    fp->inode.size = *off;
    return (ssize_t)size;
}
static int vfs_fsync(struct file *fp, int unused)
{
    (void)unused;
    assert(fp == &guard_file || fp == &directory);
    syncs++;
    return 0;
}

#include "production_guard_io.inc"

static void initialize(const void *data, size_t len)
{
    assert(len <= sizeof(marker));
    memset(marker, 0, sizeof(marker));
    if (len)
        memcpy(marker, data, len);
    parent.d_parent = NULL;
    entry.d_parent = &parent;
    directory = (struct file){.f_path = {&parent, &parent}};
    guard_file = (struct file){.f_path = {&parent, &entry}, .inode = {S_IFREG | 0600, (loff_t)len}};
    open_error = 0;
    read_error = resize_during_read = false;
    read_limit = write_limit = -1;
    reads = writes = closes = syncs = 0;
}

int main(void)
{
    const uint32_t uids[] = {10123, 10456, 10789};
    struct ksu_auth_guard guard;
    for (size_t len = 1; len <= 11; ++len) {
        initialize(uids, len);
        assert(read_allowlist_guard(&guard));
        bool torn = len % sizeof(uint32_t) != 0;
        assert(guard.all == torn && closes == 1 && reads == 1);
        assert(ksu_auth_guard_covers(&guard, 99999) == torn);
    }
    for (ssize_t limit = 0; limit <= 7; ++limit) {
        initialize(uids, sizeof(uids));
        read_limit = limit;
        assert(read_allowlist_guard(&guard) && guard.all && closes == 1);
    }
    initialize(uids, sizeof(uids));
    read_error = true;
    assert(read_allowlist_guard(&guard) && guard.all);
    initialize(uids, sizeof(uids));
    resize_during_read = true;
    assert(read_allowlist_guard(&guard) && guard.all);
    initialize(NULL, 0);
    guard_file.inode.size = sizeof(marker);
    assert(read_allowlist_guard(&guard) && guard.all && reads == 0);
    initialize(NULL, 0);
    guard_file.inode.i_mode = S_IFDIR;
    assert(read_allowlist_guard(&guard) && guard.all && reads == 0);
    initialize(NULL, 0);
    open_error = EIO;
    assert(read_allowlist_guard(&guard) && guard.all);
    open_error = ENOENT;
    assert(!read_allowlist_guard(&guard) && !guard.all);

    struct perm_data replacement = {.profile = {.curr_uid = 10456}};
    struct allowlist_store store = {.replacement = &replacement, .directory = &directory};
    for (ssize_t partial = 1; partial <= 3; ++partial) {
        initialize(uids, sizeof(uint32_t)); /* Preserve an earlier failed uid. */
        write_limit = partial;
        assert(prepare_allowlist_store(&store) == -EIO);
        assert(writes == 1 && syncs == 0 && closes == 1);
        unsigned char before[sizeof(marker)];
        memcpy(before, marker, sizeof(marker));
        loff_t old_size = guard_file.inode.size;
        write_limit = -1;
        for (unsigned retry = 0; retry < 3; ++retry) {
            assert(prepare_allowlist_store(&store) == -EINVAL);
            assert(writes == 1 && guard_file.inode.size == old_size && !memcmp(before, marker, sizeof(marker)));
            assert(read_allowlist_guard(&guard) && guard.all);
        }
        store.replacement = NULL; /* Safe full-snapshot recovery may proceed. */
        assert(!prepare_allowlist_store(&store) && syncs == 2 && writes == 1);
        assert(!memcmp(before, marker, sizeof(marker))); /* prepare never cleans. */
        store.replacement = &replacement;
    }
    initialize(uids, sizeof(uint32_t));
    assert(!prepare_allowlist_store(&store) && writes == 1 && syncs == 2);
    assert(guard_file.inode.size == 2 * sizeof(uint32_t));
    assert(!memcmp(marker, uids, 2 * sizeof(uint32_t)));
    assert(read_allowlist_guard(&guard) && !guard.all && guard.count == 2);
    assert(ksu_auth_guard_covers(&guard, 10123) && ksu_auth_guard_covers(&guard, 10456));
    assert(!ksu_auth_guard_covers(&guard, 10789));
    puts("PASS: production guard reader/writer, aligned and torn short reads, read errors, size race, partial writes, repeated append rejection, earlier uid preservation, recovery prepare");
}
