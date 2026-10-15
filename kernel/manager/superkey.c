#include <crypto/algapi.h>
#include <crypto/hash.h>
#include <linux/jiffies.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/uaccess.h>
#include "manager/manager_identity.h"
#include "manager/superkey.h"
#include "uapi/supercall.h"

/* Patched in the LKM before boot-image installation, never a writable module parameter.
 * Layout: 16-byte magic, LE32 format (0=off, 1=PBKDF2-SHA256), salt[16], SHA256(proof)[32].
 * The proof is PBKDF2-HMAC-SHA256(key, salt, 210000, 32). The verifier is NOT an auth token.
 * Mutable at link time to prevent constant folding; read-only after module initialization.
 */
unsigned char ksu_superkey_config[68] __ro_after_init = "LUNEXSU-KEY-V1!!";
static DEFINE_MUTEX(superkey_lock);
static uid_t authenticated_uid = (uid_t)-1;
static unsigned long retry_after;

static bool required(void)
{
    return ksu_superkey_config[16] || ksu_superkey_config[17] || ksu_superkey_config[18] || ksu_superkey_config[19];
}

bool ksu_superkey_authorized(uid_t uid)
{
    return !required() || READ_ONCE(authenticated_uid) == uid;
}

void ksu_superkey_revoke(void)
{
    mutex_lock(&superkey_lock);
    WRITE_ONCE(authenticated_uid, (uid_t)-1);
    mutex_unlock(&superkey_lock);
}

/* This appid is established ONLY by throne_tracker's package and APK signature checks.
 * A password never appoints an arbitrary application as Manager.
 */
static bool candidate(void)
{
    return ksu_is_manager_appid_valid() && ksu_get_manager_appid() == current_uid().val % KSU_PER_USER_RANGE;
}

int ksu_superkey_salt(void __user *arg)
{
    struct ksu_superkey_salt_cmd cmd;
    if (!candidate() || !required() || ksu_superkey_config[16] != 1 || ksu_superkey_config[17] ||
        ksu_superkey_config[18] || ksu_superkey_config[19])
        return -EACCES;
    memcpy(cmd.salt, ksu_superkey_config + 20, sizeof(cmd.salt));
    return copy_to_user(arg, &cmd, sizeof(cmd)) ? -EFAULT : 0;
}

int ksu_superkey_auth(void __user *arg)
{
    struct ksu_superkey_auth_cmd cmd;
    struct crypto_shash *tfm;
    struct shash_desc *desc;
    unsigned char digest[32];
    int ret = -EACCES;

    if (!candidate() || !required())
        return -EACCES;
    if (copy_from_user(&cmd, arg, sizeof(cmd)))
        return -EFAULT;
    mutex_lock(&superkey_lock);
    if (retry_after && time_before(jiffies, retry_after))
        goto out;
    /* Limit expensive attempts from the signed candidate; other apps cannot lock it out. */
    retry_after = jiffies + 2 * HZ;
    if (ksu_superkey_config[16] != 1 || ksu_superkey_config[17] || ksu_superkey_config[18] || ksu_superkey_config[19])
        goto out;
    tfm = crypto_alloc_shash("sha256", 0, 0);
    if (IS_ERR(tfm))
        goto out;
    desc = kzalloc(sizeof(*desc) + crypto_shash_descsize(tfm), GFP_KERNEL);
    if (desc) {
        desc->tfm = tfm;
        if (!crypto_shash_digest(desc, cmd.proof, sizeof(cmd.proof), digest) &&
            !crypto_memneq(digest, ksu_superkey_config + 36, sizeof(digest)) && candidate()) {
            WRITE_ONCE(authenticated_uid, current_uid().val);
            ret = 0;
        }
        kfree_sensitive(desc);
    }
    crypto_free_shash(tfm);
out:
    memzero_explicit(digest, sizeof(digest));
    memzero_explicit(&cmd, sizeof(cmd));
    mutex_unlock(&superkey_lock);
    return ret;
}
