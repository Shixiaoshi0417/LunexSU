/* Production credential/wrapper/task_work ownership with counted host objects. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stddef.h>

#define GFP_KERNEL 0
#define KERNEL_VERSION(a,b,c) ((a) * 10000 + (b) * 100 + (c))
#define LINUX_VERSION_CODE KERNEL_VERSION(TEST_VERSION / 100, TEST_VERSION % 100, 0)
#define pr_warn(...) ((void)0)
#define pr_err(...) ((void)0)
#define pr_info(...) ((void)0)
#define __user
#define O_CLOEXEC 1
#define PF_EXITING 1
#define IS_ERR(p) ((uintptr_t)(p) >= (uintptr_t)-4095)
#define PTR_ERR(p) ((long)(intptr_t)(p))
#define ERR_PTR(n) ((void *)(intptr_t)(n))
#define container_of(p, t, m) ((t *)((char *)(p) - offsetof(t, m)))
static int allocations, fail_at, live, module_refs, going, module;
#define THIS_MODULE (&module)
static void *allocate(size_t size)
{
    if (++allocations == fail_at) return NULL;
    void *p = calloc(1, size);
    assert(p); live++; return p;
}
static void kfree(void *p) { if (p) { assert(live); live--; free(p); } }
#define kmalloc(s, flags) allocate(s)
#define kzalloc(s, flags) allocate(s)
#define kcalloc(n, s, flags) allocate((n) * (s))
static bool try_module_get(void *m) { if (going) return false; module_refs++; return true; }
static void module_put(void *m) { assert(module_refs > 0); module_refs--; }

typedef uint32_t u32;
typedef unsigned kgid_t;
#define KSU_MAX_GROUPS 32
struct root_profile { int uid; unsigned groups_count, groups[KSU_MAX_GROUPS]; };
struct group_info { int refs, ngroups; kgid_t gid[KSU_MAX_GROUPS]; };
struct cred { struct group_info *group_info; };
static struct group_info *groups_alloc(unsigned n)
{
    struct group_info *g = allocate(sizeof(*g));
    if (g) { g->refs = 1; g->ngroups = n; }
    return g;
}
static void put_group_info(struct group_info *g) { if (!--g->refs) kfree(g); }
static void set_groups(struct cred *c, struct group_info *g)
{
    put_group_info(c->group_info); g->refs++; c->group_info = g;
}
static int current_user_ns(void) { return 0; }
static kgid_t make_kgid(int ns, unsigned gid) { return gid; }
static bool gid_valid(kgid_t g) { return g != UINT32_MAX; }
static void groups_sort(struct group_info *g) {}
#include "groups.inc"

struct file;
struct inode;
struct dentry;
struct file_operations {
#include "wrapper_fields.inc"
    int (*open)(struct inode *, struct file *);
};
struct inode { unsigned i_mode; const struct file_operations *i_fop; };
struct dentry_operations { char *(*d_dname)(struct dentry *, char *, int); void (*d_release)(struct dentry *); };
struct dentry { void *d_fsdata; const struct dentry_operations *d_op; int refs; };
struct path { struct dentry *dentry; };
struct file { struct path f_path; const struct file_operations *f_op; void *private_data; int f_flags, refs;
              struct inode inode; };
struct task_struct { void *files; int flags, pid; };
static struct task_struct task;
#define current (&task)
struct inode_security_struct { unsigned sid; };
static struct inode_security_struct security;
static unsigned ksu_file_sid;
static struct cred *ksu_cred;
static const struct cred *current_cred(void) { return NULL; }
static const struct cred *override_creds(struct cred *c) { return NULL; }
static void revert_creds(const struct cred *c) {}
static struct inode_security_struct *selinux_inode(struct inode *i) { return &security; }
#define file_inode(f) (&(f)->inode)
static void path_get(struct path *p) { p->dentry->refs++; }
static void path_put(struct path *p)
{
    struct dentry *d = p->dentry;
    assert(d->refs > 0);
    if (!--d->refs) { if (d->d_op) d->d_op->d_release(d); kfree(d); }
}
static char *d_path(struct path *p, char *buffer, int buflen) { assert(p->dentry->refs); return buffer; }
static const struct file_operations *fops_get(const struct file_operations *ops)
{
    if (ops->owner && !try_module_get(ops->owner)) return NULL;
    return ops;
}
static void fops_put(const struct file_operations *ops) { if (ops && ops->owner) module_put(ops->owner); }
static void replace_fops(struct file *f, const struct file_operations *ops) { assert(ops); fops_put(f->f_op); f->f_op = ops; }
static void get_file(struct file *f) { f->refs++; }
static void fput(struct file *f)
{
    assert(f->refs > 0);
    if (--f->refs) return;
    if (f->f_op && f->f_op->release) ((int (*)(struct inode *, struct file *))f->f_op->release)(&f->inode, f);
    fops_put(f->f_op); path_put(&f->f_path); kfree(f);
}
static struct file_operations original_ops;
static struct file *new_file(void)
{
    struct file *f = allocate(sizeof(*f));
    if (!f) return ERR_PTR(-ENOMEM);
    f->f_path.dentry = allocate(sizeof(struct dentry));
    if (!f->f_path.dentry) { kfree(f); return ERR_PTR(-ENOMEM); }
    f->refs = f->f_path.dentry->refs = 1; f->f_op = &original_ops;
    return f;
}
static struct file *last_open;
static struct file *dentry_open(struct path *p, int flags, const struct cred *cred)
{
    last_open = new_file(); return last_open;
}
static void filp_close(struct file *f, void *files) { fput(f); }
static struct file *input, *installed;
static int unused_fd;
static struct file *fget(int fd) { get_file(input); return input; }
static int get_unused_fd_flags(int flags) { assert(!unused_fd); unused_fd = 1; return 7; }
static void put_unused_fd(int fd) { assert(unused_fd); unused_fd = 0; }
static void fd_install(int fd, struct file *f) { assert(unused_fd); unused_fd = 0; installed = f; }
static struct file *ksu_anon_inode_create_getfile_compat(const char *name, const struct file_operations *ops,
                                                       void *priv, int flags, void *context)
{
    struct file *f = new_file();
    if (IS_ERR(f)) return f;
    f->f_op = fops_get(ops); assert(f->f_op); f->private_data = priv;
    return f;
}
struct ksu_file_wrapper { struct file *orig; struct file_operations ops; };
static struct ksu_file_wrapper *ksu_create_file_wrapper(struct file *fp);
static void ksu_release_file_wrapper(struct ksu_file_wrapper *data);
static int ksu_wrapper_open(struct inode *, struct file *);
static int ksu_wrapper_release(struct inode *, struct file *);
static char *ksu_wrapper_d_dname(struct dentry *, char *, int);
static void ksu_wrapper_d_release(struct dentry *);
static const struct file_operations ksu_file_wrapper_inode_fops = {.owner = THIS_MODULE, .open = ksu_wrapper_open};
static const struct dentry_operations ksu_file_wrapper_d_ops = {ksu_wrapper_d_dname, ksu_wrapper_d_release};
#include "wrapper_callbacks.inc"
#include "wrapper.inc"

struct callback_head { void (*func)(struct callback_head *); };
static struct callback_head *pending;
static int add_fails, run_inline;
#define TWA_RESUME 1
static int task_work_add(struct task_struct *t, struct callback_head *cb, int mode)
{
    if (add_fails) return -ESRCH;
    if (run_inline) cb->func(cb);
    else pending = cb;
    return 0;
}
#include "task_work.inc"
static int allowlist_mutex, writes, tracks, installs, closes;
#define mutex_lock(p) ((void)p)
#define mutex_unlock(p) ((void)p)
static int write_allowlist_locked(void *p) { assert(module_refs); writes++; return -EIO; }
static void track_throne(bool prune) { assert(module_refs); tracks++; }
static int ksu_install_fd(void) { assert(module_refs); installs++; return 5; }
static int copy_to_user(int *out, int *fd, size_t len) { return 1; }
static void ksu_close_fd(int fd) { closes++; }
struct ksu_install_fd_tw { struct callback_head cb; int *outp; };
#include "task_callbacks.inc"

static void group_tests(void)
{
    struct root_profile p = {.groups_count = 1, .groups = {0}};
    struct cred c = {.group_info = groups_alloc(2)};
    struct group_info *old = c.group_info;
    old->refs++;
    setup_groups(&p, &c);
    assert(c.group_info != old && c.group_info->ngroups == 0 && c.group_info->refs == 1 && old->refs == 1);
    put_group_info(old);
    old = c.group_info; fail_at = allocations + 1;
    setup_groups(&p, &c);
    assert(c.group_info == old);
    fail_at = 0;
    p.groups_count = 2; p.groups[0] = 100; p.groups[1] = UINT32_MAX;
    setup_groups(&p, &c); assert(c.group_info == old);
    p.groups[1] = 101; setup_groups(&p, &c);
    assert(c.group_info->ngroups == 2 && c.group_info->gid[0] == 100 && c.group_info->gid[1] == 101);
    put_group_info(c.group_info); assert(!live);
}
static void wrapper_tests(void)
{
    input = new_file();
    assert(ksu_install_file_wrapper(0) == 7 && installed && module_refs == 2 && input->refs == 2);
    struct path opath = installed->f_path;
    path_get(&opath); /* O_PATH retains only the path; no fops_get. */
    fput(installed);
    assert(module_refs == 1 && input->refs == 1);
    char buffer[8];
    assert(opath.dentry->d_op->d_dname(opath.dentry, buffer, sizeof(buffer)) == buffer);
    path_put(&opath); assert(module_refs == 0);

    assert(ksu_install_file_wrapper(0) == 7);
    struct file *reopen = new_file();
    path_put(&reopen->f_path); reopen->f_path = installed->f_path; path_get(&reopen->f_path);
    reopen->f_op = fops_get(&ksu_file_wrapper_inode_fops);
    assert(!ksu_wrapper_open(&reopen->inode, reopen));
    assert(last_open->refs == 1); /* Regression: used to retain two refs. */
    fput(reopen); fput(installed); fput(input);
    assert(!live && !module_refs && !unused_fd);

    /* Fail every allocation during installation and verify total ownership. */
    unsigned total = 0;
    for (unsigned fault = 0; !fault || fault <= total; fault++) {
        fail_at = 0; input = new_file(); int before = live;
        allocations = 0; fail_at = fault;
        int ret = ksu_install_file_wrapper(0);
        if (!fault) { assert(ret == 7); total = allocations; fput(installed); }
        else assert(ret == -ENOMEM);
        assert(live == before && input->refs == 1 && !module_refs && !unused_fd);
        fput(input); assert(!live);
    }
    fail_at = 0;
}
static void task_tests(void)
{
    void (*callbacks[])(struct callback_head *) = {do_persistent_allow_list, ksu_track_throne_tw_func, ksu_install_fd_tw_func};
    for (unsigned i = 0; i < 3; i++) {
        for (int inline_run = 0; inline_run < 2; inline_run++) {
            struct callback_head *cb = allocate(sizeof(struct ksu_install_fd_tw)); cb->func = callbacks[i];
            run_inline = inline_run;
            assert(!ksu_task_work_add(current, cb));
            if (!inline_run) { assert(module_refs == 1); pending->func(pending); }
            assert(!module_refs && !live);
        }
        struct callback_head *cb = allocate(sizeof(struct ksu_install_fd_tw)); cb->func = callbacks[i];
        add_fails = 1; assert(ksu_task_work_add(current, cb) == -ESRCH && !module_refs);
        add_fails = 0; going = 1; assert(ksu_task_work_add(current, cb) == -ENODEV && !module_refs);
        going = 0; kfree(cb);
    }
    assert(writes == 2 && tracks == 2 && installs == 2 && closes == 2);
    current->flags = PF_EXITING;
    for (unsigned i = 1; i < 3; i++) {
        struct callback_head *cb = allocate(sizeof(struct ksu_install_fd_tw)); cb->func = callbacks[i];
        assert(!ksu_task_work_add(current, cb));
    }
    assert(tracks == 2 && installs == 2 && !module_refs && !live);
}
int main(void)
{
    group_tests(); wrapper_tests(); task_tests();
    printf("ownership %d: groups, reopen refs, O_PATH dentry, queued/immediate/failed/exiting task_work passed\n", TEST_VERSION);
}
