/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021-2026 Sui Contributors
 */

#include <cstdlib>
#include <cstring>
#include <csignal>
#include <android/api-level.h>
#include <grp.h>
#include <logging.h>
#include <unistd.h>
#include <sched.h>
#include <app_process.h>
#include <misc.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <fcntl.h>
#include <selinux.h>
#include <string>
#include <dirent.h>
#include <ksu.h>
#include <shell_runtime.h>

static constexpr const char* SUI_DATA_DIR = "/data/adb/sui";
static constexpr const char* KSU_NO_ESCAPE_MARKER = "/data/adb/sui/ksu_no_escape";
static constexpr const char* LEGACY_SHELL_DIR = "/data/local/tmp/sui_shell";
static constexpr const char* SHELL_BASE_DIR = "/data/local/tmp";
static constexpr const char* SHELL_DIR_PREFIX = "sui_shell_";
static constexpr const char* SHELL_DIR_MARKER = "/data/adb/sui/shell_dir_name";
static constexpr const char* SHELL_DATA_FILE_CONTEXT = "u:object_r:shell_data_file:s0";

static constexpr uid_t SHELL_UID = 2000;
static constexpr gid_t SHELL_GID = 2000;
// sui_uids.txt only lists UID:flags pairs; anything larger is not ours to copy as root.
static constexpr off_t MAX_LEGACY_UIDS_SIZE = 1024 * 1024;

// Labels through the descriptor: UID 2000 owns the runtime directory and could otherwise swap the
// path for a link between creation and labeling.
static bool set_shell_runtime_context(int fd) {
    char* context = nullptr;
    if (fgetfilecon_raw(fd, &context) >= 0) {
        bool matched = strcmp(context, SHELL_DATA_FILE_CONTEXT) == 0;
        freecon(context);
        if (matched) {
            return true;
        }
    }

    if (fsetfilecon_raw(fd, SHELL_DATA_FILE_CONTEXT) != 0) {
        PLOGE("fsetfilecon -> %s", SHELL_DATA_FILE_CONTEXT);
        return false;
    }

    context = nullptr;
    if (fgetfilecon_raw(fd, &context) < 0) {
        PLOGE("fgetfilecon");
        return false;
    }
    bool matched = strcmp(context, SHELL_DATA_FILE_CONTEXT) == 0;
    if (!matched) {
        LOGE("unexpected SELinux context for a shell runtime entry: %s", context);
    }
    freecon(context);
    return matched;
}

// Recreates `name` in the shell runtime directory from a root-owned module file. The previous
// entry, which UID 2000 may have replaced with a link, is renamed over and never opened.
static bool prepare_shell_runtime_file(const char* source, int dir_fd, const char* name,
                                       mode_t mode) {
    int src_fd = shell_runtime_open_regular(AT_FDCWD, source, -1);
    if (src_fd < 0) {
        PLOGE("open %s", source);
        return false;
    }
    int rc = shell_runtime_replace_file(dir_fd, name, src_fd, mode, SHELL_UID, SHELL_GID,
                                        set_shell_runtime_context);
    int err = errno;
    close(src_fd);
    if (rc != 0) {
        errno = err;
        PLOGE("prepare shell runtime file %s", name);
        return false;
    }
    return true;
}

// Opens (creating if needed) the runtime directory under /data/local/tmp without following a
// link, and hands it to UID 2000 through the descriptor.
static int open_shell_runtime_dir(const char* name) {
    int base_fd = shell_runtime_open_dir(AT_FDCWD, SHELL_BASE_DIR);
    if (base_fd < 0) {
        PLOGE("open %s", SHELL_BASE_DIR);
        return -1;
    }
    int dir_fd = shell_runtime_open_or_create_dir(base_fd, name, 0755);
    int err = errno;
    close(base_fd);
    if (dir_fd < 0) {
        errno = err;
        PLOGE("open shell runtime directory %s/%s", SHELL_BASE_DIR, name);
        return -1;
    }
    struct stat st{};
    if (fstat(dir_fd, &st) != 0 || (st.st_uid != 0 && st.st_uid != SHELL_UID)) {
        LOGE("unexpected owner of shell runtime directory %s/%s", SHELL_BASE_DIR, name);
        close(dir_fd);
        return -1;
    }
    if (!set_shell_runtime_context(dir_fd)) {
        close(dir_fd);
        return -1;
    }
    if (fchown(dir_fd, SHELL_UID, SHELL_GID) != 0 || fchmod(dir_fd, 0755) != 0) {
        PLOGE("hand over shell runtime directory %s/%s", SHELL_BASE_DIR, name);
        close(dir_fd);
        return -1;
    }
    return dir_fd;
}

static void log_context(const char* stage) {
    char* con = nullptr;
    if (getcon(&con) == 0 && con != nullptr) {
        LOGI("%s: uid=%d gid=%d context=%s", stage, getuid(), getgid(), con);
    } else {
        PLOGE("getcon(%s)", stage);
    }
    freecon(con);
}

static int set_shell_supplementary_groups() {
    gid_t groups[14];
    size_t count = 0;
    const int api = android_get_device_api_level();

    // Android 6 / API 23 baseline from AOSP adbd.
    groups[count++] = 1011;  // adb
    groups[count++] = 1007;  // log
    groups[count++] = 1004;  // input
    groups[count++] = 3003;  // inet
    groups[count++] = 3002;  // net_bt
    groups[count++] = 3001;  // net_bt_admin
    groups[count++] = 1028;  // sdcard_r
    groups[count++] = 1015;  // sdcard_rw
    groups[count++] = 3006;  // net_bw_stats

    if (api >= 24) {
        groups[count++] = 3009;  // readproc
    }
    if (api >= 27) {
        groups[count++] = 3011;  // uhid
    }
    if (api >= 30) {
        groups[count++] = 1078;  // ext_data_rw
        groups[count++] = 1079;  // ext_obb_rw
    }
    if (api >= 32) {
        groups[count++] = 3012;  // readtracefs
    }

    LOGI("shell supplementary groups: api=%d count=%zu", api, count);
    return setgroups(count, groups);
}

static std::string trim_copy(const std::string& input) {
    size_t begin = 0;
    size_t end = input.size();
    while (begin < end && (input[begin] == ' ' || input[begin] == '\n' || input[begin] == '\r' ||
                           input[begin] == '\t')) {
        ++begin;
    }
    while (end > begin && (input[end - 1] == ' ' || input[end - 1] == '\n' ||
                           input[end - 1] == '\r' || input[end - 1] == '\t')) {
        --end;
    }
    return input.substr(begin, end - begin);
}

static bool is_valid_shell_dir_name(const std::string& name) {
    if (name == "sui_shell") {
        return true;
    }
    if (name.rfind(SHELL_DIR_PREFIX, 0) != 0) {
        return false;
    }
    for (char c : name) {
        bool ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || c == '_' || c == '-';
        if (!ok)
            return false;
    }
    return true;
}

// The runtime directory is always a direct child of SHELL_BASE_DIR with a validated name.
static bool shell_dir_name_of(const std::string& path, std::string& name) {
    size_t base_len = strlen(SHELL_BASE_DIR);
    if (path.rfind(SHELL_BASE_DIR, 0) != 0 || path.size() <= base_len + 1 ||
        path[base_len] != '/') {
        return false;
    }
    name = path.substr(base_len + 1);
    return is_valid_shell_dir_name(name);
}

static bool read_file_line(const char* path, std::string& out) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return false;
    char buf[256]{0};
    ssize_t r = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (r <= 0)
        return false;
    out = trim_copy(std::string(buf, static_cast<size_t>(r)));
    return !out.empty();
}

static int write_text_atomic(const char* path, const std::string& content) {
    std::string tmp_path = std::string(path) + ".tmp";
    int fd = open(tmp_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0)
        return -1;
    if (write_full(fd, content.data(), content.size()) != 0 || fsync(fd) != 0) {
        close(fd);
        unlink(tmp_path.c_str());
        return -1;
    }
    close(fd);
    if (rename(tmp_path.c_str(), path) != 0) {
        unlink(tmp_path.c_str());
        return -1;
    }
    return 0;
}

static std::string generate_shell_dir_name() {
    unsigned char random_bytes[8]{0};
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        if (read_full(fd, random_bytes, sizeof(random_bytes)) != 0) {
            memset(random_bytes, 0, sizeof(random_bytes));
        }
        close(fd);
    }
    char suffix[17]{0};
    static constexpr const char* hex = "0123456789abcdef";
    for (size_t i = 0; i < sizeof(random_bytes); ++i) {
        suffix[i * 2] = hex[(random_bytes[i] >> 4) & 0x0f];
        suffix[i * 2 + 1] = hex[random_bytes[i] & 0x0f];
    }
    return std::string(SHELL_DIR_PREFIX) + suffix;
}

static std::string generate_unique_shell_dir_name() {
    for (int i = 0; i < 8; ++i) {
        std::string name = generate_shell_dir_name();
        std::string path = std::string(SHELL_BASE_DIR) + "/" + name;
        if (access(path.c_str(), F_OK) != 0) {
            return name;
        }
    }
    char fallback[64]{0};
    snprintf(fallback, sizeof(fallback), "%s%ld", SHELL_DIR_PREFIX, static_cast<long>(getpid()));
    return std::string(fallback);
}

// The legacy directory is shell-owned: a link there must not make root list or unlink another
// directory, so the cleanup works on descriptors and leaves a link alone.
static void cleanup_legacy_shell_dir_best_effort() {
    int base_fd = shell_runtime_open_dir(AT_FDCWD, SHELL_BASE_DIR);
    if (base_fd < 0) {
        LOGW("open %s for legacy cleanup failed with %d: %s", SHELL_BASE_DIR, errno,
             strerror(errno));
        return;
    }
    if (shell_runtime_remove_dir(base_fd, "sui_shell") != 0) {
        LOGW("remove legacy shell dir failed: %s (%d: %s)", LEGACY_SHELL_DIR, errno,
             strerror(errno));
    } else {
        LOGI("legacy shell dir removed: %s", LEGACY_SHELL_DIR);
    }
    close(base_fd);
}

// Copies the legacy sui_uids.txt without following a link or reading a FIFO as root.
static void copy_legacy_uids_best_effort(int new_dir_fd) {
    int base_fd = shell_runtime_open_dir(AT_FDCWD, SHELL_BASE_DIR);
    if (base_fd < 0) {
        return;
    }
    int legacy_fd = shell_runtime_open_dir(base_fd, "sui_shell");
    close(base_fd);
    if (legacy_fd < 0) {
        if (errno != ENOENT) {
            LOGW("open legacy shell dir failed with %d: %s", errno, strerror(errno));
        }
        return;
    }
    int src_fd = shell_runtime_open_regular(legacy_fd, "sui_uids.txt", MAX_LEGACY_UIDS_SIZE);
    close(legacy_fd);
    if (src_fd < 0) {
        if (errno != ENOENT) {
            LOGW("open legacy sui_uids.txt failed with %d: %s", errno, strerror(errno));
        }
        return;
    }
    if (shell_runtime_replace_file(new_dir_fd, "sui_uids.txt", src_fd, 0644, SHELL_UID, SHELL_GID,
                                   nullptr) != 0) {
        LOGW("copy legacy sui_uids.txt failed with %d: %s", errno, strerror(errno));
    }
    close(src_fd);
}

static std::string migrate_legacy_shell_dir() {
    std::string new_name = generate_unique_shell_dir_name();
    std::string new_path = std::string(SHELL_BASE_DIR) + "/" + new_name;
    int base_fd = shell_runtime_open_dir(AT_FDCWD, SHELL_BASE_DIR);
    int new_dir_fd =
            base_fd < 0 ? -1 : shell_runtime_open_or_create_dir(base_fd, new_name.c_str(), 0755);
    int err = errno;
    if (base_fd >= 0) {
        close(base_fd);
    }
    if (new_dir_fd < 0) {
        LOGW("create migrated shell dir failed with %d: %s", err, strerror(err));
        return LEGACY_SHELL_DIR;
    }

    copy_legacy_uids_best_effort(new_dir_fd);
    close(new_dir_fd);

    if (write_text_atomic(SHELL_DIR_MARKER, new_name + "\n") != 0) {
        LOGW("write migrated shell dir marker failed with %d: %s", errno, strerror(errno));
        return LEGACY_SHELL_DIR;
    }

    cleanup_legacy_shell_dir_best_effort();
    LOGI("migrated legacy shell dir to %s", new_path.c_str());
    return new_path;
}

static std::string resolve_shell_dir_path() {
    std::string marker;
    if (read_file_line(SHELL_DIR_MARKER, marker)) {
        std::string marker_trimmed = trim_copy(marker);
        size_t base_len = strlen(SHELL_BASE_DIR);
        if (marker_trimmed.rfind(SHELL_BASE_DIR, 0) == 0 && marker_trimmed.size() > base_len + 1 &&
            marker_trimmed[base_len] == '/') {
            std::string name = marker_trimmed.substr(base_len + 1);
            if (is_valid_shell_dir_name(name)) {
                if (name == "sui_shell") {
                    return migrate_legacy_shell_dir();
                }
                return marker_trimmed;
            }
        } else if (is_valid_shell_dir_name(marker_trimmed)) {
            if (marker_trimmed == "sui_shell") {
                return migrate_legacy_shell_dir();
            }
            return std::string(SHELL_BASE_DIR) + "/" + marker_trimmed;
        }
        LOGW("Invalid shell dir marker content: %s", marker_trimmed.c_str());
    }

    if (access(LEGACY_SHELL_DIR, F_OK) == 0) {
        return migrate_legacy_shell_dir();
    }

    std::string new_name = generate_unique_shell_dir_name();
    if (write_text_atomic(SHELL_DIR_MARKER, new_name + "\n") != 0) {
        LOGW("write randomized shell dir marker failed with %d: %s", errno, strerror(errno));
        return LEGACY_SHELL_DIR;
    }
    return std::string(SHELL_BASE_DIR) + "/" + new_name;
}

static bool build_module_path(char* buffer, size_t buffer_size, const char* root_path,
                              const char* suffix) {
    int written = snprintf(buffer, buffer_size, "%s%s", root_path, suffix);
    if (written < 0 || static_cast<size_t>(written) >= buffer_size) {
        errno = ENAMETOOLONG;
        return false;
    }
    return true;
}

/*
 * argv[1]: path of the module, such as /data/adb/modules/zygisk-sui
 */
static int sui_main(int argc, char** argv) {
    LOGI("Sui starter begin: %s", argv[1]);

    const char* foreground = getenv("SUI_FOREGROUND");
    if ((!foreground || strcmp(foreground, "1") != 0) && daemon(false, false) != 0) {
        PLOGE("daemon");
        return EXIT_FAILURE;
    }

    {
        int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
        if (fd >= 0) {
            const char value[] = "-1000";
            if (write_full(fd, value, sizeof(value) - 1) != 0) {
                LOGW("write /proc/self/oom_score_adj failed with %d: %s", errno, strerror(errno));
            }
            close(fd);
        } else {
            LOGW("open /proc/self/oom_score_adj failed with %d: %s", errno, strerror(errno));
        }
    }

    wait_for_zygote();

    if (access(SUI_DATA_DIR, F_OK) != 0) {
        mkdir(SUI_DATA_DIR, 0700);
    }
    chmod(SUI_DATA_DIR, 0700);
    chown(SUI_DATA_DIR, 0, 0);

    auto root_path = argv[1];

    char dex_path[PATH_MAX]{0};
    if (!build_module_path(dex_path, sizeof(dex_path), root_path, "/sui.dex")) {
        PLOGE("build_module_path %s/sui.dex", root_path);
        return EXIT_FAILURE;
    }

    // Resolve and persist shell workdir before forking so root/shell servers share
    // the same directory from the first startup tick.
    std::string shell_dir_path = resolve_shell_dir_path();

    pid_t pid = fork();
    if (pid < 0) {
        PLOGE("fork");
        return EXIT_FAILURE;
    }

    if (pid == 0) {
        // Child process -> Shell Server
        if (!init_selinux()) {
            LOGE("init_selinux failed");
            exit(EXIT_FAILURE);
        }
        log_context("initial");

        if (access(KSU_NO_ESCAPE_MARKER, F_OK) == 0) {
            LOGI("KernelSU no-escape protection requested for shell server");
            KsuNoEscapeResult result = ksu_disable_escape_to_root();
            if (result == KsuNoEscapeResult::Enabled) {
                LOGI("KernelSU no-escape protection enabled for shell server");
            } else if (result == KsuNoEscapeResult::Unsupported) {
                LOGW("KernelSU no-escape is unsupported; disabling the option and continuing");
                if (unlink(KSU_NO_ESCAPE_MARKER) != 0 && errno != ENOENT) {
                    LOGW("failed to disable KernelSU no-escape marker with %d: %s", errno,
                         strerror(errno));
                }
            } else {
                LOGW("KernelSU no-escape setup failed with %d: %s; continuing without protection",
                     errno, strerror(errno));
            }
        }

        // uid 2000 cannot read /data/adb/modules/zygisk-sui/sui.dex or .so libraries
        const char* shell_dir = shell_dir_path.c_str();
        std::string shell_dir_name;
        if (!shell_dir_name_of(shell_dir_path, shell_dir_name)) {
            LOGE("invalid shell runtime directory %s", shell_dir);
            exit(EXIT_FAILURE);
        }
        int shell_dir_fd = open_shell_runtime_dir(shell_dir_name.c_str());
        if (shell_dir_fd < 0) {
            exit(EXIT_FAILURE);
        }

        const char* metadata_names[] = {"system_ui", "settings"};
        for (const char* metadata_name : metadata_names) {
            char metadata_path[PATH_MAX];
            snprintf(metadata_path, PATH_MAX, "%s/%s", root_path, metadata_name);
            // FinaSU manages Sui from its own app and installs no SystemUI/Settings metadata;
            // the shell server only needs those files for the injected management UI.
            if (access(metadata_path, F_OK) != 0) {
                continue;
            }
            if (!prepare_shell_runtime_file(metadata_path, shell_dir_fd, metadata_name, 0644)) {
                exit(EXIT_FAILURE);
            }
        }

        char shell_dex_path[PATH_MAX];
        snprintf(shell_dex_path, PATH_MAX, "%s/sui.dex", shell_dir);
        if (!prepare_shell_runtime_file(dex_path, shell_dir_fd, "sui.dex", 0644)) {
            exit(EXIT_FAILURE);
        }

        char lib_path[PATH_MAX];
        snprintf(lib_path, PATH_MAX, "%s/librish.so", root_path);
        if (!prepare_shell_runtime_file(lib_path, shell_dir_fd, "librish.so", 0644)) {
            exit(EXIT_FAILURE);
        }
        close(shell_dir_fd);

        // Match adbd's supplementary groups before dropping root credentials.
        if (set_shell_supplementary_groups() != 0) {
            PLOGE("setgroups shell");
            exit(EXIT_FAILURE);
        }

        // Set GID to shell (2000)
        if (setresgid(2000, 2000, 2000) != 0) {
            PLOGE("setresgid 2000");
            exit(EXIT_FAILURE);
        }
        log_context("after setresgid");

        // Set UID to shell (2000)
        if (setresuid(2000, 2000, 2000) != 0) {
            PLOGE("setresuid 2000");
            exit(EXIT_FAILURE);
        }
        log_context("after setresuid");

        // Switch domains after dropping UID/GID while the current KSU domain can still perform
        // the credential changes above.
        LOGI("shell credentials ready; switching to shell domain and arming parent death signal");
        if (setcon("u:r:shell:s0") != 0) {
            PLOGE("setcon u:r:shell:s0");
            exit(EXIT_FAILURE);
        }

        // setresgid/setresuid clear PDEATHSIG when credentials change, so arm it only after all
        // credential and SELinux transitions are complete.
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) != 0) {
            PLOGE("prctl PR_SET_PDEATHSIG");
            exit(EXIT_FAILURE);
        }
        pid_t parent = getppid();
        if (parent == 1) {
            exit(EXIT_FAILURE);
        }

        app_process(shell_dex_path, shell_dir, "rikka.sui.server.Starter", "sui_shell", "--shell",
                    false);
        exit(EXIT_FAILURE);
    } else {
        // Parent process -> Root Server
        app_process(dex_path, root_path, "rikka.sui.server.Starter", "sui");
        exit(EXIT_FAILURE);
    }

    return EXIT_SUCCESS;
}
