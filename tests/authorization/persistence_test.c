/* SPDX-License-Identifier: GPL-2.0 */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <stdbool.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include "kernel/policy/authorization_persistence.h"
#include "uapi/supercall.h"

enum fault {
    NONE, GUARD_OPEN, GUARD_SYNC, GUARD_DIR_SYNC, WRITE_PARTIAL,
    WRITE_LATER_RECORD, STAGING_SYNC, RENAME, REPLACEMENT_DIR_SYNC,
    CLEANUP_UNLINK, CLEANUP_DIR_SYNC,
};

struct store {
    enum fault fault;
    int directory;
    int staging;
    bool cleanup_failed;
    struct app_profile candidate;
    uint32_t mode;
};

static void checked_write(int fd, const void *data, size_t size)
{
    assert(write(fd, data, size) == (ssize_t)size);
}

static int prepare(void *ctx)
{
    struct store *s = ctx;
    if (s->fault == GUARD_OPEN)
        return -EIO;
    int fd = openat(s->directory, ".allowlist.pending", O_WRONLY | O_CREAT | O_NOFOLLOW, 0600);
    assert(fd >= 0);
    int ret = s->fault == GUARD_SYNC ? -EIO : fsync(fd);
    assert(!close(fd));
    if (ret)
        return ret;
    return s->fault == GUARD_DIR_SYNC ? -EIO : fsync(s->directory);
}

static int write_snapshot(void *ctx)
{
    struct store *s = ctx;
    const uint32_t header[] = {0x7f4b5355, 5};
    const uint64_t duration = 0;
    assert(!unlinkat(s->directory, ".allowlist.tmp", 0) || errno == ENOENT);
    s->staging = openat(s->directory, ".allowlist.tmp", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    assert(s->staging >= 0);
    checked_write(s->staging, header, sizeof(header));
    if (s->fault == WRITE_PARTIAL) {
        checked_write(s->staging, &s->candidate, 20);
        return -EIO;
    }
    checked_write(s->staging, &s->candidate, sizeof(s->candidate));
    checked_write(s->staging, &s->mode, sizeof(s->mode));
    checked_write(s->staging, &duration, sizeof(duration));
    /* Reproduce the review: a complete Permanent candidate followed by a
     * failing later record, which the old prefix loader would have accepted. */
    if (s->fault == WRITE_LATER_RECORD) {
        checked_write(s->staging, &s->candidate, 20);
        return -EIO;
    }
    return 0;
}

static int sync_snapshot(void *ctx)
{
    struct store *s = ctx;
    return s->fault == STAGING_SYNC ? -EIO : fsync(s->staging);
}

static int replace(void *ctx)
{
    struct store *s = ctx;
    return s->fault == RENAME ? -EIO : renameat(s->directory, ".allowlist.tmp", s->directory, ".allowlist");
}

static int sync_directory(void *ctx)
{
    struct store *s = ctx;
    return s->fault == REPLACEMENT_DIR_SYNC ? -EIO : fsync(s->directory);
}

static void cleanup(void *ctx)
{
    struct store *s = ctx;
    if (s->fault == CLEANUP_UNLINK) {
        s->cleanup_failed = true;
        return;
    }
    assert(!unlinkat(s->directory, ".allowlist.pending", 0));
    if (s->fault == CLEANUP_DIR_SYNC) {
        s->cleanup_failed = true;
        return;
    }
    assert(!fsync(s->directory));
}

static const struct ksu_auth_storage_ops ops = {
    .prepare = prepare, .write = write_snapshot, .sync = sync_snapshot,
    .replace = replace, .sync_directory = sync_directory, .cleanup = cleanup,
};

static bool reboot_allows(struct store *s)
{
    struct stat st;
    int lookup = fstatat(s->directory, ".allowlist.pending", &st, AT_SYMLINK_NOFOLLOW);
    if (!ksu_auth_snapshot_loadable(lookup ? -errno : 0))
        return false;
    int fd = openat(s->directory, ".allowlist", O_RDONLY);
    assert(fd >= 0);
    struct app_profile profile;
    uint32_t mode;
    assert(lseek(fd, 8, SEEK_SET) == 8);
    assert(read(fd, &profile, sizeof(profile)) == sizeof(profile));
    assert(read(fd, &mode, sizeof(mode)) == sizeof(mode));
    assert(!close(fd));
    return profile.allow_su && mode == KSU_AUTH_PERMANENT;
}

static void close_staging(struct store *s)
{
    if (s->staging >= 0) {
        assert(!close(s->staging));
        s->staging = -1;
    }
}

int main(void)
{
    assert(ksu_auth_snapshot_loadable(-ENOENT));
    assert(!ksu_auth_snapshot_loadable(0));
    assert(!ksu_auth_snapshot_loadable(-EACCES));
    assert(!ksu_auth_snapshot_loadable(-EIO));
    for (enum fault fault = NONE; fault <= CLEANUP_DIR_SYNC; ++fault) {
        char dir[] = "/tmp/lunexsu-store-XXXXXX";
        assert(mkdtemp(dir));
        struct store s = {.directory = open(dir, O_RDONLY | O_DIRECTORY), .staging = -1};
        assert(s.directory >= 0);
        s.candidate.version = KSU_APP_PROFILE_VER;
        s.candidate.curr_uid = 10123;
        strcpy(s.candidate.key, "test.fixture");
        assert(!ksu_auth_store(&ops, &s)); /* Durable denied baseline. */
        close_staging(&s);
        assert(!reboot_allows(&s));

        /* A stale staging hard link must be removed, never truncated. */
        assert(!linkat(s.directory, ".allowlist", s.directory, ".allowlist.tmp", 0));
        s.candidate.allow_su = true;
        s.fault = fault;
        int ret = ksu_auth_store(&ops, &s);
        close_staging(&s);
        bool committed = fault == NONE || fault >= CLEANUP_UNLINK;
        assert((ret == 0) == committed);
        if (!committed) {
            assert(!reboot_allows(&s)); /* Rejected Permanent never loads. */
            if (fault < REPLACEMENT_DIR_SYNC) {
                /* Before atomic replacement, the live file is untouched. */
                int fd = openat(s.directory, ".allowlist", O_RDONLY);
                struct app_profile profile;
                assert(fd >= 0 && lseek(fd, 8, SEEK_SET) == 8);
                assert(read(fd, &profile, sizeof(profile)) == sizeof(profile));
                assert(!profile.allow_su);
                assert(!close(fd));
            }
        } else {
            assert(reboot_allows(&s) == (fault != CLEANUP_UNLINK));
            assert(s.cleanup_failed == (fault >= CLEANUP_UNLINK));
        }
        /* A retry based on unchanged in-memory denied policy removes all
         * failed candidate state, including a post-rename failure. */
        s.candidate.allow_su = false;
        s.fault = NONE;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        assert(!reboot_allows(&s));
        assert(!unlinkat(s.directory, ".allowlist", 0));
        assert(!close(s.directory));
        assert(!rmdir(dir));
    }
    /* Simulate reboot/interruption after every completed transaction stage. */
    for (unsigned stage = 0; stage < 5; ++stage) {
        char dir[] = "/tmp/lunexsu-interrupt-XXXXXX";
        assert(mkdtemp(dir));
        struct store s = {.directory = open(dir, O_RDONLY | O_DIRECTORY), .staging = -1};
        assert(s.directory >= 0);
        s.candidate.allow_su = true;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        assert(reboot_allows(&s)); /* Start with a durable Permanent grant. */
        s.mode = KSU_AUTH_TIMED;
        assert(!prepare(&s));
        if (stage >= 1) assert(!write_snapshot(&s));
        if (stage >= 2) assert(!sync_snapshot(&s));
        if (stage >= 3) assert(!replace(&s));
        if (stage >= 4) assert(!sync_directory(&s));
        close_staging(&s);
        assert(!reboot_allows(&s));
        s.candidate.allow_su = false;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        assert(!reboot_allows(&s));
        assert(!unlinkat(s.directory, ".allowlist", 0));
        assert(!close(s.directory));
        assert(!rmdir(dir));
    }
    puts("PASS: production persistence transaction, 10 I/O faults, interrupted stages, reboot rejection and retry");
    return 0;
}
