"""Compile the production root-path helper for both GKI API signatures.

Kernel task/path plumbing is mocked; real openat calls use two independent
filesystem views. This checks root selection and lifetime, not kernel mount
traversal, which still needs a matching GKI Core on a device.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
stub = r'''
#ifndef MOCK_KERNEL_H
#define MOCK_KERNEL_H
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>
typedef unsigned short umode_t;
struct dentry { int fd; };
struct vfsmount { int unused; };
struct path { struct dentry *dentry; struct vfsmount *mnt; };
struct fs_struct { struct path root; };
struct task_struct { struct fs_struct *fs; };
struct file { int fd; };
static struct task_struct task;
static int init_present = 1, locks, refs, paths;
#define PIDTYPE_PID 0
#define KERNEL_VERSION(a,b,c) (((a)<<16)+((b)<<8)+(c))
static inline void rcu_read_lock(void) {}
static inline void rcu_read_unlock(void) {}
static inline void *find_vpid(int pid) { assert(pid == 1); return &task; }
static inline struct task_struct *get_pid_task(void *pid, int type) {
    assert(pid == &task && type == 0);
    if (!init_present) return NULL;
    refs++; return &task;
}
static inline void put_task_struct(struct task_struct *t) { assert(t == &task); refs--; }
static inline void task_lock(struct task_struct *t) { assert(t == &task); locks++; }
static inline void task_unlock(struct task_struct *t) { assert(t == &task); locks--; }
static inline void get_fs_root(struct fs_struct *fs, struct path *p) { assert(locks == 1); *p = fs->root; paths++; }
static inline void path_put(struct path *p) { assert(p->dentry); paths--; }
static inline struct file *open_test_root(const struct path *p, const char *name, int flags, umode_t mode) {
    // Kernel LOOKUP_ROOT resolves absolute names relative to the supplied root.
    while (*name == '/') name++;
    int fd = openat(p->dentry->fd, name, flags, mode);
    if (fd < 0) return (struct file *)(intptr_t)-errno;
    struct file *file = malloc(sizeof(*file)); assert(file); file->fd = fd; return file;
}
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5,14,0)
static inline struct file *file_open_root(const struct path *p, const char *name, int flags, umode_t mode) {
    return open_test_root(p, name, flags, mode);
}
#else
static inline struct file *file_open_root(struct dentry *dentry, struct vfsmount *mnt, const char *name, int flags, umode_t mode) {
    struct path p = {dentry, mnt}; return open_test_root(&p, name, flags, mode);
}
#endif
#endif
'''
test = r'''
#include "kernel/policy/authorization_path.h"
int main(int argc, char **argv) {
    assert(argc == 3);
    struct dentry init_dir = {open(argv[1], O_PATH | O_DIRECTORY)};
    struct vfsmount mount = {0};
    struct fs_struct fs = {{&init_dir, &mount}};
    struct path trusted;
    assert(init_dir.fd >= 0 && chdir(argv[2]) == 0);
    assert(open("data/user/0/bin.mt.plus", O_PATH | O_DIRECTORY) < 0 && errno == ENOENT);
    task.fs = &fs;
    assert(ksu_auth_init_root(&trusted) == 0);
    assert(trusted.dentry == &init_dir && refs == 0 && locks == 0 && paths == 1);
    struct file *file = ksu_auth_open_root(&trusted, "/data/user/0/bin.mt.plus", O_PATH | O_DIRECTORY | O_NOFOLLOW, 0);
    assert((intptr_t)file > 0); close(file->fd); free(file);
    file = ksu_auth_open_root(&trusted, "/data/user/10/bin.mt.plus", O_PATH | O_DIRECTORY, 0);
    assert((intptr_t)file > 0); close(file->fd); free(file);
    file = ksu_auth_open_root(&trusted, "/data/user/0/missing", O_PATH | O_DIRECTORY, 0);
    assert((intptr_t)file == -ENOENT);
    file = ksu_auth_open_root(&trusted, "/data/adb/ksu/.allowlist.pending", O_WRONLY | O_CREAT | O_NOFOLLOW, 0600);
    assert((intptr_t)file > 0);
    struct stat st; assert(fstat(file->fd, &st) == 0 && (st.st_mode & 0777) == 0600);
    close(file->fd); free(file);
    path_put(&trusted);
    task.fs = NULL; assert(ksu_auth_init_root(&trusted) == -ESRCH);
    init_present = 0; assert(ksu_auth_init_root(&trusted) == -ESRCH);
    assert(refs == 0 && locks == 0 && paths == 0);
    close(init_dir.fd);
    puts("PASS: production path helper selects init view, supports multi-user and creation, releases references, fails closed");
}
'''
with tempfile.TemporaryDirectory(prefix="lunexsu-path-") as tmp:
    out = Path(tmp)
    for header in ("fs.h", "fs_struct.h", "pid.h", "sched/task.h", "version.h", "rcupdate.h"):
        path = out / "linux" / header
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#include "mock_kernel.h"\n')
    (out / "mock_kernel.h").write_text(stub)
    (out / "path_test.c").write_text(test)
    for name in ("data/user/0/bin.mt.plus", "data/user/10/bin.mt.plus", "data/adb/ksu"):
        (out / "init" / name).mkdir(parents=True, exist_ok=True)
    (out / "caller").mkdir()
    for version in ((5, 10, 0), (5, 14, 0), (5, 15, 0)):
        code = (version[0] << 16) + (version[1] << 8) + version[2]
        exe = out / f"path_{version[0]}_{version[1]}"
        subprocess.run([os.environ.get("CC", "gcc"), "-std=c11", "-Wall", "-Wextra", "-Werror",
                        f"-DLINUX_VERSION_CODE={code}", "-I", str(out), "-I", str(root),
                        str(out / "path_test.c"), "-o", str(exe)], check=True)
        subprocess.run([str(exe), str(out / "init"), str(out / "caller")], check=True)
