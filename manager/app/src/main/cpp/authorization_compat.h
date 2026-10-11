#pragma once

#include <cerrno>
#include <cstddef>
#include <cstring>
#include "uapi/authorization.h"

static_assert(KSU_APP_PROFILE_VER == 4 && sizeof(app_profile) == 784, "Upstream profile ABI drift");
static_assert(sizeof(ksu_auth_profile_cmd) == 824 && offsetof(ksu_auth_profile_cmd, mode) == 784 &&
              offsetof(ksu_auth_profile_cmd, generation) == 800, "Authorization ABI drift");

inline bool authorization_reply_valid(const ksu_auth_profile_cmd &cmd, int uid) {
    return cmd.profile.version == KSU_APP_PROFILE_VER && cmd.profile.curr_uid == uid &&
           memchr(cmd.profile.key, '\0', sizeof(cmd.profile.key)) &&
           cmd.mode <= KSU_AUTH_TIMED && cmd.state <= KSU_AUTH_BUSY && !cmd.reserved;
}

// A missing app entry still proves that the new ioctl exists. Only explicit
// unsupported-command errors permit reading the upstream permanent-only ABI.
inline bool authorization_ioctl_unsupported(int error) {
    return error == ENOTTY || error == EOPNOTSUPP || error == ENOSYS;
}
