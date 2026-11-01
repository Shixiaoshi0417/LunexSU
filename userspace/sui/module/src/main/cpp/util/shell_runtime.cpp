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
 * Copyright (c) 2026 LunexSU Contributors
 */

#include "shell_runtime.h"

#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr int TEMP_NAME_ATTEMPTS = 16;

bool valid_entry_name(const char* name) {
    return name != nullptr && name[0] != '\0' && strchr(name, '/') == nullptr &&
           strcmp(name, ".") != 0 && strcmp(name, "..") != 0;
}

void close_preserving_errno(int fd) {
    int saved = errno;
    close(fd);
    errno = saved;
}

std::string temp_name(const char* name, int attempt) {
    unsigned char random_bytes[8]{0};
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t r;
        do {
            r = read(fd, random_bytes, sizeof(random_bytes));
        } while (r < 0 && errno == EINTR);
        close(fd);
    }
    char suffix[48];
    snprintf(suffix, sizeof(suffix), "%02x%02x%02x%02x%02x%02x%02x%02x-%ld-%d", random_bytes[0],
             random_bytes[1], random_bytes[2], random_bytes[3], random_bytes[4], random_bytes[5],
             random_bytes[6], random_bytes[7], static_cast<long>(getpid()), attempt);
    return std::string(".") + name + ".tmp-" + suffix;
}

int copy_fd(int src_fd, int dst_fd) {
    char buffer[64 * 1024];
    for (;;) {
        ssize_t r = read(src_fd, buffer, sizeof(buffer));
        if (r < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        if (r == 0)
            return 0;
        const char* out = buffer;
        while (r > 0) {
            ssize_t w = write(dst_fd, out, static_cast<size_t>(r));
            if (w < 0) {
                if (errno == EINTR)
                    continue;
                return -1;
            }
            out += w;
            r -= w;
        }
    }
}

}  // namespace

int shell_runtime_open_dir(int parent_fd, const char* name) {
    if (name == nullptr || name[0] == '\0') {
        errno = EINVAL;
        return -1;
    }
    int fd = openat(parent_fd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0)
        return -1;
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        close_preserving_errno(fd);
        return -1;
    }
    if (!S_ISDIR(st.st_mode)) {
        close(fd);
        errno = ENOTDIR;
        return -1;
    }
    return fd;
}

int shell_runtime_open_or_create_dir(int parent_fd, const char* name, mode_t mode) {
    if (!valid_entry_name(name)) {
        errno = EINVAL;
        return -1;
    }
    // mkdirat never follows a link at `name`; an existing entry is validated by the open below.
    if (mkdirat(parent_fd, name, mode) != 0 && errno != EEXIST)
        return -1;
    return shell_runtime_open_dir(parent_fd, name);
}

int shell_runtime_open_regular(int parent_fd, const char* name, off_t max_size) {
    if (name == nullptr || name[0] == '\0') {
        errno = EINVAL;
        return -1;
    }
    // O_NONBLOCK keeps a FIFO planted by UID 2000 from stalling this root process.
    int fd = openat(parent_fd, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0)
        return -1;
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        close_preserving_errno(fd);
        return -1;
    }
    if (!S_ISREG(st.st_mode)) {
        close(fd);
        errno = EINVAL;
        return -1;
    }
    if (max_size >= 0 && st.st_size > max_size) {
        close(fd);
        errno = EFBIG;
        return -1;
    }
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags & ~O_NONBLOCK) != 0) {
        close_preserving_errno(fd);
        return -1;
    }
    return fd;
}

int shell_runtime_replace_file(int dir_fd, const char* name, int src_fd, mode_t mode, uid_t uid,
                               gid_t gid, shell_runtime_label_fn label) {
    if (!valid_entry_name(name)) {
        errno = EINVAL;
        return -1;
    }

    std::string tmp;
    int fd = -1;
    for (int attempt = 0; attempt < TEMP_NAME_ATTEMPTS && fd < 0; ++attempt) {
        tmp = temp_name(name, attempt);
        // O_EXCL never opens an existing entry, including a planted link, so retry on a new name.
        fd = openat(dir_fd, tmp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd < 0 && errno != EEXIST)
            return -1;
    }
    if (fd < 0)
        return -1;

    bool ok = copy_fd(src_fd, fd) == 0 && (label == nullptr || label(fd)) &&
              fchown(fd, uid, gid) == 0 && fchmod(fd, mode) == 0 && fsync(fd) == 0;
    if (!ok) {
        close_preserving_errno(fd);
        int saved = errno;
        unlinkat(dir_fd, tmp.c_str(), 0);
        errno = saved;
        return -1;
    }
    if (close(fd) != 0) {
        int saved = errno;
        unlinkat(dir_fd, tmp.c_str(), 0);
        errno = saved;
        return -1;
    }
    // rename replaces a link at `name` itself; it fails rather than descending into a directory.
    if (renameat(dir_fd, tmp.c_str(), dir_fd, name) != 0) {
        int saved = errno;
        unlinkat(dir_fd, tmp.c_str(), 0);
        errno = saved;
        return -1;
    }
    if (fsync(dir_fd) != 0 && errno != EINVAL && errno != EROFS)
        return -1;
    return 0;
}

int shell_runtime_remove_dir(int parent_fd, const char* name) {
    if (!valid_entry_name(name)) {
        errno = EINVAL;
        return -1;
    }
    int dir_fd = shell_runtime_open_dir(parent_fd, name);
    if (dir_fd < 0)
        return -1;
    int list_fd = dup(dir_fd);
    if (list_fd < 0) {
        close_preserving_errno(dir_fd);
        return -1;
    }
    DIR* dir = fdopendir(list_fd);
    if (dir == nullptr) {
        close_preserving_errno(list_fd);
        close_preserving_errno(dir_fd);
        return -1;
    }
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0)
            continue;
        // unlinkat without AT_REMOVEDIR removes links themselves and refuses directories.
        unlinkat(dir_fd, entry->d_name, 0);
    }
    closedir(dir);
    close(dir_fd);
    return unlinkat(parent_fd, name, AT_REMOVEDIR);
}
