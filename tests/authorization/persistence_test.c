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
    /* An unrelated Permanent grant that every snapshot carries unchanged. */
    struct app_profile bystander;
    struct app_profile candidate;
    uint32_t mode;
};

struct loaded {
    bool candidate_root;
    bool bystander_root;
};

static void checked_write(int fd, const void *data, size_t size)
{
    assert(write(fd, data, size) == (ssize_t)size);
}

/* Mirrors prepare_allowlist_store(): append this transaction's uid. */
static int prepare(void *ctx)
{
    struct store *s = ctx;
    if (s->fault == GUARD_OPEN)
        return -EIO;
    int fd = openat(s->directory, ".allowlist.pending", O_WRONLY | O_CREAT | O_APPEND | O_NOFOLLOW, 0600);
    assert(fd >= 0);
    uint32_t uid = s->candidate.curr_uid;
    checked_write(fd, &uid, sizeof(uid));
    int ret = s->fault == GUARD_SYNC ? -EIO : fsync(fd);
    assert(!close(fd));
    if (ret)
        return ret;
    return s->fault == GUARD_DIR_SYNC ? -EIO : fsync(s->directory);
}

static void write_record(int fd, const struct app_profile *profile, uint32_t mode)
{
    const uint64_t duration = 0;
    checked_write(fd, profile, sizeof(*profile));
    checked_write(fd, &mode, sizeof(mode));
    checked_write(fd, &duration, sizeof(duration));
}

static int write_snapshot(void *ctx)
{
    struct store *s = ctx;
    const uint32_t header[] = {0x7f4b5355, 5};
    assert(!unlinkat(s->directory, ".allowlist.tmp", 0) || errno == ENOENT);
    s->staging = openat(s->directory, ".allowlist.tmp", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    assert(s->staging >= 0);
    checked_write(s->staging, header, sizeof(header));
    write_record(s->staging, &s->bystander, KSU_AUTH_PERMANENT);
    if (s->fault == WRITE_PARTIAL) {
        checked_write(s->staging, &s->candidate, 20);
        return -EIO;
    }
    write_record(s->staging, &s->candidate, s->mode);
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

/* Mirrors ksu_load_allow_list(): guarded uids lose Root, other records load.
 * Temporary grants never survive a reboot, so only Permanent records grant. */
static struct loaded reboot(struct store *s)
{
    struct ksu_auth_guard guard = {0};
    bool guarded = false;
    int fd = openat(s->directory, ".allowlist.pending", O_RDONLY | O_NOFOLLOW);
    if (fd >= 0) {
        uint32_t uids[KSU_AUTH_GUARD_MAX_UIDS + 1];
        ssize_t len = read(fd, uids, sizeof(uids));
        assert(len >= 0 && !close(fd));
        ksu_auth_guard_parse(&guard, uids, (size_t)len);
        guarded = true;
    } else {
        assert(errno == ENOENT);
    }
    struct loaded result = {0};
    fd = openat(s->directory, ".allowlist", O_RDONLY);
    assert(fd >= 0);
    assert(lseek(fd, 8, SEEK_SET) == 8);
    struct app_profile profile;
    uint32_t mode;
    uint64_t duration;
    while (read(fd, &profile, sizeof(profile)) == sizeof(profile)) {
        assert(read(fd, &mode, sizeof(mode)) == sizeof(mode));
        assert(read(fd, &duration, sizeof(duration)) == sizeof(duration));
        if (guarded && ksu_auth_guard_covers(&guard, profile.curr_uid))
            ksu_auth_strip_root(&profile);
        bool root = profile.allow_su && mode == KSU_AUTH_PERMANENT;
        if (profile.curr_uid == s->candidate.curr_uid)
            result.candidate_root = root;
        else if (profile.curr_uid == s->bystander.curr_uid)
            result.bystander_root = root;
    }
    assert(!close(fd));
    return result;
}

static void close_staging(struct store *s)
{
    if (s->staging >= 0) {
        assert(!close(s->staging));
        s->staging = -1;
    }
}

static void init_profile(struct app_profile *profile, int32_t uid, const char *key, bool allow_su)
{
    memset(profile, 0, sizeof(*profile));
    profile->version = KSU_APP_PROFILE_VER;
    profile->curr_uid = uid;
    profile->allow_su = allow_su;
    strcpy(profile->key, key);
}

static struct store open_store(char *dir)
{
    assert(mkdtemp(dir));
    struct store s = {.directory = open(dir, O_RDONLY | O_DIRECTORY), .staging = -1};
    assert(s.directory >= 0);
    init_profile(&s.bystander, 10200, "test.bystander", true);
    init_profile(&s.candidate, 10123, "test.fixture", false);
    return s;
}

static void close_store(struct store *s, const char *dir)
{
    assert(!unlinkat(s->directory, ".allowlist.pending", 0) || errno == ENOENT);
    assert(!unlinkat(s->directory, ".allowlist.tmp", 0) || errno == ENOENT);
    assert(!unlinkat(s->directory, ".allowlist", 0));
    assert(!close(s->directory));
    assert(!rmdir(dir));
}

static void guard_parse_cases(void)
{
    struct ksu_auth_guard guard;
    const uint32_t uids[KSU_AUTH_GUARD_MAX_UIDS + 1] = {10123, 10456};

    ksu_auth_guard_parse(&guard, uids, 0); /* marker created, uid not yet written */
    assert(!guard.all && !ksu_auth_guard_covers(&guard, 10123));
    ksu_auth_guard_parse(&guard, uids, sizeof(uint32_t) * 2 + 2); /* torn third uid */
    assert(!guard.all && guard.count == 2);
    assert(ksu_auth_guard_covers(&guard, 10123) && ksu_auth_guard_covers(&guard, 10456));
    assert(!ksu_auth_guard_covers(&guard, 10200));
    ksu_auth_guard_parse(&guard, uids, sizeof(uids)); /* more than the loader can track */
    assert(guard.all && ksu_auth_guard_covers(&guard, 10200));

    struct app_profile profile;
    init_profile(&profile, 10123, "test.fixture", true);
    profile.rp_config.use_default = false;
    profile.rp_config.profile.uid = 0;
    ksu_auth_strip_root(&profile);
    assert(!profile.allow_su && profile.nrp_config.use_default);
    assert(!strcmp(profile.key, "test.fixture") && profile.curr_uid == 10123);
}

int main(void)
{
    guard_parse_cases();
    for (enum fault fault = NONE; fault <= CLEANUP_DIR_SYNC; ++fault) {
        char dir[] = "/tmp/lunexsu-store-XXXXXX";
        struct store s = open_store(dir);
        assert(!ksu_auth_store(&ops, &s)); /* Durable denied baseline. */
        close_staging(&s);
        struct loaded loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root);

        /* A stale staging hard link must be removed, never truncated. */
        assert(!linkat(s.directory, ".allowlist", s.directory, ".allowlist.tmp", 0));
        s.candidate.allow_su = true;
        s.fault = fault;
        int ret = ksu_auth_store(&ops, &s);
        close_staging(&s);
        bool committed = fault == NONE || fault >= CLEANUP_UNLINK;
        assert((ret == 0) == committed);
        loaded = reboot(&s);
        /* An unfinished transaction never costs another app its grant. */
        assert(loaded.bystander_root);
        if (!committed) {
            assert(!loaded.candidate_root); /* Rejected Permanent never loads. */
            if (fault < REPLACEMENT_DIR_SYNC) {
                /* Before atomic replacement, the live file is untouched. */
                int fd = openat(s.directory, ".allowlist", O_RDONLY);
                struct app_profile profile;
                assert(fd >= 0 && lseek(fd, 8 + sizeof(profile) + 12, SEEK_SET) == (off_t)(8 + sizeof(profile) + 12));
                assert(read(fd, &profile, sizeof(profile)) == sizeof(profile));
                assert(profile.curr_uid == 10123 && !profile.allow_su);
                assert(!close(fd));
            }
        } else {
            assert(loaded.candidate_root == (fault != CLEANUP_UNLINK));
            assert(s.cleanup_failed == (fault >= CLEANUP_UNLINK));
        }
        /* A retry based on unchanged in-memory denied policy removes all
         * failed candidate state, including a post-rename failure. */
        s.candidate.allow_su = false;
        s.fault = NONE;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root);
        close_store(&s, dir);
    }
    /* Simulate reboot/interruption after every completed transaction stage. */
    for (unsigned stage = 0; stage < 5; ++stage) {
        char dir[] = "/tmp/lunexsu-interrupt-XXXXXX";
        struct store s = open_store(dir);
        s.candidate.allow_su = true;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        assert(reboot(&s).candidate_root); /* Start with a durable Permanent grant. */
        s.mode = KSU_AUTH_TIMED;
        assert(!prepare(&s));
        if (stage >= 1) assert(!write_snapshot(&s));
        if (stage >= 2) assert(!sync_snapshot(&s));
        if (stage >= 3) assert(!replace(&s));
        if (stage >= 4) assert(!sync_directory(&s));
        close_staging(&s);
        struct loaded loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root);
        s.candidate.allow_su = false;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root);
        close_store(&s, dir);
    }
    /* Two unfinished transactions in a row: the second must not un-guard the first. */
    {
        char dir[] = "/tmp/lunexsu-twice-XXXXXX";
        struct store s = open_store(dir);
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        s.candidate.allow_su = true;
        s.fault = REPLACEMENT_DIR_SYNC; /* rename landed, then reported failure */
        assert(ksu_auth_store(&ops, &s));
        close_staging(&s);
        struct app_profile first = s.candidate;
        init_profile(&s.candidate, 10456, "test.other", false);
        s.fault = RENAME;
        assert(ksu_auth_store(&ops, &s));
        close_staging(&s);
        s.candidate = first;
        struct loaded loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root);
        close_store(&s, dir);
    }
    puts("PASS: production persistence transaction, 10 I/O faults, interrupted stages, uid-scoped reboot revocation and retry");
    return 0;
}
