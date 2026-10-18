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
    bool recovery; /* A full committed snapshot, with no replacement candidate. */
};

struct loaded {
    bool candidate_root;
    bool bystander_root;
};

static void checked_write(int fd, const void *data, size_t size)
{
    assert(write(fd, data, size) == (ssize_t)size);
}

/* Same production boundary predicate as prepare_allowlist_store(); I/O alone
 * is replaced with POSIX calls on disposable host files. */
static int prepare(void *ctx)
{
    struct store *s = ctx;
    if (s->fault == GUARD_OPEN)
        return -EIO;
    int fd = openat(s->directory, ".allowlist.pending", O_WRONLY | O_CREAT | O_APPEND | O_NOFOLLOW, 0600);
    assert(fd >= 0);
    if (!s->recovery) {
        struct stat st;
        assert(!fstat(fd, &st));
        if (st.st_size < 0 || !ksu_auth_guard_can_append((uint64_t)st.st_size)) {
            assert(!close(fd));
            return -EINVAL;
        }
        uint32_t uid = s->candidate.curr_uid;
        checked_write(fd, &uid, sizeof(uid));
    }
    int ret = s->fault == GUARD_SYNC ? -EIO : fsync(fd);
    assert(!close(fd));
    if (ret)
        return ret;
    return s->fault == GUARD_DIR_SYNC ? -EIO : fsync(s->directory);
}

/* Mirrors write_allowlist_record(): upstream v4 records, temporary grants
 * written without Root. */
static void write_record(int fd, const struct app_profile *profile, uint32_t mode)
{
    struct app_profile record = *profile;
    ksu_auth_prepare_record(&record, mode);
    checked_write(fd, &record, sizeof(record));
}

static int write_snapshot(void *ctx)
{
    struct store *s = ctx;
    const uint32_t header[] = {0x7f4b5355, 4};
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
 * A v4 record grants Root exactly when it says so, as for an upstream Core. */
static struct loaded reboot(struct store *s)
{
    struct ksu_auth_guard guard = {0};
    bool guarded = false;
    int fd = openat(s->directory, ".allowlist.pending", O_RDONLY | O_NOFOLLOW);
    if (fd >= 0) {
        uint32_t uids[KSU_AUTH_GUARD_MAX_UIDS + 1];
        ssize_t len = read(fd, uids, sizeof(uids));
        struct stat st;
        assert(len >= 0 && !fstat(fd, &st) && !close(fd));
        if (len != st.st_size)
            guard.all = true;
        else
            ksu_auth_guard_parse(&guard, uids, (size_t)len);
        guarded = true;
    } else {
        assert(errno == ENOENT);
    }
    struct loaded result = {0};
    fd = openat(s->directory, ".allowlist", O_RDONLY);
    assert(fd >= 0);
    uint32_t header[2];
    assert(read(fd, header, sizeof(header)) == sizeof(header) && header[1] == 4);
    struct app_profile profile;
    while (read(fd, &profile, sizeof(profile)) == sizeof(profile)) {
        if (guarded && ksu_auth_guard_covers(&guard, profile.curr_uid))
            ksu_auth_strip_root(&profile);
        bool root = profile.allow_su;
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

static struct store open_store(char *dir, size_t size)
{
    const char *tmp = getenv("TMPDIR");
    int len = snprintf(dir, size, "%s/lunexsu-store-XXXXXX", tmp ? tmp : ".");
    assert(len > 0 && (size_t)len < size);
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
    for (size_t tail = 1; tail <= 7; tail++) {
        ksu_auth_guard_parse(&guard, uids, sizeof(uint32_t) + tail);
        bool aligned = tail % sizeof(uint32_t) == 0;
        assert(ksu_auth_guard_can_append(sizeof(uint32_t) + tail) == aligned);
        assert(guard.all == !aligned);
        assert(ksu_auth_guard_covers(&guard, 10123) && ksu_auth_guard_covers(&guard, 10456));
        assert(ksu_auth_guard_covers(&guard, 10200) == !aligned);
        ksu_auth_guard_parse(&guard, uids, tail);
        assert(guard.all == !aligned);
        assert(ksu_auth_guard_covers(&guard, 10200) == !aligned);
    }
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

static void check_guard_bytes(struct store *s, const void *expected, size_t len)
{
    unsigned char bytes[64];
    int fd = openat(s->directory, ".allowlist.pending", O_RDONLY);
    assert(fd >= 0 && len < sizeof(bytes));
    assert(read(fd, bytes, sizeof(bytes)) == (ssize_t)len);
    assert(!memcmp(bytes, expected, len) && !close(fd));
}

static void torn_guard_transactions(void)
{
    for (size_t tail = 1; tail <= 7; ++tail) {
        char dir[4096];
        struct store s = open_store(dir, sizeof(dir));
        s.candidate.allow_su = true;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        assert(reboot(&s).candidate_root && reboot(&s).bystander_root);

        /* A complete guard from an earlier failed replacement, followed by a
         * 1..7-byte tail. Four bytes are a complete uid, all others are torn. */
        const uint32_t marker[] = {10123, 10456, 10567};
        size_t len = sizeof(uint32_t) + tail;
        int fd = openat(s.directory, ".allowlist.pending", O_WRONLY | O_CREAT | O_EXCL, 0600);
        assert(fd >= 0);
        checked_write(fd, marker, len);
        assert(!fsync(fd) && !close(fd) && !fsync(s.directory));
        struct app_profile first = s.candidate;
        init_profile(&s.candidate, 10456, "test.other", true);
        s.fault = RENAME;
        bool aligned = tail % sizeof(uint32_t) == 0;
        for (int retry = 0; retry < 3; ++retry) {
            int ret = ksu_auth_store(&ops, &s);
            assert(ret == (aligned ? -EIO : -EINVAL));
            if (!aligned) {
                assert(s.staging == -1); /* No staging write or rename. */
                check_guard_bytes(&s, marker, len);
            }
            close_staging(&s);
        }
        s.candidate = first;
        struct loaded loaded = reboot(&s);
        assert(!loaded.candidate_root);
        assert(loaded.bystander_root == aligned);

        /* Restart imports only the stripped records. A recovery snapshot can
         * bypass a bad append boundary, but may clean it only after commit. */
        s.recovery = true;
        s.candidate.allow_su = loaded.candidate_root;
        s.bystander.allow_su = loaded.bystander_root;
        s.fault = WRITE_LATER_RECORD;
        assert(ksu_auth_store(&ops, &s) == -EIO);
        close_staging(&s);
        if (!aligned)
            check_guard_bytes(&s, marker, len);
        loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root == aligned);
        s.fault = REPLACEMENT_DIR_SYNC;
        assert(ksu_auth_store(&ops, &s) == -EIO);
        close_staging(&s);
        if (!aligned)
            check_guard_bytes(&s, marker, len);
        s.fault = NONE;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        assert(faccessat(s.directory, ".allowlist.pending", F_OK, 0) == -1 && errno == ENOENT);
        loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root == aligned);

        /* Once recovered, ordinary explicit Permanent grants restore cleanly. */
        s.recovery = false;
        s.candidate.allow_su = s.bystander.allow_su = true;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        loaded = reboot(&s);
        assert(loaded.candidate_root && loaded.bystander_root);
        close_store(&s, dir);
    }
}

int main(void)
{
    guard_parse_cases();
    torn_guard_transactions();
    for (enum fault fault = NONE; fault <= CLEANUP_DIR_SYNC; ++fault) {
        char dir[4096];
        struct store s = open_store(dir, sizeof(dir));
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
                assert(fd >= 0 && lseek(fd, 8 + sizeof(profile), SEEK_SET) == (off_t)(8 + sizeof(profile)));
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
        char dir[4096];
        struct store s = open_store(dir, sizeof(dir));
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
        char dir[4096];
        struct store s = open_store(dir, sizeof(dir));
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
    /* Committed temporary grants are stored without Root, so neither this
     * loader nor an upstream downgrade can restore them as Permanent. */
    for (uint32_t mode = KSU_AUTH_ONCE; mode <= KSU_AUTH_TIMED; ++mode) {
        char dir[4096];
        struct store s = open_store(dir, sizeof(dir));
        s.candidate.allow_su = true;
        s.mode = mode;
        assert(!ksu_auth_store(&ops, &s));
        close_staging(&s);
        struct loaded loaded = reboot(&s);
        assert(!loaded.candidate_root && loaded.bystander_root);
        close_store(&s, dir);
    }
    /* Format 5 records from earlier builds restore the same way. */
    {
        struct app_profile profile;
        init_profile(&profile, 10123, "test.fixture", true);
        ksu_auth_prepare_record(&profile, KSU_AUTH_ONCE);
        assert(!profile.allow_su && profile.nrp_config.use_default);
        init_profile(&profile, 10123, "test.fixture", true);
        ksu_auth_prepare_record(&profile, KSU_AUTH_PERMANENT);
        assert(profile.allow_su);
    }
    puts("PASS: production persistence helpers, tails 1..7, repeated failures, restart/recovery, 10 I/O faults, interrupted stages, upstream v4 and temporary grants");
    return 0;
}
