// Host driver for userspace/sui/module/src/main/cpp/util/shell_runtime.cpp. Each scenario plays
// the shell user: it plants links, FIFOs or directories where the root starter writes, then checks
// that nothing outside the runtime directory changed.
#include "shell_runtime.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

#define CHECK(cond)                                                                    \
    do {                                                                               \
        if (!(cond)) {                                                                 \
            fprintf(stderr, "%s:%d: CHECK failed: %s (errno %d: %s)\n", __FILE__,      \
                    __LINE__, #cond, errno, strerror(errno));                          \
            exit(1);                                                                   \
        }                                                                              \
    } while (0)

static std::string root;

static std::string path(const char* name) { return root + "/" + name; }

static void write_file(const std::string& p, const char* content, mode_t mode = 0600) {
    int fd = open(p.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    CHECK(fd >= 0);
    CHECK(write(fd, content, strlen(content)) == static_cast<ssize_t>(strlen(content)));
    CHECK(close(fd) == 0);
}

static std::string read_file(const std::string& p) {
    int fd = open(p.c_str(), O_RDONLY | O_CLOEXEC);
    CHECK(fd >= 0);
    char buf[4096];
    ssize_t r = read(fd, buf, sizeof(buf));
    CHECK(r >= 0);
    close(fd);
    return std::string(buf, static_cast<size_t>(r));
}

static mode_t mode_of(const std::string& p) {
    struct stat st{};
    CHECK(lstat(p.c_str(), &st) == 0);
    return st.st_mode;
}

static int entry_count(const std::string& dir) {
    DIR* d = opendir(dir.c_str());
    CHECK(d != nullptr);
    int n = 0;
    while (struct dirent* e = readdir(d)) {
        if (strcmp(e->d_name, ".") != 0 && strcmp(e->d_name, "..") != 0)
            ++n;
    }
    closedir(d);
    return n;
}

static int open_root() {
    int fd = shell_runtime_open_dir(AT_FDCWD, root.c_str());
    CHECK(fd >= 0);
    return fd;
}

static int labels = 0;
static bool count_label(int fd) {
    struct stat st{};
    // Labeling happens on the still-private temporary file, before it becomes visible.
    CHECK(fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && (st.st_mode & 0777) == 0600);
    ++labels;
    return true;
}
static bool refuse_label(int) {
    errno = EACCES;
    return false;
}

static int source_fd(const char* content) {
    write_file(path("source"), content);
    int fd = shell_runtime_open_regular(AT_FDCWD, path("source").c_str(), -1);
    CHECK(fd >= 0);
    return fd;
}

// A link planted at the target is replaced, never followed: the outside file keeps its content
// and mode, and the runtime entry becomes a fresh regular file.
static void replace_link_target() {
    write_file(path("outside"), "root secret", 0600);
    CHECK(mkdir(path("dir").c_str(), 0755) == 0);
    CHECK(symlink(path("outside").c_str(), path("dir/sui.dex").c_str()) == 0);
    int dir = shell_runtime_open_dir(AT_FDCWD, path("dir").c_str());
    CHECK(dir >= 0);
    int src = source_fd("dex payload");
    CHECK(shell_runtime_replace_file(dir, "sui.dex", src, 0644, getuid(), getgid(), count_label) == 0);
    CHECK(labels == 1);
    CHECK(read_file(path("outside")) == "root secret");
    CHECK((mode_of(path("outside")) & 0777) == 0600);
    CHECK(S_ISREG(mode_of(path("dir/sui.dex"))));
    CHECK((mode_of(path("dir/sui.dex")) & 0777) == 0644);
    CHECK(read_file(path("dir/sui.dex")) == "dex payload");
    CHECK(entry_count(path("dir")) == 1);
}

// An existing regular file (hard links included) is replaced by rename, so its inode is untouched.
static void replace_keeps_other_links() {
    CHECK(mkdir(path("dir").c_str(), 0755) == 0);
    write_file(path("dir/librish.so"), "old", 0600);
    CHECK(link(path("dir/librish.so").c_str(), path("other_link").c_str()) == 0);
    int dir = shell_runtime_open_dir(AT_FDCWD, path("dir").c_str());
    int src = source_fd("new library");
    CHECK(shell_runtime_replace_file(dir, "librish.so", src, 0644, getuid(), getgid(), nullptr) == 0);
    CHECK(read_file(path("other_link")) == "old");
    CHECK(read_file(path("dir/librish.so")) == "new library");
}

// A directory planted at the target name makes the copy fail without leaving a temporary entry.
static void directory_target_fails_cleanly() {
    CHECK(mkdir(path("dir").c_str(), 0755) == 0);
    CHECK(mkdir(path("dir/sui.dex").c_str(), 0755) == 0);
    int dir = shell_runtime_open_dir(AT_FDCWD, path("dir").c_str());
    int src = source_fd("dex payload");
    CHECK(shell_runtime_replace_file(dir, "sui.dex", src, 0644, getuid(), getgid(), nullptr) != 0);
    CHECK(S_ISDIR(mode_of(path("dir/sui.dex"))));
    CHECK(entry_count(path("dir")) == 1);
}

// A failed label aborts before the file becomes visible and leaves no temporary entry.
static void label_failure_publishes_nothing() {
    CHECK(mkdir(path("dir").c_str(), 0755) == 0);
    int dir = shell_runtime_open_dir(AT_FDCWD, path("dir").c_str());
    int src = source_fd("dex payload");
    CHECK(shell_runtime_replace_file(dir, "sui.dex", src, 0644, getuid(), getgid(), refuse_label) != 0);
    CHECK(errno == EACCES);
    CHECK(entry_count(path("dir")) == 0);
}

// The runtime directory itself may not be a link: opening or creating it must not reach the
// outside directory, which keeps its mode.
static void directory_link_is_refused() {
    CHECK(mkdir(path("outside_dir").c_str(), 0700) == 0);
    CHECK(symlink(path("outside_dir").c_str(), path("sui_shell_x").c_str()) == 0);
    int base = open_root();
    CHECK(shell_runtime_open_or_create_dir(base, "sui_shell_x", 0755) < 0);
    CHECK(errno == ELOOP || errno == ENOTDIR);
    CHECK(shell_runtime_open_dir(base, "sui_shell_x") < 0);
    CHECK((mode_of(path("outside_dir")) & 0777) == 0700);
    CHECK(S_ISLNK(mode_of(path("sui_shell_x"))));
}

static void directory_is_created_and_reopened() {
    int base = open_root();
    int dir = shell_runtime_open_or_create_dir(base, "sui_shell_new", 0755);
    CHECK(dir >= 0);
    close(dir);
    dir = shell_runtime_open_or_create_dir(base, "sui_shell_new", 0755);
    CHECK(dir >= 0);
    CHECK(S_ISDIR(mode_of(path("sui_shell_new"))));
    CHECK(shell_runtime_open_or_create_dir(base, "../escape", 0755) < 0 && errno == EINVAL);
    CHECK(shell_runtime_open_or_create_dir(base, "..", 0755) < 0 && errno == EINVAL);
}

// Sources are regular files only: a link, a FIFO (which would otherwise block root forever) and an
// oversized file are refused immediately.
static void sources_are_regular_and_bounded() {
    write_file(path("big"), "0123456789");
    CHECK(symlink(path("big").c_str(), path("link2").c_str()) == 0);
    CHECK(mkfifo(path("fifo").c_str(), 0600) == 0);
    int base = open_root();
    CHECK(shell_runtime_open_regular(base, "link2", -1) < 0 && errno == ELOOP);
    alarm(5);
    CHECK(shell_runtime_open_regular(base, "fifo", -1) < 0 && errno == EINVAL);
    alarm(0);
    CHECK(shell_runtime_open_regular(base, "big", 9) < 0 && errno == EFBIG);
    int fd = shell_runtime_open_regular(base, "big", 10);
    CHECK(fd >= 0);
    CHECK((fcntl(fd, F_GETFL) & O_NONBLOCK) == 0);
    CHECK(shell_runtime_open_regular(base, "missing", -1) < 0 && errno == ENOENT);
}

// Legacy cleanup removes plain files and links themselves, never what a link points to.
static void legacy_cleanup_unlinks_links_not_targets() {
    write_file(path("outside"), "keep me");
    CHECK(mkdir(path("outside_dir").c_str(), 0755) == 0);
    write_file(path("outside_dir/file"), "keep me too");
    CHECK(mkdir(path("sui_shell").c_str(), 0755) == 0);
    write_file(path("sui_shell/sui_uids.txt"), "10000:1\n");
    CHECK(symlink(path("outside").c_str(), path("sui_shell/link").c_str()) == 0);
    CHECK(symlink(path("outside_dir").c_str(), path("sui_shell/dirlink").c_str()) == 0);
    int base = open_root();
    CHECK(shell_runtime_remove_dir(base, "sui_shell") == 0);
    CHECK(access(path("sui_shell").c_str(), F_OK) != 0);
    CHECK(read_file(path("outside")) == "keep me");
    CHECK(read_file(path("outside_dir/file")) == "keep me too");
}

// A legacy directory replaced by a link is left alone: nothing in the link target is removed.
static void legacy_link_is_not_cleaned() {
    CHECK(mkdir(path("outside_dir").c_str(), 0755) == 0);
    write_file(path("outside_dir/precious"), "keep me");
    CHECK(symlink(path("outside_dir").c_str(), path("sui_shell").c_str()) == 0);
    int base = open_root();
    CHECK(shell_runtime_remove_dir(base, "sui_shell") != 0);
    CHECK(read_file(path("outside_dir/precious")) == "keep me");
    CHECK(S_ISLNK(mode_of(path("sui_shell"))));
}

int main(int argc, char** argv) {
    if (argc != 3)
        return 2;
    root = argv[1];
    std::string scenario = argv[2];
    struct {
        const char* name;
        void (*run)();
    } scenarios[] = {
            {"replace_link_target", replace_link_target},
            {"replace_keeps_other_links", replace_keeps_other_links},
            {"directory_target_fails_cleanly", directory_target_fails_cleanly},
            {"label_failure_publishes_nothing", label_failure_publishes_nothing},
            {"directory_link_is_refused", directory_link_is_refused},
            {"directory_is_created_and_reopened", directory_is_created_and_reopened},
            {"sources_are_regular_and_bounded", sources_are_regular_and_bounded},
            {"legacy_cleanup_unlinks_links_not_targets", legacy_cleanup_unlinks_links_not_targets},
            {"legacy_link_is_not_cleaned", legacy_link_is_not_cleaned},
    };
    for (auto& s : scenarios) {
        if (scenario == s.name) {
            s.run();
            puts("ok");
            return 0;
        }
    }
    if (scenario == "list") {
        for (auto& s : scenarios)
            puts(s.name);
        return 0;
    }
    return 2;
}
