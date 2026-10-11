#include <linux/rcupdate.h>
#include <linux/limits.h>
#include <linux/rculist.h>
#include <linux/mutex.h>
#include <linux/task_work.h>
#include <linux/capability.h>
#include <linux/compiler.h>
#include <linux/fs.h>
#include <linux/gfp.h>
#include <linux/kernel.h>
#include <linux/list.h>
#include <linux/printk.h>
#include <linux/slab.h>
#include <linux/types.h>
#include <linux/version.h>
#include <linux/compiler_types.h>
#include <linux/hashtable.h>
#include <linux/kref.h>
#include <linux/namei.h>
#include <linux/timekeeping.h>
#include <linux/file.h>
#include <linux/mount.h>

#include "klog.h" // IWYU pragma: keep
#include "ksu.h"
#include "runtime/ksud_boot.h"
#include "selinux/selinux.h"
#include "policy/allowlist.h"
#include "manager/manager_identity.h"
#include "infra/su_mount_ns.h"
#include "policy/authorization_state.h"
#include "policy/authorization_persistence.h"
#include "policy/authorization_path.h"

#define FILE_MAGIC 0x7f4b5355 // ' KSU', u32
#define FILE_FORMAT_VERSION 4 // upstream format; temporary grants are stored without Root
#define FILE_FORMAT_VERSION_AUTH 5 // read only: earlier builds stored mode and duration per record

#define KSU_APP_PROFILE_PRESERVE_UID 9999 // NOBODY_UID
#define KSU_DEFAULT_SELINUX_DOMAIN "u:r:" KERNEL_SU_DOMAIN ":s0"

static DEFINE_MUTEX(allowlist_mutex);

// default profiles, these may be used frequently, so we cache it
static struct root_profile default_root_profile;
static struct non_root_profile default_non_root_profile;

static void __init init_default_profiles()
{
    kernel_cap_t full_cap = CAP_FULL_SET;

    default_root_profile.uid = 0;
    default_root_profile.gid = 0;
    default_root_profile.groups_count = 1;
    default_root_profile.groups[0] = 0;
    memcpy(&default_root_profile.capabilities.effective, &full_cap,
           sizeof(default_root_profile.capabilities.effective));
    default_root_profile.namespaces = KSU_NS_INHERITED;
    strcpy(default_root_profile.selinux_domain, KSU_DEFAULT_SELINUX_DOMAIN);

    // This means that we will umount modules by default!
    default_non_root_profile.umount_modules = true;

    default_root_profile.flags = 0;
}

struct perm_data {
    struct hlist_node list;
    struct rcu_head rcu;
    struct kref ref;
    struct app_profile profile;
    struct ksu_authorization_state auth;
    u64 generation;
    unsigned int in_flight;
    bool revoked;
    struct file *data_identity;
};

// protected by rcu
#define ALLOW_LIST_BITS 8
static DEFINE_HASHTABLE(allow_list, ALLOW_LIST_BITS);
static u16 allow_list_count = 0;
static u64 authorization_generation;

#define KERNEL_SU_ALLOWLIST_DIR "/data/adb/ksu"
#define KERNEL_SU_ALLOWLIST KERNEL_SU_ALLOWLIST_DIR "/.allowlist"
#define KERNEL_SU_ALLOWLIST_TMP KERNEL_SU_ALLOWLIST_DIR "/.allowlist.tmp"
#define KERNEL_SU_ALLOWLIST_PENDING KERNEL_SU_ALLOWLIST_DIR "/.allowlist.pending"

void ksu_persistent_allow_list(void);
static int write_allowlist_locked(struct perm_data *replacement);

/* Only temporary App grants require an install identity. Full Android UID is
 * used, including the userId. Reinstall/data reset or removal of this user
 * invalidates the grant even when PackageManager reuses the same UID.
 */
static struct file *open_app_data(struct app_profile *profile)
{
    struct file *file;
    struct inode *inode;
    const struct cred *saved;
    struct path root;
    int ret;
    char name[KSU_MAX_PACKAGE_NAME + 48];
    const char *c;

    if (!is_appuid(profile->curr_uid) || !profile->key[0])
        return ERR_PTR(-EINVAL);
    for (c = profile->key; *c; c++) {
        if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '_' ||
              *c == '.'))
            return ERR_PTR(-EINVAL);
    }
    if (strstr(profile->key, "..") || profile->key[0] == '.')
        return ERR_PTR(-EINVAL);
    snprintf(name, sizeof(name), "/data/user/%u/%s", profile->curr_uid / PER_USER_RANGE, profile->key);
    ret = ksu_auth_init_root(&root);
    if (ret)
        return ERR_PTR(ret);
    saved = override_creds(ksu_cred);
    file = ksu_auth_open_root(&root, name, O_PATH | O_DIRECTORY | O_NOFOLLOW, 0);
    revert_creds(saved);
    path_put(&root);
    if (IS_ERR(file))
        return file;
    inode = file_inode(file);
    if (!inode || !S_ISDIR(inode->i_mode) || inode->i_uid.val != profile->curr_uid) {
        fput(file);
        return ERR_PTR(-ESTALE);
    }
    /* Pin the inode: even immediate inode-number reuse cannot impersonate an
     * uninstalled app's old data directory. O_PATH confers no userspace fd. */
    return file;
}

static bool same_install(struct perm_data *p)
{
    struct file *file;
    bool same;
    if (!p->data_identity)
        return false;
    file = open_app_data(&p->profile);
    if (IS_ERR(file))
        return false;
    same = file_inode(file) == file_inode(p->data_identity);
    fput(file);
    return same;
}

static bool auth_available_rcu(struct perm_data *p)
{
    struct ksu_authorization_state state = {
        .mode = READ_ONCE(p->auth.mode),
        .once = READ_ONCE(p->auth.once),
        .deadline_ns = READ_ONCE(p->auth.deadline_ns),
    };
    return !READ_ONCE(p->revoked) && p->profile.allow_su && ksu_auth_available(&state, ktime_get_boottime_ns());
}

void ksu_show_allow_list(void)
{
    int i;
    struct perm_data *p = NULL;
    pr_info("ksu_show_allow_list\n");
    rcu_read_lock();
    hash_for_each_rcu (allow_list, i, p, list) {
        pr_info("uid :%d, allow: %d\n", p->profile.curr_uid, p->profile.allow_su);
    }
    rcu_read_unlock();
}

struct app_profile *ksu_get_app_profile(uid_t uid)
{
    struct perm_data *p = NULL;
    bool found;

retry:
    found = false;
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid) {
            // found it, override it with ours
            found = true;
            break;
        }
    }

    if (!found)
        return NULL;

    if (!kref_get_unless_zero(&p->ref)) {
        goto retry;
    }

    return &p->profile;
}

static inline bool forbid_system_uid(uid_t uid)
{
#define SHELL_UID 2000
#define SYSTEM_UID 1000
    return uid < SHELL_UID && uid != SYSTEM_UID;
}

static bool profile_valid(struct app_profile *profile)
{
    if (!profile) {
        return false;
    }
    if (profile->curr_uid < 0)
        return false;

    if (strnlen(profile->key, sizeof(profile->key)) >= sizeof(profile->key)) {
        pr_err("invalid app_profile key\n");
        return false;
    }

    if (profile->version != KSU_APP_PROFILE_VER) {
        pr_info("Unsupported profile version: %d\n", profile->version);
        return false;
    }

    if (profile->allow_su) {
#ifndef CONFIG_KSU_DISABLE_POLICY
        if (profile->rp_config.profile.groups_count > KSU_MAX_GROUPS) {
            pr_err("invalid groups_count in app_profile: %s\n", profile->key);
            return false;
        }

        static const size_t domain_len = sizeof(profile->rp_config.profile.selinux_domain);
        size_t len = strnlen(profile->rp_config.profile.selinux_domain, domain_len);

        if (len == 0 || len >= domain_len) {
            pr_err("invalid selinux_domain in app_profile: %s\n", profile->key);
            return false;
        }
#endif
    }

    return true;
}

static void release_perm_data(struct kref *ref)
{
    struct perm_data *p = container_of(ref, struct perm_data, ref);
    if (p->data_identity)
        fput(p->data_identity);
    kfree_rcu(p, rcu);
}

static void put_perm_data(struct perm_data *data)
{
    kref_put(&data->ref, release_perm_data);
}

static int set_profile(struct app_profile *profile, struct ksu_auth_profile_cmd *cmd, bool restoring)
{
    struct perm_data *p, *np;
    int result = 0;

    if (!profile_valid(profile)) {
        pr_err("Failed to set app profile: invalid profile!\n");
        return -EINVAL;
    }

#ifdef CONFIG_KSU_DISABLE_POLICY
    if (profile->allow_su) {
        profile->rp_config.use_default = true;
        memset(profile->rp_config.template_name, 0, sizeof(profile->rp_config.template_name));
        memset(&profile->rp_config.profile, 0, sizeof(profile->rp_config.profile));
    } else {
        profile->nrp_config.use_default = true;
        memset(&profile->nrp_config.profile, 0, sizeof(profile->nrp_config.profile));
    }
#endif

    // only allow default non root profile
    if (unlikely(profile->curr_uid == KSU_APP_PROFILE_PRESERVE_UID && strcmp(profile->key, "$") != 0)) {
        return -EINVAL;
    }

    mutex_lock(&allowlist_mutex);

    hash_for_each_possible (allow_list, p, list, profile->curr_uid) {
        if (profile->curr_uid == p->profile.curr_uid) {
            if (strcmp(profile->key, p->profile.key) != 0) {
                pr_warn("ksu_set_app_profile: key changed: uid=%d orig=%s new=%s\n", profile->curr_uid, p->profile.key,
                        profile->key);
            }
            /* Revocation may stop future admissions immediately even while
             * an already-admitted exec is in progress. Other changes wait. */
            if (p->in_flight && profile->allow_su) {
                result = -EBUSY;
                goto out_unlock;
            }
            if (cmd && !restoring && cmd->generation != p->generation) {
                result = -EAGAIN;
                goto out_unlock;
            }
            // Preserve runtime quota/deadline when merely editing a profile.
            np = (struct perm_data *)kzalloc(sizeof(struct perm_data), GFP_KERNEL);
            if (!np) {
                result = -ENOMEM;
                goto out_unlock;
            }
            kref_init(&np->ref);
            memcpy(&np->profile, profile, sizeof(*profile));
            np->auth = p->auth;
            if (p->data_identity)
                np->data_identity = get_file(p->data_identity);
            goto publish;
        }
    }

    if (unlikely(allow_list_count == U16_MAX)) {
        pr_err("too many app profile\n");
        result = -E2BIG;
        goto out_unlock;
    }

    // not found, alloc a new node!
    np = (struct perm_data *)kzalloc(sizeof(struct perm_data), GFP_KERNEL);
    if (!np) {
        pr_err("ksu_set_app_profile alloc failed\n");
        result = -ENOMEM;
        goto out_unlock;
    }

    kref_init(&np->ref);
    memcpy(&np->profile, profile, sizeof(*profile));
    if (cmd && !restoring && cmd->generation) {
        result = -EAGAIN;
        goto free_new;
    }
    p = NULL;
publish:
    if (cmd) {
        if (cmd->mode > KSU_AUTH_TIMED || cmd->operation > KSU_AUTH_GRANT || cmd->reserved) {
            result = -EINVAL;
            goto free_new;
        }
        if (!restoring && !ksu_auth_operation_valid(cmd->operation, p && p->profile.allow_su, profile->allow_su)) {
            result = -EINVAL;
            goto free_new;
        }
        if (restoring) {
            np->auth.mode = cmd->mode;
            np->auth.duration_seconds = cmd->duration_seconds;
            ksu_auth_invalidate(&np->auth);
        } else if (cmd->operation == KSU_AUTH_GRANT) {
            if (!profile->allow_su ||
                !ksu_auth_grant(&np->auth, cmd->mode, cmd->duration_seconds, ktime_get_boottime_ns())) {
                result = -EINVAL;
                goto free_new;
            }
            if (cmd->mode != KSU_AUTH_PERMANENT) {
                struct file *identity;
                if (is_uid_manager(profile->curr_uid)) {
                    result = -EINVAL;
                    goto free_new;
                }
                identity = open_app_data(profile);
                if (IS_ERR(identity)) {
                    result = PTR_ERR(identity);
                    goto free_new;
                }
                if (np->data_identity)
                    fput(np->data_identity);
                np->data_identity = identity;
            }
        } else if (cmd->mode != np->auth.mode) {
            result = -EINVAL;
            goto free_new;
        }
    }
    if (p && strcmp(profile->key, p->profile.key)) {
        /* A metadata edit must never transfer a temporary grant to another
         * package sharing/reusing the UID. Require an explicit new grant. */
        if (!cmd || cmd->operation != KSU_AUTH_GRANT)
            ksu_auth_invalidate(&np->auth);
    }
    if (!profile->allow_su)
        ksu_auth_invalidate(&np->auth);
    if ((!profile->allow_su || np->auth.mode == KSU_AUTH_PERMANENT) && np->data_identity) {
        fput(np->data_identity);
        np->data_identity = NULL;
    }
    np->generation = ++authorization_generation;
    /* Write the candidate BEFORE publishing it. An acknowledged temporary
     * grant can never reboot into the previous permanent on-disk grant. */
    if (cmd && !restoring) {
        result = write_allowlist_locked(np);
        if (result)
            goto free_new;
    }
    if (p) {
        if (!profile->allow_su)
            WRITE_ONCE(p->revoked, true);
        hlist_replace_rcu(&p->list, &np->list);
        put_perm_data(p);
        goto out;
    }
    if (profile->allow_su) {
        pr_info("set root profile, key: %s, uid: %d, gid: %d, context: %s\n", profile->key, profile->curr_uid,
                profile->rp_config.profile.gid, profile->rp_config.profile.selinux_domain);
    } else {
        pr_info("set app profile, key: %s, uid: %d, umount modules: %d\n", profile->key, profile->curr_uid,
                profile->nrp_config.profile.umount_modules);
    }

    hash_add_rcu(allow_list, &np->list, np->profile.curr_uid);
    ++allow_list_count;

out:
    result = 0;

    if (unlikely(profile->curr_uid == KSU_APP_PROFILE_PRESERVE_UID)) {
        // set default non root profile
        default_non_root_profile.umount_modules = profile->nrp_config.profile.umount_modules;
    }

out_unlock:
    mutex_unlock(&allowlist_mutex);
    return result;
free_new:
    if (np->data_identity)
        fput(np->data_identity);
    kfree(np);
    goto out_unlock;
}

int ksu_set_app_profile(struct app_profile *profile)
{
    return set_profile(profile, NULL, false);
}

int ksu_set_auth_profile(struct ksu_auth_profile_cmd *cmd)
{
    return set_profile(&cmd->profile, cmd, false);
}

int ksu_get_auth_profile(struct ksu_auth_profile_cmd *cmd)
{
    struct perm_data *p;
    u64 now;
    int ret = -ENOENT;

    mutex_lock(&allowlist_mutex);
    hash_for_each_possible (allow_list, p, list, cmd->profile.curr_uid) {
        if (p->profile.curr_uid != cmd->profile.curr_uid)
            continue;
        /* Authorization is UID-wide, including sharedUserId packages. Return
         * the saved package anchor and generation even if the caller selected
         * another package. Only explicit GRANT may bind a new anchor. */
        cmd->profile = p->profile;
        cmd->mode = p->auth.mode;
        cmd->operation = KSU_AUTH_KEEP;
        cmd->generation = p->generation;
        cmd->duration_seconds = p->auth.duration_seconds;
        cmd->remaining_seconds = 0;
        cmd->reserved = 0;
        now = ktime_get_boottime_ns();
        cmd->state = auth_available_rcu(p) ? KSU_AUTH_ACTIVE : KSU_AUTH_INACTIVE;
        if (p->auth.mode != KSU_AUTH_PERMANENT && cmd->state == KSU_AUTH_ACTIVE && !same_install(p)) {
            ksu_auth_invalidate(&p->auth);
            cmd->state = KSU_AUTH_INACTIVE;
            p->generation = ++authorization_generation;
            cmd->generation = p->generation;
        }
        if (p->auth.mode == KSU_AUTH_ONCE && p->auth.once == 2)
            cmd->state = KSU_AUTH_BUSY;
        if (cmd->state == KSU_AUTH_ACTIVE && p->auth.mode == KSU_AUTH_TIMED && p->auth.deadline_ns > now)
            cmd->remaining_seconds = (p->auth.deadline_ns - now + 999999999ULL) / 1000000000ULL;
        ret = 0;
        break;
    }
    mutex_unlock(&allowlist_mutex);
    return ret;
}

int ksu_authorization_begin(uid_t uid, bool su_exec, struct ksu_auth_lease *lease)
{
    struct perm_data *p;
    struct root_profile *root;
    int ret = -EPERM;

    memset(lease, 0, sizeof(*lease));
    mutex_lock(&allowlist_mutex);
    if ((uid == 0 && is_ksu_domain()) || is_uid_manager(uid) || (allow_shell && uid == SHELL_UID))
        goto bypass;
    if (forbid_system_uid(uid))
        goto out;
    hash_for_each_possible (allow_list, p, list, uid) {
        if (p->profile.curr_uid != uid)
            continue;
        if (!auth_available_rcu(p))
            goto out;
        if (p->auth.mode != KSU_AUTH_PERMANENT && !same_install(p)) {
            ksu_auth_invalidate(&p->auth);
            p->generation = ++authorization_generation;
            goto out;
        }
        if (!ksu_auth_reserve(&p->auth, ktime_get_boottime_ns(), su_exec))
            goto out;
        root = ksu_get_root_profile(uid);
        lease->profile = *root;
        ksu_put_root_profile(root);
        kref_get(&p->ref);
        ++p->in_flight;
        lease->entry = p;
        lease->temporary = p->auth.mode != KSU_AUTH_PERMANENT;
        ret = 0;
        goto out;
    }
    goto out;
bypass:
    root = ksu_get_root_profile(uid);
    lease->profile = *root;
    ksu_put_root_profile(root);
    ret = 0;
out:
    mutex_unlock(&allowlist_mutex);
    return ret;
}

void ksu_authorization_finish(struct ksu_auth_lease *lease, bool success)
{
    struct perm_data *p = lease->entry;
    if (!p)
        return;
    mutex_lock(&allowlist_mutex);
    if (!p->revoked) {
        ksu_auth_finish(&p->auth, success);
        if (p->auth.mode == KSU_AUTH_ONCE)
            p->generation = ++authorization_generation;
    }
    --p->in_flight;
    mutex_unlock(&allowlist_mutex);
    put_perm_data(p);
    lease->entry = NULL;
}

static bool has_allow_uid(uid_t uid, bool configured)
{
    struct perm_data *p;

    if (forbid_system_uid(uid)) {
        // do not bother going through the list if it's system
        return false;
    }

    if (unlikely(is_uid_manager(uid))) {
        // manager is always allowed!
        return true;
    }

    if (unlikely(allow_shell) && uid == SHELL_UID) {
        return true;
    }

    rcu_read_lock();
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid && (configured ? p->profile.allow_su : auth_available_rcu(p))) {
            rcu_read_unlock();
            return true;
        }
    }
    rcu_read_unlock();

    return false;
}

bool ksu_has_root_profile(uid_t uid)
{
    if (unlikely(uid == 0))
        return is_ksu_domain();
    return has_allow_uid(uid, true);
}

bool __ksu_is_allow_uid(uid_t uid)
{
    return has_allow_uid(uid, false);
}

bool __ksu_is_allow_uid_for_current(uid_t uid)
{
    if (unlikely(uid == 0)) {
        // already root, but only allow our domain.
        return is_ksu_domain();
    }
    return __ksu_is_allow_uid(uid);
}

bool ksu_uid_should_umount(uid_t uid)
{
    struct app_profile *profile;
    bool res;
    if (likely(ksu_is_manager_appid_valid()) && unlikely(ksu_get_manager_appid() == uid % PER_USER_RANGE)) {
        // we should not umount on manager!
        return false;
    }
#ifdef CONFIG_KSU_DISABLE_POLICY
    return !has_allow_uid(uid, false);
#else
    rcu_read_lock();
    profile = ksu_get_app_profile(uid);
    if (!profile) {
        // no app profile found, it must be non root app
        res = default_non_root_profile.umount_modules;
    } else {
        // only an active grant keeps modules mounted for a Root profile
        struct perm_data *p = container_of(profile, struct perm_data, profile);
        res =
            ksu_auth_should_umount(profile->allow_su, auth_available_rcu(p), profile->nrp_config.use_default,
                                   profile->nrp_config.profile.umount_modules, default_non_root_profile.umount_modules);
    }
    rcu_read_unlock();

    if (profile)
        ksu_put_app_profile(profile);
    return res;
#endif
}

void ksu_put_app_profile(struct app_profile *profile)
{
    struct perm_data *p = container_of(profile, struct perm_data, profile);
    put_perm_data(p);
}

struct root_profile *ksu_get_root_profile(uid_t uid)
{
#ifdef CONFIG_KSU_DISABLE_POLICY
    (void)uid;
    return &default_root_profile;
#else
    struct perm_data *p = NULL;
    struct root_profile *res;

    rcu_read_lock();
    if (is_uid_manager(uid)) {
        goto use_default;
    }

    if (unlikely(allow_shell && uid == SHELL_UID)) {
        goto use_default;
    }

retry:
    res = NULL;
    hash_for_each_possible_rcu (allow_list, p, list, uid) {
        if (uid == p->profile.curr_uid && p->profile.allow_su) {
            if (!p->profile.rp_config.use_default) {
                if (!kref_get_unless_zero(&p->ref)) {
                    goto retry;
                }
                res = &p->profile.rp_config.profile;
            }
            break;
        }
    }

    if (unlikely(!res)) {
    use_default:
        res = &default_root_profile;
    }

    rcu_read_unlock();
    return res;
#endif
}

void ksu_put_root_profile(struct root_profile *profile)
{
    if (likely(profile == &default_root_profile))
        return;
    struct perm_data *p = container_of(profile, struct perm_data, profile.rp_config.profile);
    put_perm_data(p);
}

bool ksu_get_allow_list(int *array, u16 length, u16 *out_length, u16 *out_total, bool allow)
{
    struct perm_data *p = NULL;
    u16 i = 0, j = 0;
    int iter;
    rcu_read_lock();
    hash_for_each_rcu (allow_list, iter, p, list) {
        // pr_info("get_allow_list uid: %d allow: %d\n", p->uid, p->allow);
        if (auth_available_rcu(p) == allow && !is_uid_manager(p->profile.curr_uid)) {
            if (j < length) {
                array[j++] = p->profile.curr_uid;
            }
            ++i;
        }
    }
    rcu_read_unlock();
    if (out_length) {
        *out_length = j;
    }
    if (out_total) {
        *out_total = i;
    }

    return true;
}

struct allowlist_store {
    struct perm_data *replacement;
    struct file *directory;
    struct file *staging;
};

static struct dentry *lookup_allowlist_name(const struct path *parent, const char *name)
{
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 16, 0)
    struct qstr qname = QSTR_INIT(name, strlen(name));
    return lookup_one(mnt_idmap(parent->mnt), &qname, parent->dentry);
#else
    return lookup_one_len(name, parent->dentry, strlen(name));
#endif
}

static int unlink_allowlist_name(struct allowlist_store *store, const char *name)
{
    const struct path *parent = &store->directory->f_path;
    struct dentry *entry;
    int ret = mnt_want_write(parent->mnt);

    if (ret)
        return ret;
    inode_lock(d_inode(parent->dentry));
    entry = lookup_allowlist_name(parent, name);
    if (IS_ERR(entry)) {
        ret = PTR_ERR(entry);
    } else {
        if (d_is_positive(entry)) {
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 3, 0)
            ret = vfs_unlink(mnt_idmap(parent->mnt), d_inode(parent->dentry), entry, NULL);
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(5, 12, 0)
            ret = vfs_unlink(mnt_user_ns(parent->mnt), d_inode(parent->dentry), entry, NULL);
#else
            ret = vfs_unlink(d_inode(parent->dentry), entry, NULL);
#endif
        }
        dput(entry);
    }
    inode_unlock(d_inode(parent->dentry));
    mnt_drop_write(parent->mnt);
    return ret;
}

static int prepare_allowlist_store(void *ctx)
{
    struct allowlist_store *store = ctx;
    struct file *guard;
    int ret;

    guard = ksu_auth_open_root(&store->directory->f_path, ".allowlist.pending", O_WRONLY | O_CREAT | O_NOFOLLOW, 0600);
    if (IS_ERR(guard))
        return PTR_ERR(guard);
    ret = -EINVAL;
    if (S_ISREG(file_inode(guard)->i_mode) && guard->f_path.mnt == store->directory->f_path.mnt &&
        guard->f_path.dentry->d_parent == store->directory->f_path.dentry) {
        ret = 0;
        if (store->replacement) {
            /* Appended, never rewritten: an earlier failed transaction stays guarded. */
            u32 uid = store->replacement->profile.curr_uid;
            loff_t pos = i_size_read(file_inode(guard));
            if (kernel_write(guard, &uid, sizeof(uid), &pos) != sizeof(uid))
                ret = -EIO;
        }
        if (!ret)
            ret = vfs_fsync(guard, 0);
    }
    filp_close(guard, 0);
    if (ret)
        return ret;
    /* Before rename can expose any candidate, reboot must see the guard. */
    return vfs_fsync(store->directory, 0);
}

static bool write_allowlist_record(struct file *fp, const struct perm_data *entry, loff_t *off)
{
    struct app_profile record = entry->profile;

    ksu_auth_prepare_record(&record, entry->auth.mode);
    return kernel_write(fp, &record, sizeof(record), off) == sizeof(record);
}

static int write_allowlist_snapshot(void *ctx)
{
    struct allowlist_store *store = ctx;
    struct perm_data *replacement = store->replacement;
    u32 magic = FILE_MAGIC;
    u32 version = FILE_FORMAT_VERSION;
    struct perm_data *p = NULL;
    loff_t off = 0;
    int i;
    bool replaced = false;
    int ret;
    struct file *fp;

    /* Never truncate an old staging inode: it could be a hard link. */
    ret = unlink_allowlist_name(store, ".allowlist.tmp");
    if (ret)
        return ret;
    fp =
        ksu_auth_open_root(&store->directory->f_path, ".allowlist.tmp", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
    if (IS_ERR(fp)) {
        return PTR_ERR(fp);
    }
    store->staging = fp;
    if (!S_ISREG(file_inode(fp)->i_mode) || file_inode(fp)->i_nlink != 1 ||
        fp->f_path.dentry->d_parent != store->directory->f_path.dentry)
        return -EINVAL;

    // store magic and version
    if (kernel_write(fp, &magic, sizeof(magic), &off) != sizeof(magic)) {
        pr_err("save_allow_list write magic failed.\n");
        return -EIO;
    }

    if (kernel_write(fp, &version, sizeof(version), &off) != sizeof(version)) {
        pr_err("save_allow_list write version failed.\n");
        return -EIO;
    }

    hash_for_each (allow_list, i, p, list) {
        struct perm_data *entry = p;
        if (replacement && p->profile.curr_uid == replacement->profile.curr_uid) {
            entry = replacement;
            replaced = true;
        }
        if (!write_allowlist_record(fp, entry, &off))
            return -EIO;
    }
    if (replacement && !replaced && !write_allowlist_record(fp, replacement, &off))
        return -EIO;
    return 0;
}

static int sync_allowlist_snapshot(void *ctx)
{
    struct allowlist_store *store = ctx;
    return vfs_fsync(store->staging, 0);
}

static int replace_allowlist_snapshot(void *ctx)
{
    struct allowlist_store *store = ctx;
    const struct path *parent = &store->directory->f_path;
    struct dentry *source, *destination;
    int ret;

    if (store->staging->f_path.mnt != parent->mnt)
        return -EXDEV;
    ret = mnt_want_write(parent->mnt);
    if (ret)
        return ret;
    lock_rename(parent->dentry, parent->dentry);
    source = lookup_allowlist_name(parent, ".allowlist.tmp");
    if (IS_ERR(source)) {
        ret = PTR_ERR(source);
        goto unlock;
    }
    /* Rename exactly the synced inode, never a substituted staging pathname. */
    if (source != store->staging->f_path.dentry) {
        ret = -ESTALE;
        goto put_source;
    }
    destination = lookup_allowlist_name(parent, ".allowlist");
    if (IS_ERR(destination)) {
        ret = PTR_ERR(destination);
        goto put_source;
    }
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 12, 0)
    struct renamedata rd = {
        .old_dentry = source,
        .new_dentry = destination,
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 17, 0)
        .old_parent = parent->dentry,
        .new_parent = parent->dentry,
#else
        .old_dir = d_inode(parent->dentry),
        .new_dir = d_inode(parent->dentry),
#endif
#if LINUX_VERSION_CODE >= KERNEL_VERSION(6, 18, 0)
        .mnt_idmap = mnt_idmap(parent->mnt),
#elif LINUX_VERSION_CODE >= KERNEL_VERSION(6, 3, 0)
        .old_mnt_idmap = mnt_idmap(parent->mnt),
        .new_mnt_idmap = mnt_idmap(parent->mnt),
#else
        .old_mnt_userns = mnt_user_ns(parent->mnt),
        .new_mnt_userns = mnt_user_ns(parent->mnt),
#endif
    };
    ret = vfs_rename(&rd);
#else
    ret = vfs_rename(d_inode(parent->dentry), source, d_inode(parent->dentry), destination, NULL, 0);
#endif
    dput(destination);
put_source:
    dput(source);
unlock:
    unlock_rename(parent->dentry, parent->dentry);
    mnt_drop_write(parent->mnt);
    return ret;
}

static int sync_allowlist_directory(void *ctx)
{
    struct allowlist_store *store = ctx;
    return vfs_fsync(store->directory, 0);
}

static void cleanup_allowlist_store(void *ctx)
{
    struct allowlist_store *store = ctx;
    int ret = unlink_allowlist_name(store, ".allowlist.pending");
    if (!ret)
        ret = sync_allowlist_directory(ctx);
    if (ret)
        pr_err("allowlist committed; guard cleanup failed (%d), reboot revokes the guarded uids\n", ret);
}

/* Caller holds allowlist_mutex for the entire transaction. A durable guard
 * prevents both an uncommitted candidate and an old permanent snapshot from
 * being restored after an interrupted replacement. No staging file is loaded.
 */
static int write_allowlist_locked(struct perm_data *replacement)
{
    const struct ksu_auth_storage_ops ops = {
        .prepare = prepare_allowlist_store,
        .write = write_allowlist_snapshot,
        .sync = sync_allowlist_snapshot,
        .replace = replace_allowlist_snapshot,
        .sync_directory = sync_allowlist_directory,
        .cleanup = cleanup_allowlist_store,
    };
    struct allowlist_store store = { .replacement = replacement };
    const struct cred *saved;
    struct path root;
    int ret;

    ret = ksu_auth_init_root(&root);
    if (ret)
        return ret;
    saved = override_creds(ksu_cred);
    store.directory = ksu_auth_open_root(&root, KERNEL_SU_ALLOWLIST_DIR, O_RDONLY | O_DIRECTORY | O_NOFOLLOW, 0);
    path_put(&root);
    if (IS_ERR(store.directory)) {
        ret = PTR_ERR(store.directory);
    } else {
        ret = ksu_auth_store(&ops, &store);
        if (store.staging)
            filp_close(store.staging, 0);
        filp_close(store.directory, 0);
    }
    revert_creds(saved);
    return ret;
}

static void do_persistent_allow_list(struct callback_head *_cb)
{
    mutex_lock(&allowlist_mutex);
    if (write_allowlist_locked(NULL))
        pr_err("failed to persist allowlist\n");
    mutex_unlock(&allowlist_mutex);
    kfree(_cb);
}

void ksu_persistent_allow_list()
{
    struct task_struct *tsk;

    rcu_read_lock();
    tsk = get_pid_task(find_vpid(1), PIDTYPE_PID);
    if (!tsk) {
        rcu_read_unlock();
        pr_err("save_allow_list find init task err\n");
        return;
    }
    rcu_read_unlock();

    struct callback_head *cb = kzalloc(sizeof(struct callback_head), GFP_KERNEL);
    if (!cb) {
        pr_err("save_allow_list alloc cb err\b");
        goto put_task;
    }
    cb->func = do_persistent_allow_list;
    if (task_work_add(tsk, cb, TWA_RESUME)) {
        kfree(cb);
        pr_warn("save_allow_list add task_work failed\n");
    }

put_task:
    put_task_struct(tsk);
}

static void migrate_profile(u32 version, struct app_profile *profile)
{
    char *domain;
    static const size_t domain_len = sizeof(profile->rp_config.profile.selinux_domain);

    switch (version) {
    case 2:
        if (profile->allow_su) {
            domain = profile->rp_config.profile.selinux_domain;
            if (strncmp(domain, "u:r:su:s0", domain_len) == 0) {
                strscpy_pad(domain, KSU_DEFAULT_SELINUX_DOMAIN, domain_len);
                pr_info("migrated domain of profile: %s\n", profile->key);
            }
        }
        fallthrough;
    case 3:
        if (profile->allow_su) {
            profile->rp_config.profile.flags = FLAG_KSU_NO_NEW_PRIVS;
        }
        break;
    }

    profile->version = KSU_APP_PROFILE_VER;
}

/* Returns whether an unfinished transaction left its marker. A marker that
 * cannot be read revokes Root from every record rather than trusting any.
 */
static bool read_allowlist_guard(struct ksu_auth_guard *guard)
{
    u32 uids[KSU_AUTH_GUARD_MAX_UIDS + 1];
    loff_t off = 0;
    ssize_t len;
    struct file *fp = filp_open(KERNEL_SU_ALLOWLIST_PENDING, O_RDONLY | O_NOFOLLOW, 0);

    memset(guard, 0, sizeof(*guard));
    if (IS_ERR(fp)) {
        if (PTR_ERR(fp) == -ENOENT)
            return false;
        guard->all = true;
        return true;
    }
    len = kernel_read(fp, uids, sizeof(uids), &off);
    filp_close(fp, 0);
    if (len < 0)
        guard->all = true;
    else
        ksu_auth_guard_parse(guard, uids, len);
    return true;
}

void ksu_load_allow_list()
{
#ifdef CONFIG_KSU_DISABLE_POLICY
    pr_info("allowlist load skipped because policy is disabled\n");
    return;
#endif

    loff_t off = 0;
    ssize_t ret = 0;
    struct file *fp = NULL;
    u32 magic;
    u32 version;
    size_t app_profile_size;
    static struct ksu_auth_guard guard;
    bool guarded = read_allowlist_guard(&guard);

    // load allowlist now!
    fp = filp_open(KERNEL_SU_ALLOWLIST, O_RDONLY, 0);
    if (IS_ERR(fp)) {
        pr_err("load_allow_list open file failed: %ld\n", PTR_ERR(fp));
        return;
    }

    // verify magic
    if (kernel_read(fp, &magic, sizeof(magic), &off) != sizeof(magic) || magic != FILE_MAGIC) {
        pr_err("allowlist file invalid: %d!\n", magic);
        goto exit;
    }

    // get file version
    if (kernel_read(fp, &version, sizeof(version), &off) != sizeof(version)) {
        pr_err("allowlist read version: %d failed\n", version);
        goto exit;
    }

    if (version < 2 || version > FILE_FORMAT_VERSION_AUTH) {
        pr_err("invalid allowlist version: %d\n", version);
        goto exit;
    }

    pr_info("allowlist version: %d\n", version);

    static const size_t kAppProfileSizePreV4 = 776;
    app_profile_size = version < 4 ? kAppProfileSizePreV4 : sizeof(struct app_profile);

    while (true) {
        struct ksu_auth_profile_cmd cmd = {};
        struct app_profile *profile = &cmd.profile;

        ret = kernel_read(fp, profile, app_profile_size, &off);

        if (ret != app_profile_size) {
            if (ret != 0)
                pr_info("load_allow_list read err: %zd\n", ret);
            break;
        }
        if (version == FILE_FORMAT_VERSION_AUTH) {
            if (kernel_read(fp, &cmd.mode, sizeof(cmd.mode), &off) != sizeof(cmd.mode) || cmd.mode > KSU_AUTH_TIMED ||
                kernel_read(fp, &cmd.duration_seconds, sizeof(cmd.duration_seconds), &off) !=
                    sizeof(cmd.duration_seconds) ||
                cmd.duration_seconds > KSU_AUTH_MAX_SECONDS) {
                pr_err("invalid authorization record\n");
                break;
            }
            // A temporary grant from before the reboot restores without Root.
            ksu_auth_prepare_record(profile, cmd.mode);
            cmd.mode = KSU_AUTH_PERMANENT;
            cmd.duration_seconds = 0;
        }

        migrate_profile(version, profile);
        if (guarded && profile->allow_su && ksu_auth_guard_covers(&guard, profile->curr_uid)) {
            pr_warn("unfinished authorization transaction, revoking Root for uid %d\n", profile->curr_uid);
            ksu_auth_strip_root(profile);
        }

        pr_info("load_allow_uid, name: %s, uid: %d, allow: %d\n", profile->key, profile->curr_uid, profile->allow_su);
        set_profile(&cmd.profile, &cmd, true);
    }
    ksu_show_allow_list();
    filp_close(fp, 0);
    // A guarded load persists its revocations, which also removes the marker.
    if (version != FILE_FORMAT_VERSION || guarded)
        ksu_persistent_allow_list();
    return;

exit:
    ksu_show_allow_list();
    filp_close(fp, 0);
}

void ksu_prune_allowlist(bool (*is_uid_valid)(uid_t, char *, void *), void *data)
{
    struct perm_data *np = NULL;
    struct hlist_node *tmp;
    int i;

    if (!ksu_boot_completed) {
        pr_info("boot not completed, skip prune\n");
        return;
    }

    bool modified = false;
    mutex_lock(&allowlist_mutex);
    hash_for_each_safe (allow_list, i, tmp, np, list) {
        uid_t uid = np->profile.curr_uid;
        char *package = np->profile.key;
        // we use this uid for special cases, don't prune it!
        bool is_preserved_uid = uid == KSU_APP_PROFILE_PRESERVE_UID || uid == WEBVIEW_ZYGOTE_UID;
        if (!is_preserved_uid && !is_uid_valid(uid, package, data)) {
            modified = true;
            pr_info("prune uid: %d, package: %s\n", uid, package);
            hlist_del_rcu(&np->list);
            WRITE_ONCE(np->revoked, true);
            put_perm_data(np);
            --allow_list_count;
        }
    }
    mutex_unlock(&allowlist_mutex);

    if (modified) {
        smp_mb();
        ksu_persistent_allow_list();
    }
}

void __init ksu_allowlist_init(void)
{
    init_default_profiles();
}

void __exit ksu_allowlist_exit(void)
{
    struct perm_data *np = NULL;
    struct hlist_node *tmp;
    int i;

    // free allowlist
    mutex_lock(&allowlist_mutex);
    hash_for_each_safe (allow_list, i, tmp, np, list) {
        hlist_del(&np->list);
        put_perm_data(np);
    }
    mutex_unlock(&allowlist_mutex);
}
