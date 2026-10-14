/* SPDX-License-Identifier: GPL-2.0 */
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <pthread.h>
#include "kernel/policy/authorization_session.h"

static struct ksu_app_run shared;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t token;

static void *requests(void *unused)
{
    (void)unused;
    for (int i = 0; i < 1000; ++i) {
        pthread_mutex_lock(&lock);
        assert(ksu_app_run_enter(&shared)); /* fork/thread of a live App */
        assert(ksu_app_run_available(&shared, token, true));
        ksu_app_run_leave(&shared); /* exit or successful Root admission */
        assert(ksu_app_run_available(&shared, token, true));
        pthread_mutex_unlock(&lock);
    }
    return NULL;
}

int main(void)
{
    struct ksu_app_run run = {0}, other_user = {0};
    uint64_t grant = ksu_app_run_bind(&run);
    assert(grant == 1);
    assert(ksu_app_run_available(&run, grant, false)); /* pending pregrant */
    assert(!ksu_app_run_available(&run, grant, true)); /* no App caller yet */
    assert(ksu_app_run_enter(&run)); /* cold start */
    assert(ksu_app_run_available(&run, grant, true));
    assert(ksu_app_run_bind(&run) == grant); /* grant to already-running App */
    assert(ksu_app_run_enter(&run)); /* another process/thread */
    ksu_app_run_leave(&run);
    assert(ksu_app_run_available(&run, grant, true)); /* leader exit is not last-thread exit */
    ksu_app_run_leave(&run);
    assert(!ksu_app_run_available(&run, grant, false)); /* last App task ends authorization */
    assert(!ksu_app_run_available(&run, grant, true));
    assert(ksu_app_run_enter(&run)); /* restart before Manager polls */
    assert(run.epoch == 2 && !ksu_app_run_available(&run, grant, true));
    grant = ksu_app_run_bind(&run); /* explicit regrant, never automatic rearm */
    assert(ksu_app_run_available(&run, grant, true));
    assert(!ksu_app_run_available(&run, 0, true));
    assert(!ksu_app_run_available(&other_user, grant, true)); /* full UID separation */
    run.live = UINT32_MAX;
    assert(!ksu_app_run_enter(&run));
    run.live = 0;
    run.epoch = UINT64_MAX;
    assert(!ksu_app_run_bind(&run) && !ksu_app_run_enter(&run));

    pthread_t workers[32];
    assert(ksu_app_run_enter(&shared)); /* ordinary App anchor remains alive */
    token = ksu_app_run_bind(&shared);
    for (int i = 0; i < 32; ++i) assert(!pthread_create(&workers[i], NULL, requests, NULL));
    for (int i = 0; i < 32; ++i) assert(!pthread_join(workers[i], NULL));
    assert(shared.live == 1);
    ksu_app_run_leave(&shared);
    assert(!ksu_app_run_available(&shared, token, true));
    puts("PASS: pregrant/cold start, multi-process/thread exit, repeated/concurrent su, restart epochs, overflow and UID separation");
    return 0;
}
