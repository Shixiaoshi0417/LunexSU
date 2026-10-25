/* Execute production init.rc fops and input-work lifecycle with counted owners
 * and deterministic pthread interleavings. This is not a kernel execution.
 */
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>

#define __init
#define __exit
#define __user
#define pr_info(...) ((void)0)
#define smp_rmb() __atomic_thread_fence(__ATOMIC_ACQUIRE)
#define smp_wmb() __atomic_thread_fence(__ATOMIC_RELEASE)
#define READ_ONCE(x) __atomic_load_n(&(x), __ATOMIC_ACQUIRE)
#define DEFINE_MUTEX(n) pthread_mutex_t n = PTHREAD_MUTEX_INITIALIZER
#define DEFINE_SPINLOCK(n) DEFINE_MUTEX(n)
static pthread_mutex_t events = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static int gate_contended;
static _Thread_local int mutex_depth, gate_depth;
static void lock_events(void) { assert(!pthread_mutex_lock(&events)); }
static void unlock_events(void) { assert(!pthread_mutex_unlock(&events)); }
static void notify(void) { assert(!pthread_cond_broadcast(&changed)); }
static void wait_for(int *value)
{
    lock_events();
    while (!*value) assert(!pthread_cond_wait(&changed, &events));
    unlock_events();
}
static void set_event(int *value) { lock_events(); *value = 1; notify(); unlock_events(); }
static void lock_mutex(pthread_mutex_t *p) { assert(!pthread_mutex_lock(p)); mutex_depth++; }
static void unlock_mutex(pthread_mutex_t *p) { assert(mutex_depth-- > 0); assert(!pthread_mutex_unlock(p)); }
#define mutex_lock(p) lock_mutex(p)
#define mutex_unlock(p) unlock_mutex(p)
static void lock_gate(pthread_mutex_t *p)
{
    int ret = pthread_mutex_trylock(p);
    if (ret) {
        assert(ret == EBUSY);
        set_event(&gate_contended);
        assert(!pthread_mutex_lock(p));
    }
    gate_depth++;
}
static void unlock_gate(pthread_mutex_t *p) { assert(gate_depth-- > 0); assert(!pthread_mutex_unlock(p)); }
#define spin_lock_irqsave(p, flags) do { (flags) = 0; lock_gate(p); } while (0)
#define spin_unlock_irqrestore(p, flags) do { (void)(flags); unlock_gate(p); } while (0)

struct module { int refs; bool going; };
static struct module lunex, filesystem;
#define THIS_MODULE (&lunex)
static bool try_module_get(struct module *m)
{
    if (!m) return true;
    if (m->going) return false;
    __atomic_add_fetch(&m->refs, 1, __ATOMIC_SEQ_CST);
    return true;
}
static void module_put(struct module *m) { if (m) assert(__atomic_fetch_sub(&m->refs, 1, __ATOMIC_SEQ_CST) > 0); }
struct inode { int unused; };
struct file;
struct kiocb { struct file *ki_filp; };
struct iov_iter { char *buf; size_t remaining; };
struct file_operations {
    struct module *owner;
    ssize_t (*read)(struct file *, char *, size_t, loff_t *);
    ssize_t (*read_iter)(struct kiocb *, struct iov_iter *);
    int (*release)(struct inode *, struct file *);
    long (*unlocked_ioctl)(struct file *, unsigned int, unsigned long);
};
struct file { const struct file_operations *f_op; bool is_rc; };
static const struct file_operations *fops_get(const struct file_operations *ops)
{
    return ops && try_module_get(ops->owner) ? ops : NULL;
}
static void fops_put(const struct file_operations *ops) { if (ops) module_put(ops->owner); }
static void replace_fops(struct file *f, const struct file_operations *ops)
{
    assert(ops && (ops->owner != THIS_MODULE || READ_ONCE(lunex.refs) > 0));
    fops_put(f->f_op); f->f_op = ops;
}
static unsigned long copy_to_user(char *dst, const char *src, size_t size) { memcpy(dst, src, size); return 0; }
static size_t copy_to_iter(const char *src, size_t size, struct iov_iter *iter)
{
    if (size > iter->remaining) size = iter->remaining;
    memcpy(iter->buf, src, size); iter->buf += size; iter->remaining -= size;
    return size;
}
static const char KERNEL_SU_RC[] = "rc";
struct work_struct { void (*func)(struct work_struct *); bool initialized, pending, running; };
#include "runtime_state.inc"
static int loads, unhooks, release_calls, read_calls, iter_calls, ioctl_calls;
static int free_calls, expect_cancel, cancel_seen;
static int original_release_started, allow_original_release, release_returned, allow_final_put, hold_release;
static ssize_t read_result;
static bool is_init_rc(struct file *f) { return f->is_rc; }
static void load_module_rc_once(void) { assert(READ_ONCE(lunex.refs) > 0); loads++; }
static void stop_init_rc_hook(void) { unhooks++; }
static void kvfree(void *p) { if (expect_cancel) assert(cancel_seen); free(p); free_calls++; }

static void ksu_stop_input_hook_runtime(void);
static void init_work(struct work_struct *w, void (*fn)(struct work_struct *))
{
    assert(!w->pending && !w->running);
    *w = (struct work_struct){.func = fn, .initialized = true};
}
#define INIT_WORK(w, fn) init_work(w, fn)
static int schedules, pause_schedule, schedule_entered, allow_schedule;
static bool schedule_work(struct work_struct *w)
{
    assert(w->initialized && gate_depth == 1);
    lock_events();
    if (pause_schedule) {
        schedule_entered = 1; notify();
        while (!allow_schedule) assert(!pthread_cond_wait(&changed, &events));
    }
    bool queued = !w->pending;
    if (queued) { w->pending = true; schedules++; }
    unlock_events();
    return queued;
}
static int worker_started, worker_returned, hold_worker_return, allow_worker_return;
static void *run_work(void *arg)
{
    struct work_struct *w = arg;
    lock_events();
    assert(w->pending && !w->running);
    w->pending = false; w->running = true; worker_started = 1; notify();
    unlock_events();
    w->func(w);
    lock_events();
    worker_returned = 1; notify();
    while (hold_worker_return && !allow_worker_return) assert(!pthread_cond_wait(&changed, &events));
    w->running = false; notify();
    unlock_events();
    return NULL;
}
static bool cancel_work_sync(struct work_struct *w)
{
    assert(w->initialized && !gate_depth && !mutex_depth);
    assert(!input_hook_accepting);
    ksu_stop_input_hook_runtime(); /* A late producer must not enqueue. */
    lock_events();
    cancel_seen = 1; notify();
    bool was_pending = w->pending;
    w->pending = false;
    while (w->running) assert(!pthread_cond_wait(&changed, &events));
    unlock_events();
    return was_pending;
}
struct kprobe { int unused; };
static struct kprobe input_event_kp;
static bool kp_live, in_register;
static int register_error, enqueue_in_register, start_worker_in_register, unregistrations;
static int hold_unregister, unregister_started, allow_unregister;
static pthread_t early_worker;
static int register_kprobe(struct kprobe *kp)
{
    assert(stop_input_hook_work.initialized && mutex_depth == 1 && !gate_depth);
    in_register = true;
    if (!register_error) kp_live = true;
    if (enqueue_in_register) {
        ksu_stop_input_hook_runtime();
        if (start_worker_in_register) {
            assert(!pthread_create(&early_worker, NULL, run_work, &stop_input_hook_work));
            wait_for(&worker_started);
            assert(!unregistrations); /* Worker waits for registration to finish. */
        }
    }
    in_register = false;
    return register_error;
}
static void unregister_kprobe(struct kprobe *kp)
{
    assert(!in_register && kp_live && mutex_depth == 1 && !gate_depth);
    ksu_stop_input_hook_runtime(); /* Models a probe's last concurrent callback. */
    lock_events(); unregister_started = 1; notify();
    while (hold_unregister && !allow_unregister) assert(!pthread_cond_wait(&changed, &events));
    unlock_events();
    kp_live = false; unregistrations++;
}
struct pt_regs { int unused; };
static long ksu_sys_read(const struct pt_regs *regs) { return 0; }
static long ksu_sys_fstat(const struct pt_regs *regs) { return 0; }
static long (*orig_sys_read)(const struct pt_regs *), (*orig_sys_fstat)(const struct pt_regs *);
#define __NR_read 0
#define __NR_fstat 1
static void ksu_syscall_table_hook(int nr, long (*fn)(const struct pt_regs *), long (**old)(const struct pt_regs *))
{
    assert(stop_input_hook_work.initialized);
    ksu_stop_input_hook_runtime(); /* Earliest syscall producer; gate still shut. */
    assert(!schedules);
}
#include "runtime.inc"

static int original_release(struct inode *inode, struct file *f)
{
    assert(READ_ONCE(filesystem.refs) > 0 && READ_ONCE(lunex.refs) > 0);
    release_calls++;
    lock_events(); original_release_started = 1; notify();
    while (hold_release && !allow_original_release) assert(!pthread_cond_wait(&changed, &events));
    unlock_events();
    return -EIO;
}
static ssize_t original_read(struct file *f, char *buf, size_t size, loff_t *pos)
{
    assert(READ_ONCE(filesystem.refs) > 0 && READ_ONCE(lunex.refs) > 0); read_calls++; return read_result;
}
static ssize_t original_iter(struct kiocb *iocb, struct iov_iter *to)
{
    assert(READ_ONCE(filesystem.refs) > 0 && READ_ONCE(lunex.refs) > 0); iter_calls++; return read_result;
}
static long original_ioctl(struct file *f, unsigned int cmd, unsigned long arg)
{
    assert(READ_ONCE(filesystem.refs) > 0 && READ_ONCE(lunex.refs) > 0); ioctl_calls++; return 11;
}
static void close_file(struct file *f)
{
    const struct file_operations *ops = f->f_op;
    if (ops->release) {
        assert(ops->release(NULL, f) == -EIO);
        assert(!READ_ONCE(filesystem.refs) && READ_ONCE(lunex.refs) == 1);
        lock_events(); release_returned = 1; notify();
        while (hold_release && !allow_final_put) assert(!pthread_cond_wait(&changed, &events));
        unlock_events();
    }
    fops_put(f->f_op); f->f_op = NULL;
}
static void *close_thread(void *f) { close_file(f); return NULL; }
static void *install_thread(void *f) { ksu_install_rc_hook(f); return NULL; }
static void reset_proxy(void)
{
    assert(!lunex.refs && !filesystem.refs && !module_rc_buf);
    rc_hooked = false; orig_rc_fops = NULL;
    memset(&fops_proxy, 0, sizeof(fops_proxy));
    lunex.going = filesystem.going = false;
    loads = unhooks = release_calls = read_calls = iter_calls = ioctl_calls = 0;
    ksu_rc_pos = module_rc_pos = module_rc_len = 0;
    hold_release = original_release_started = allow_original_release = release_returned = allow_final_put = 0;
}
static void proxy_tests(void)
{
    struct file_operations ops = {.owner = &filesystem, .read = original_read, .read_iter = original_iter,
                                  .release = original_release, .unlocked_ioctl = original_ioctl};
    reset_proxy();
    struct file f = {.f_op = fops_get(&ops), .is_rc = false};
    ksu_install_rc_hook(&f);
    assert(f.f_op == &ops && !rc_hooked && filesystem.refs == 1 && !lunex.refs);
    f.is_rc = true; filesystem.going = true;
    ksu_install_rc_hook(&f); /* Failed extra original owner acquisition. */
    assert(f.f_op == &ops && !rc_hooked && filesystem.refs == 1 && !lunex.refs);
    filesystem.going = false; lunex.going = true;
    ksu_install_rc_hook(&f); /* MODULE_STATE_GOING: unwind extra original ref. */
    assert(f.f_op == &ops && !rc_hooked && filesystem.refs == 1 && !lunex.refs && !loads && !unhooks);
    lunex.going = false;
    ksu_install_rc_hook(&f);
    assert(f.f_op == &fops_proxy && rc_hooked && filesystem.refs == 1 && lunex.refs == 1);
    assert(orig_rc_fops == &ops && f.f_op->owner == THIS_MODULE && loads == 1 && unhooks == 1);
    struct file_operations published = fops_proxy;
    ksu_install_rc_hook(&f);
    assert(!memcmp(&published, &fops_proxy, sizeof(published)) && filesystem.refs == 1 && lunex.refs == 1);

    char buffer[32] = {0}; loff_t pos = 0;
    read_result = -EIO; assert(f.f_op->read(&f, buffer, sizeof(buffer), &pos) == -EIO);
    read_result = 0;
    assert(f.f_op->read(&f, buffer, sizeof(buffer), &pos) == (ssize_t)strlen(KERNEL_SU_RC));
    assert(!memcmp(buffer, KERNEL_SU_RC, strlen(KERNEL_SU_RC)));
    ksu_rc_pos = 0;
    struct kiocb iocb = {.ki_filp = &f}; struct iov_iter iter = {buffer, sizeof(buffer)};
    assert(f.f_op->read_iter(&iocb, &iter) == (ssize_t)strlen(KERNEL_SU_RC));
    assert(f.f_op->unlocked_ioctl(&f, 0, 0) == 11);
    assert(read_calls == 2 && iter_calls == 1 && ioctl_calls == 1);

    hold_release = 1;
    pthread_t closer; assert(!pthread_create(&closer, NULL, close_thread, &f));
    wait_for(&original_release_started);
    assert(filesystem.refs == 1 && lunex.refs == 1); /* Sleeping original release. */
    set_event(&allow_original_release);
    wait_for(&release_returned);
    assert(!filesystem.refs && lunex.refs == 1); /* Return window: VFS still owns proxy pin. */
    set_event(&allow_final_put); assert(!pthread_join(closer, NULL));
    assert(!filesystem.refs && !lunex.refs && release_calls == 1);

    reset_proxy();
    /* Concurrent first reads must not mutate a table that is already published. */
    ops.release = NULL;
    struct file a = {.f_op = fops_get(&ops), .is_rc = true};
    struct file b = {.f_op = fops_get(&ops), .is_rc = true};
    pthread_t first, second;
    assert(!pthread_create(&first, NULL, install_thread, &a));
    assert(!pthread_create(&second, NULL, install_thread, &b));
    assert(!pthread_join(first, NULL) && !pthread_join(second, NULL));
    assert((a.f_op == &fops_proxy) != (b.f_op == &fops_proxy));
    assert(filesystem.refs == 2 && lunex.refs == 1 && loads == 1 && unhooks == 1);
    struct file *proxied = a.f_op == &fops_proxy ? &a : &b;
    struct file *untouched = a.f_op == &fops_proxy ? &b : &a;
    fops_put(untouched->f_op);
    assert(proxied->f_op->release(NULL, proxied) == 0); /* Original has no release. */
    assert(!filesystem.refs && lunex.refs == 1);
    fops_put(proxied->f_op);

    reset_proxy();
    /* A built-in original has no owner, but the proxy must still pin LunexSU. */
    ops.owner = NULL; ops.read = NULL; ops.read_iter = NULL;
    f = (struct file){.f_op = fops_get(&ops), .is_rc = true};
    ksu_install_rc_hook(&f);
    assert(!f.f_op->read && !f.f_op->read_iter && lunex.refs == 1);
    assert(f.f_op->release(NULL, &f) == 0);
    fops_put(f.f_op); reset_proxy();
}

static int exit_started, exit_done;
static void *exit_thread(void *unused)
{
    set_event(&exit_started); ksu_ksud_exit(); set_event(&exit_done); return NULL;
}
static void *queue_thread(void *unused) { ksu_stop_input_hook_runtime(); return NULL; }
static void reset_work(void)
{
    assert(!input_hook_registered && !kp_live && !stop_input_hook_work.pending && !stop_input_hook_work.running);
    assert(!module_rc_buf);
    memset(&stop_input_hook_work, 0, sizeof(stop_input_hook_work));
    input_hook_accepting = false;
    schedules = pause_schedule = schedule_entered = allow_schedule = 0;
    worker_started = worker_returned = hold_worker_return = allow_worker_return = 0;
    register_error = enqueue_in_register = start_worker_in_register = unregistrations = 0;
    hold_unregister = unregister_started = allow_unregister = 0;
    exit_started = exit_done = cancel_seen = free_calls = gate_contended = 0;
    expect_cancel = 1;
}
static void setup_work(void)
{
    ksu_stop_input_hook_runtime(); assert(!schedules); /* Before INIT_WORK, including late-load mode. */
    ksu_ksud_init();
    module_rc_buf = malloc(4); assert(module_rc_buf);
}
static void assert_stopped(int unregisters)
{
    assert(!input_hook_accepting && !input_hook_registered && !kp_live);
    assert(cancel_seen && !stop_input_hook_work.pending && !stop_input_hook_work.running);
    assert(unregistrations == unregisters && !module_rc_buf && free_calls == 1);
    int before = schedules; ksu_stop_input_hook_runtime(); assert(schedules == before);
}
static void work_tests(void)
{
    reset_work(); setup_work(); ksu_ksud_exit(); assert_stopped(1);
    ksu_ksud_exit(); assert(unregistrations == 1 && free_calls == 1); /* Idempotent. */

    reset_work(); register_error = -ENOENT; setup_work();
    ksu_stop_input_hook_runtime(); assert(!schedules); ksu_ksud_exit(); assert_stopped(0);

    reset_work(); enqueue_in_register = start_worker_in_register = 1;
    setup_work(); assert(!pthread_join(early_worker, NULL));
    assert(unregistrations == 1 && schedules == 1);
    ksu_ksud_exit(); assert_stopped(1);

    reset_work(); register_error = -ENOENT; enqueue_in_register = 1;
    setup_work(); assert(schedules == 1 && stop_input_hook_work.pending);
    ksu_ksud_exit(); assert_stopped(0); /* Registration failure with an early external request. */

    reset_work(); setup_work();
    pthread_t producers[8];
    for (unsigned i = 0; i < 8; i++) assert(!pthread_create(&producers[i], NULL, queue_thread, NULL));
    for (unsigned i = 0; i < 8; i++) assert(!pthread_join(producers[i], NULL));
    assert(schedules == 1 && stop_input_hook_work.pending);
    ksu_ksud_exit(); assert_stopped(1); /* Pending work canceled without double unregister. */

    reset_work(); setup_work(); ksu_stop_input_hook_runtime();
    hold_unregister = hold_worker_return = 1;
    pthread_t worker, unload;
    assert(!pthread_create(&worker, NULL, run_work, &stop_input_hook_work));
    wait_for(&unregister_started);
    assert(!pthread_create(&unload, NULL, exit_thread, NULL)); wait_for(&exit_started);
    assert(!exit_done && module_rc_buf); /* Running unregister still owns registration state. */
    set_event(&allow_unregister); wait_for(&worker_returned); wait_for(&cancel_seen);
    assert(!exit_done && module_rc_buf && stop_input_hook_work.running);
    set_event(&allow_worker_return);
    assert(!pthread_join(worker, NULL) && !pthread_join(unload, NULL)); assert_stopped(1);

    reset_work(); setup_work(); pause_schedule = 1;
    pthread_t producer; assert(!pthread_create(&producer, NULL, queue_thread, NULL));
    wait_for(&schedule_entered);
    assert(!pthread_create(&unload, NULL, exit_thread, NULL)); wait_for(&gate_contended);
    assert(!cancel_seen && !exit_done); /* Exit cannot pass a producer paused inside enqueue. */
    set_event(&allow_schedule);
    assert(!pthread_join(producer, NULL) && !pthread_join(unload, NULL));
    assert(schedules == 1); assert_stopped(1);
}
int main(void)
{
    proxy_tests(); work_tests();
    puts("runtime lifetime: proxy dual owners/failures/concurrent install/release windows; work init/register/queue/cancel races passed");
}
