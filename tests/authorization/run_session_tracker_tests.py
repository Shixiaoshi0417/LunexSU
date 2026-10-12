"""Compile the production lifecycle tracker with mocked kernel plumbing.

This exercises registration, seeding, task membership, forks, exits and fail-closed
errors; it does not substitute for real GKI tracepoints/device verification.
"""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
stub = r'''
#ifndef MOCK_KERNEL_H
#define MOCK_KERNEL_H
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
typedef uint64_t u64;
typedef struct { int counter; } atomic_t;
#define ATOMIC_INIT(i) { (i) }
static inline int atomic_read(const atomic_t *v) { return v->counter; }
static inline void atomic_inc(atomic_t *v) { v->counter++; }
static inline void atomic_dec(atomic_t *v) { v->counter--; }
static inline void atomic_set(atomic_t *v, int i) { v->counter = i; }
typedef unsigned uid_t;
#define __init
#define __exit
#define GFP_ATOMIC 0
#define PF_EXITING 1
#define READ_ONCE(x) (x)
#define WRITE_ONCE(x,y) ((x)=(y))
#define pr_warn(...) fprintf(stderr, __VA_ARGS__)
struct cred { struct { unsigned val; } uid; };
struct task_struct { struct cred cred; void *mm; unsigned long flags; struct task_struct *next; };
static struct task_struct *current, *seed;
#define __task_cred(t) (&(t)->cred)
static inline void rcu_read_lock(void) {}
static inline void rcu_read_unlock(void) {}
#define DEFINE_SPINLOCK(name) int name
#define spin_lock_irqsave(lock, flags) do { (void)(lock); (flags)=0; } while (0)
#define spin_unlock_irqrestore(lock, flags) do { (void)(lock); (void)(flags); } while (0)
static int tasklist_lock;
static inline void read_lock(int *x) { (void)x; }
static inline void read_unlock(int *x) { (void)x; }
#define for_each_process_thread(p,t) for ((p)=seed;(p);(p)=NULL) for ((t)=(p);(t);(t)=(t)->next)
struct hlist_node { struct hlist_node *next; struct hlist_node **prev; };
struct hlist_head { struct hlist_node *first; };
#define DEFINE_HASHTABLE(name,bits) struct hlist_head name[1]
#define object(n, type, field) ((type *)((char *)(n)-__builtin_offsetof(type,field)))
#define hash_add(table,node,key) do { struct hlist_node *n=(node); (void)(key); n->next=(table)[0].first; n->prev=&(table)[0].first; if(n->next) n->next->prev=&n->next; (table)[0].first=n; } while(0)
#define hash_del(n) do { if((n)->next) (n)->next->prev=(n)->prev; *(n)->prev=(n)->next; } while(0)
#define hash_for_each_possible(table,entry,field,key) \
    for(struct hlist_node *n=(table)[0].first; n && ((entry)=object(n,__typeof__(*(entry)),field),1);n=n->next)
#define hash_for_each_safe(table,bucket,tmp,entry,field) \
    for((bucket)=0;(bucket)<1;++(bucket)) \
    for(struct hlist_node *n=(table)[0].first; n && ((tmp)=n->next,(entry)=object(n,__typeof__(*(entry)),field),1);n=(tmp))
static bool allocation_failure;
static inline void *kzalloc(size_t size, int flags) { (void)flags; return allocation_failure?NULL:calloc(1,size); }
#define kfree free
static inline bool is_appuid(uid_t uid) { return uid%100000>=10000 && uid%100000<=19999; }
struct tracepoint { const char *name; };
static struct tracepoint mock_fork_tp={"sched_process_fork"}, mock_exit_tp={"sched_process_exit"};
static bool missing_hooks, registration_failure;
static int registered;
static inline unsigned long find_kernel_symbol_exact(const char *name) {
    if(missing_hooks) return 0;
    if(!strcmp(name,"__tracepoint_sched_process_fork")) return (unsigned long)&mock_fork_tp;
    if(!strcmp(name,"__tracepoint_sched_process_exit")) return (unsigned long)&mock_exit_tp;
    return 0;
}
static inline int tracepoint_probe_register(struct tracepoint *tp,void *fn,void *arg) {
    (void)tp;(void)fn;(void)arg;if(registration_failure && registered) return -EINVAL; ++registered; return 0;
}
static inline void tracepoint_probe_unregister(struct tracepoint *tp,void *fn,void *arg) {
    (void)tp;(void)fn;(void)arg; --registered;
}
static inline void tracepoint_synchronize_unregister(void) {}
static inline void check_trace_callback_type_sched_process_fork(void (*fn)(void *,struct task_struct *,struct task_struct *)) { (void)fn; }
#ifdef KSU_SCHED_EXIT_GROUP_DEAD
static inline void check_trace_callback_type_sched_process_exit(void (*fn)(void *,struct task_struct *,bool)) { (void)fn; }
#else
static inline void check_trace_callback_type_sched_process_exit(void (*fn)(void *,struct task_struct *)) { (void)fn; }
#endif
#endif
'''
test = r'''
#include "kernel/policy/app_session.c"
static void exit_task(struct task_struct *t) {
    t->flags=PF_EXITING;
#ifdef KSU_SCHED_EXIT_GROUP_DEAD
    ksu_sched_exit_probe(NULL,t,true);
#else
    ksu_sched_exit_probe(NULL,t);
#endif
}
int main(void) {
    struct task_struct anchor={.cred.uid.val=10387,.mm=(void *)1};
    struct task_struct child={.cred.uid.val=10387,.mm=(void *)1};
    struct task_struct root_child={.cred.uid.val=10387,.mm=(void *)1};
    struct task_struct next={.cred.uid.val=10387,.mm=(void *)1};
    u64 epoch, pending;
    seed=&anchor; current=&anchor;
    ksu_app_session_init(); assert(registered==2);
    assert(!ksu_app_session_bind(10387,&epoch));
    assert(ksu_app_session_available(10387,epoch,true));
    ksu_sched_fork_probe(NULL,&anchor,&child); current=&child;
    assert(ksu_app_session_available(10387,epoch,true));
    ksu_app_session_leave_current(); /* successful su excludes this task */
    ksu_sched_fork_probe(NULL,&child,&root_child); current=&root_child;
    assert(!ksu_app_session_available(10387,epoch,true)); /* Root descendants excluded */
    current=&anchor;
    assert(ksu_app_session_available(10387,epoch,true));
    exit_task(&anchor);
    assert(!ksu_app_session_available(10387,epoch,false));
    current=&next; ksu_app_session_update_current();
    assert(!ksu_app_session_available(10387,epoch,true)); /* no polling needed before restart */
    assert(!ksu_app_session_bind(10387,&epoch));
    assert(ksu_app_session_available(10387,epoch,true));
    exit_task(&next);
    assert(!ksu_app_session_bind(10387,&pending));
    assert(ksu_app_session_available(10387,pending,false));
    assert(!ksu_app_session_available(10387,pending,true));
    next.flags=0; current=&next; ksu_app_session_update_current();
    assert(ksu_app_session_available(10387,pending,true));
    assert(!ksu_app_session_bind(1010387,&epoch)); /* different Android user */
    assert(!ksu_app_session_available(1010387,epoch,true));
    allocation_failure=true; current=&child; ksu_app_session_update_current();
    assert(!ksu_app_session_available(10387,pending,false));
    assert(ksu_app_session_bind(10387,&epoch)==-EOPNOTSUPP);
    ksu_app_session_exit(); assert(registered==0);
    assert(!app_tasks[0].first && !uid_runs[0].first);
    allocation_failure=failed=false; seed=NULL; missing_hooks=true;
    ksu_app_session_init(); assert(ksu_app_session_bind(10387,&epoch)==-EOPNOTSUPP);
    ksu_app_session_exit(); assert(registered==0);
    missing_hooks=false; registration_failure=true;
    ksu_app_session_init(); assert(ksu_app_session_bind(10387,&epoch)==-EOPNOTSUPP);
    ksu_app_session_exit(); assert(registered==0);
    puts("PASS: production tracker seeding, forks, Root exclusion, last exit, restart, pregrant, multi-user and hook/allocation failure");
}
'''
with tempfile.TemporaryDirectory(prefix="lunexsu-session-") as tmp:
    out = Path(tmp)
    for header in ("linux/atomic.h", "linux/cred.h", "linux/hashtable.h", "linux/init.h",
                   "linux/sched/signal.h", "linux/slab.h", "linux/spinlock.h", "linux/tracepoint.h",
                   "trace/events/sched.h", "infra/symbol_resolver.h", "policy/allowlist.h", "klog.h"):
        path = out / header
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#include "mock_kernel.h"\n')
    (out / "mock_kernel.h").write_text(stub)
    (out / "test.c").write_text(test)
    for group_dead in (False, True):
        exe = out / f"tracker-{group_dead}"
        subprocess.run([os.environ.get("CC", "gcc"), "-std=gnu11", "-Wall", "-Wextra", "-Werror",
                        "-Wno-unused-parameter", *(["-DKSU_SCHED_EXIT_GROUP_DEAD"] if group_dead else []),
                        "-I", str(out), "-I", str(root), "-I", str(root / "kernel"),
                        str(out / "test.c"), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)
