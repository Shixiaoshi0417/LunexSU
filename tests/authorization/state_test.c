/* SPDX-License-Identifier: GPL-2.0 */
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <pthread.h>
#include "kernel/policy/authorization_state.h"
#include "uapi/supercall.h"

static struct ksu_authorization_state shared;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static unsigned successes;

static void *request(void *unused)
{
    (void)unused;
    for (int i = 0; i < 1000; ++i) {
        pthread_mutex_lock(&lock);
        bool admitted = ksu_auth_reserve(&shared, 10, true);
        pthread_mutex_unlock(&lock);
        if (!admitted)
            continue;
        pthread_mutex_lock(&lock);
        ++successes;
        ksu_auth_finish(&shared, true);
        pthread_mutex_unlock(&lock);
    }
    return NULL;
}

int main(void)
{
    struct ksu_authorization_state state = {0};
    assert(ksu_auth_available(&state, UINT64_MAX)); /* permanent legacy */
    assert(ksu_auth_grant(&state, KSU_AUTH_ONCE, 0, 10));
    assert(!ksu_auth_reserve(&state, 10, false)); /* ioctl cannot bypass su */
    assert(ksu_auth_available(&state, 10));
    assert(ksu_auth_reserve(&state, 10, true));
    assert(!ksu_auth_reserve(&state, 10, true));
    ksu_auth_finish(&state, false); /* failed prepare/exec refunds */
    assert(ksu_auth_reserve(&state, 10, true));
    ksu_auth_finish(&state, true);
    assert(!ksu_auth_available(&state, 10));
    ksu_auth_finish(&state, false); /* duplicate completion cannot refund */
    assert(!ksu_auth_available(&state, 10));
    assert(ksu_auth_grant(&state, KSU_AUTH_ONCE, 0, 10));
    ksu_auth_invalidate(&state); /* reboot or uninstall */
    assert(state.mode == KSU_AUTH_ONCE && !ksu_auth_available(&state, 0));
    assert(ksu_auth_grant(&state, KSU_AUTH_ONCE, 0, 10));
    assert(ksu_auth_reserve(&state, 10, true));
    ksu_auth_invalidate(&state); /* revoke while a request is reserved */
    ksu_auth_finish(&state, false);
    assert(!ksu_auth_available(&state, 10)); /* no refund after revoke */

    const uint64_t start = 1000000000;
    assert(ksu_auth_grant(&state, KSU_AUTH_TIMED, 300, start));
    for (int i = 0; i < 100; ++i) {
        assert(ksu_auth_reserve(&state, start + i, true));
        ksu_auth_finish(&state, true);
    }
    assert(ksu_auth_available(&state, start + 300000000000ULL - 1));
    assert(!ksu_auth_available(&state, start + 300000000000ULL));
    assert(!ksu_auth_available(&state, start + 300000000001ULL));
    ksu_auth_finish(&state, false);
    assert(!ksu_auth_available(&state, start + 300000000001ULL));
    assert(state.mode == KSU_AUTH_TIMED); /* expiry never becomes Permanent */
    assert(ksu_auth_grant(&state, KSU_AUTH_TIMED, 900, start));
    ksu_auth_invalidate(&state);
    assert(state.mode == KSU_AUTH_TIMED && !ksu_auth_available(&state, 0));
    assert(!ksu_auth_grant(&state, KSU_AUTH_TIMED, 0, start));
    assert(!ksu_auth_grant(&state, KSU_AUTH_TIMED, KSU_AUTH_MAX_SECONDS + 1, start));
    assert(!ksu_auth_grant(&state, KSU_AUTH_TIMED, 300, UINT64_MAX - 1));
    assert(!ksu_auth_grant(&state, 99, 300, start));
    assert(state.mode == KSU_AUTH_TIMED && !ksu_auth_available(&state, 0));
    state.mode = 99;
    assert(!ksu_auth_available(&state, 0));
    assert(ksu_auth_grant(&state, KSU_AUTH_PERMANENT, 0, start));
    ksu_auth_invalidate(&state);
    assert(ksu_auth_available(&state, UINT64_MAX));

    for (int round = 0; round < 20; ++round) {
        pthread_t workers[64];
        successes = 0;
        assert(ksu_auth_grant(&shared, KSU_AUTH_ONCE, 0, 10));
        for (int i = 0; i < 64; ++i) assert(!pthread_create(&workers[i], NULL, request, NULL));
        for (int i = 0; i < 64; ++i) assert(!pthread_join(workers[i], NULL));
        assert(successes == 1);
    }
    /* The upstream profile ABI stays unchanged. New ioctls carry their size. */
    _Static_assert(sizeof(struct app_profile) == 784, "v4 ABI changed");
    _Static_assert(sizeof(struct ksu_auth_profile_cmd) == 824, "authorization ABI");
    assert(_IOC_SIZE(KSU_IOCTL_GET_AUTH_PROFILE) == sizeof(struct ksu_auth_profile_cmd));
    puts("PASS: once reservation/refund, concurrency (1.28M attempts), timed boundary/renewal/reboot, validation and UAPI ABI");
    return 0;
}
