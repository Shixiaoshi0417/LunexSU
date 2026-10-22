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

#ifndef _SHELL_RUNTIME_H
#define _SHELL_RUNTIME_H

#include <sys/types.h>

// UID 2000 owns the shell runtime directory (and /data/local/tmp), so root must never resolve a
// path inside it. These helpers only work on directory descriptors, never follow a link and never
// open an existing entry for writing. They do not log; callers report errno.

using shell_runtime_label_fn = bool (*)(int fd);

// Opens an existing directory entry of `parent_fd` (or AT_FDCWD) without following a link.
int shell_runtime_open_dir(int parent_fd, const char* name);

// Same, but creates the directory with `mode` first when it is missing.
int shell_runtime_open_or_create_dir(int parent_fd, const char* name, mode_t mode);

// Opens a regular file for reading without following a link or blocking on a FIFO. A negative
// `max_size` accepts any size.
int shell_runtime_open_regular(int parent_fd, const char* name, off_t max_size);

// Copies `src_fd` to a fresh exclusive temporary entry, applies `label` (may be null), owner and
// mode through the descriptor, syncs it and renames it over `name`. An existing link at `name` is
// replaced, never followed.
int shell_runtime_replace_file(int dir_fd, const char* name, int src_fd, mode_t mode, uid_t uid,
                               gid_t gid, shell_runtime_label_fn label);

// Unlinks the non-directory entries of directory `name` under `parent_fd`, then removes it. A
// link at `name` is left alone and reported as an error.
int shell_runtime_remove_dir(int parent_fd, const char* name);

#endif  // _SHELL_RUNTIME_H
