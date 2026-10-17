#include <cassert>
#include <cstddef>
#include <cstdio>
#include <iterator>
#include "uapi/supercall.h"

// Keep LunexSU's existing authorization and superkey wire contracts intact.
static_assert(KERNEL_SU_UAPI_VERSION == 8);
static_assert(KSU_APP_PROFILE_VER == 4);
static_assert(sizeof(app_profile) == 784);
static_assert(sizeof(ksu_auth_profile_cmd) == 824);
static_assert(KSU_IOCTL_GET_AUTH_PROFILE == 0xc3384b16U);
static_assert(KSU_IOCTL_SET_AUTH_PROFILE == 0x43384b17U);
static_assert(KSU_IOCTL_SUPERKEY_SALT == 0x80104b96U);
static_assert(KSU_IOCTL_SUPERKEY_AUTH == 0x40204b97U);
static_assert(KSU_INSTALL_MAGIC1 == 0xDEADBEEF);
static_assert(KSU_INSTALL_MAGIC2 == 0xCAFEBABE);

// The upstream lookup shares NR 22, not the complete authorization ioctl.
static_assert(sizeof(ksu_lookup_symbols_cmd) == 24);
static_assert(offsetof(ksu_lookup_symbols_cmd, names) == 0);
static_assert(offsetof(ksu_lookup_symbols_cmd, addrs) == 8);
static_assert(offsetof(ksu_lookup_symbols_cmd, count) == 16);
static_assert(offsetof(ksu_lookup_symbols_cmd, flags) == 20);
static_assert(KSU_IOCTL_LOOKUP_SYMBOLS == 0x40184b16U);
static_assert(KSU_IOCTL_LOOKUP_SYMBOLS != KSU_IOCTL_GET_AUTH_PROFILE);
static_assert(KSU_LOOKUP_SYMBOLS_MAX == 1024);
static_assert(KSU_GET_INFO_FLAG_PREINIT == (1U << 5));

int main() {
    const __u32 commands[] = {
        KSU_IOCTL_GRANT_ROOT, KSU_IOCTL_GET_INFO, KSU_IOCTL_GET_INFO_LEGACY,
        KSU_IOCTL_REPORT_EVENT, KSU_IOCTL_SET_SEPOLICY, KSU_IOCTL_CHECK_SAFEMODE,
        KSU_IOCTL_GET_ALLOW_LIST, KSU_IOCTL_GET_DENY_LIST,
        KSU_IOCTL_NEW_GET_ALLOW_LIST, KSU_IOCTL_NEW_GET_DENY_LIST,
        KSU_IOCTL_UID_GRANTED_ROOT, KSU_IOCTL_UID_SHOULD_UMOUNT,
        KSU_IOCTL_GET_MANAGER_APPID, KSU_IOCTL_GET_APP_PROFILE,
        KSU_IOCTL_SET_APP_PROFILE, KSU_IOCTL_GET_FEATURE, KSU_IOCTL_SET_FEATURE,
        KSU_IOCTL_GET_WRAPPER_FD, KSU_IOCTL_MANAGE_MARK, KSU_IOCTL_NUKE_EXT4_SYSFS,
        KSU_IOCTL_ADD_TRY_UMOUNT, KSU_IOCTL_SET_INIT_PGRP, KSU_IOCTL_GET_SULOG_FD,
        KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT, KSU_IOCTL_GET_AUTH_PROFILE,
        KSU_IOCTL_SET_AUTH_PROFILE, KSU_IOCTL_LOOKUP_SYMBOLS,
        KSU_IOCTL_SUPERKEY_SALT, KSU_IOCTL_SUPERKEY_AUTH,
    };
    for (size_t i = 0; i < std::size(commands); ++i)
        for (size_t j = i + 1; j < std::size(commands); ++j)
            assert(commands[i] != commands[j]);
    puts("PASS: UAPI v8 preserves authorization/superkey ABI and all ioctl commands are distinct");
}
