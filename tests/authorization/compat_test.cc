#include <cassert>
#include <cstdio>
#include <initializer_list>
#include "manager/app/src/main/cpp/authorization_compat.h"

int main() {
    for (int error : {ENOTTY, EOPNOTSUPP, ENOSYS})
        assert(authorization_ioctl_unsupported(error));
    // Entry absence proves support; access, ABI, stale and storage failures
    // must never be interpreted as a permanent-only Core.
    for (int error : {0, ENOENT, EPERM, EACCES, EBADF, EINVAL, EAGAIN, EBUSY, ESTALE, EIO})
        assert(!authorization_ioctl_unsupported(error));
    ksu_auth_profile_cmd reply = {};
    reply.profile.version = 4;
    reply.profile.curr_uid = 10387;
    assert(authorization_reply_valid(reply, 10387));
    assert(!authorization_reply_valid(reply, 110387));
    reply.profile.version = 5;
    assert(!authorization_reply_valid(reply, 10387));
    reply.profile.version = 4;
    reply.mode = 99;
    assert(!authorization_reply_valid(reply, 10387));
    reply.mode = 1;
    reply.state = 99;
    assert(!authorization_reply_valid(reply, 10387));
    reply.state = 1;
    memset(reply.profile.key, 'a', sizeof(reply.profile.key));
    assert(!authorization_reply_valid(reply, 10387));
    puts("PASS: ioctl capability errors cannot silently downgrade policy");
}
