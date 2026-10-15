//
// Created by weishu on 2022/12/9.
//

#ifndef KERNELSU_KSU_H
#define KERNELSU_KSU_H

#include <cstdint>
#include <cstddef>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <utility>

#include "uapi/ksu.h"

uint32_t get_kernel_uapi_version();

uint32_t get_manager_uapi_version();

uint32_t get_version();

bool uid_should_umount(int uid);

bool is_safe_mode();

bool is_lkm_mode();

bool is_lkm_bundled();

bool is_late_load_mode();

bool is_manager();
bool superkey_salt(uint8_t salt[16]);
bool superkey_auth(const uint8_t proof[32]);

// Bionic's explicit_bzero is unavailable on some supported API levels.
inline void clear_superkey(void *buffer, size_t length) {
    auto *bytes = static_cast<volatile uint8_t *>(buffer);
    while (length--) *bytes++ = 0;
}

bool is_pr_build();

using p_key_t = char[KSU_MAX_PACKAGE_NAME];

bool set_app_profile(const app_profile *profile);

int get_app_profile(app_profile *profile);
int get_auth_profile(ksu_auth_profile_cmd *cmd);
int set_auth_profile(const ksu_auth_profile_cmd *cmd);

// Su compat
bool set_su_enabled(bool enabled);

bool is_su_enabled();

// Kernel umount
bool set_kernel_umount_enabled(bool enabled);

bool is_kernel_umount_enabled();

// SELinux hide
int set_selinux_hide_enabled(bool enabled);

bool is_selinux_hide_enabled();

bool get_allow_list(struct ksu_new_get_allow_list_cmd *);

inline std::pair<int, int> legacy_get_info() {
    int32_t version = -1;
    int32_t flags = 0;
    int32_t result = 0;
    prctl(0xDEADBEEF, 2, &version, &flags, &result);
    return {version, flags};
}

#endif //KERNELSU_KSU_H
