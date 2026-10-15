#ifndef __LUNEXSU_SUPERKEY_H
#define __LUNEXSU_SUPERKEY_H

#include <linux/types.h>
#include <linux/errno.h>

#ifndef CONFIG_KSU_DISABLE_MANAGER
bool ksu_superkey_authorized(uid_t uid);
void ksu_superkey_revoke(void);
int ksu_superkey_salt(void __user *arg);
int ksu_superkey_auth(void __user *arg);
#else
static inline bool ksu_superkey_authorized(uid_t uid)
{
    return true;
}
static inline void ksu_superkey_revoke(void)
{
}
static inline int ksu_superkey_salt(void __user *arg)
{
    return -EOPNOTSUPP;
}
static inline int ksu_superkey_auth(void __user *arg)
{
    return -EOPNOTSUPP;
}
#endif
#endif
