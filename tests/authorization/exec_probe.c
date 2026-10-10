/* SPDX-License-Identifier: GPL-2.0 */
/* Compile with Android NDK; execute as the disposable fixture UID. */
#include <stdbool.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include "uapi/supercall.h"

int main(int argc, char **argv)
{
    if (argc != 2)
        return 64;
    if (!strcmp(argv[1], "fault")) {
        /* Root profile application succeeds, but copying argv for ksud
         * exec fails. A temporary request must be killed and refunded. */
        syscall(SYS_execve, "/system/bin/su", (void *)-1, NULL);
        fprintf(stderr, "unexpected return from failed temporary exec: %d\n", errno);
        return 65;
    }
    if (!strcmp(argv[1], "ioctl")) {
        int fd = -1;
        syscall(SYS_reboot, KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, &fd);
        if (fd < 0)
            return 66;
        int ret = ioctl(fd, KSU_IOCTL_GRANT_ROOT, 0);
        close(fd);
        if (ret < 0) {
            printf("denied:%d\n", errno);
            return 2;
        }
        printf("granted:%u\n", getuid());
        return 0;
    }
    return 64;
}
