#include "ss/avtab.h"
#include "ss/constraint.h"
#include "ss/ebitmap.h"
#include "ss/hashtab.h"
#include "ss/policydb.h"
#include "ss/services.h"
#include <linux/gfp.h>
#include <linux/limits.h>
#include <linux/printk.h>
#include <linux/slab.h>
#include <linux/version.h>
#include <linux/vmalloc.h>

#include "sepolicy.h"
#include "klog.h" // IWYU pragma: keep
#include "ss/symtab.h"

#define KSU_SUPPORT_ADD_TYPE

//////////////////////////////////////////////////////
// Declaration
//////////////////////////////////////////////////////

static struct avtab_node *get_avtab_node(struct policydb *db, struct avtab_key *key,
                                         struct avtab_extended_perms *xperms);

static bool is_redundant_avtab_node(struct avtab_node *node);

static bool remove_avtab_node(struct policydb *db, struct avtab_node *node);

static bool add_rule(struct policydb *db, const char *s, const char *t, const char *c, const char *p, int effect,
                     bool invert);

static bool add_rule_raw(struct policydb *db, struct type_datum *src, struct type_datum *tgt, struct class_datum *cls,
                         struct perm_datum *perm, int effect, bool invert);

static bool add_xperm_rule_raw(struct policydb *db, struct type_datum *src, struct type_datum *tgt,
                               struct class_datum *cls, uint16_t low, uint16_t high, int effect, bool invert);
static bool add_xperm_rule(struct policydb *db, const char *s, const char *t, const char *c, const char *range,
                           int effect, bool invert);

static bool add_type_rule(struct policydb *db, const char *s, const char *t, const char *c, const char *d, int effect);

static bool add_filename_trans(struct policydb *db, const char *s, const char *t, const char *c, const char *d,
                               const char *o);

static bool add_genfscon(struct policydb *db, const char *fs_name, const char *path, const char *context);

static bool add_type(struct policydb *db, const char *type_name, bool attr);

static bool set_type_state(struct policydb *db, const char *type_name, bool permissive);

static bool add_typeattribute_raw(struct policydb *db, struct type_datum *type, struct type_datum *attr);

static bool add_typeattribute(struct policydb *db, const char *type, const char *attr);

//////////////////////////////////////////////////////
// Implementation
//////////////////////////////////////////////////////

// Invert is adding rules for auditdeny; in other cases, invert is removing
// rules
#define strip_av(effect, invert) ((effect == AVTAB_AUDITDENY) == !invert)

#define ksu_hash_for_each(node_ptr, n_slot, cur)                                                                       \
    int i;                                                                                                             \
    for (i = 0; i < n_slot; ++i)                                                                                       \
        for (cur = node_ptr[i]; cur; cur = cur->next)

// htable is a struct instead of pointer above 5.8.0:
// https://elixir.bootlin.com/linux/v5.8-rc1/source/security/selinux/ss/symtab.h
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 8, 0)
#define ksu_hashtab_for_each(htab, cur) ksu_hash_for_each(htab.htable, htab.size, cur)
#else
#define ksu_hashtab_for_each(htab, cur) ksu_hash_for_each(htab->htable, htab->size, cur)
#endif

// symtab_search is introduced on 5.9.0:
// https://elixir.bootlin.com/linux/v5.9-rc1/source/security/selinux/ss/symtab.h
#if LINUX_VERSION_CODE < KERNEL_VERSION(5, 9, 0)
#define symtab_search(s, name) hashtab_search((s)->table, name)
#define symtab_insert(s, name, datum) hashtab_insert((s)->table, name, datum)
#endif

#define avtab_for_each(avtab, cur) ksu_hash_for_each(avtab.htable, avtab.nslot, cur)

static struct avtab_node *get_avtab_node(struct policydb *db, struct avtab_key *key,
                                         struct avtab_extended_perms *xperms)
{
    struct avtab_node *node;

    /* AVTAB_XPERMS entries are not necessarily unique */
    if (key->specified & AVTAB_XPERMS) {
        bool match = false;
        node = avtab_search_node(&db->te_avtab, key);
        while (node) {
            if ((node->datum.u.xperms->specified == xperms->specified) &&
                (node->datum.u.xperms->driver == xperms->driver)) {
                match = true;
                break;
            }
            node = avtab_search_node_next(node, key->specified);
        }
        if (!match)
            node = NULL;
    } else {
        node = avtab_search_node(&db->te_avtab, key);
    }

    if (!node) {
        struct avtab_datum avdatum = {};
        /*
     * AUDITDENY, aka DONTAUDIT, are &= assigned, versus |= for
     * others. Initialize the data accordingly.
     */
        if (key->specified & AVTAB_XPERMS) {
            avdatum.u.xperms = xperms;
        } else {
            avdatum.u.data = key->specified == AVTAB_AUDITDENY ? ~0U : 0U;
        }
        /* this is used to get the node - insertion is actually unique */
        node = avtab_insert_nonunique(&db->te_avtab, key, &avdatum);
        if (!node)
            return NULL;

        // extra size: add_type() can grow policy without updating db->len
        int grow_size = sizeof(struct avtab_key);
        grow_size += sizeof(struct avtab_datum);
        if (key->specified & AVTAB_XPERMS) {
            grow_size += sizeof(avdatum.u.xperms->specified) + sizeof(avdatum.u.xperms->driver) +
                         sizeof(avdatum.u.xperms->perms.p);
        }
        db->len += grow_size;
    }

    return node;
}

static bool is_redundant_avtab_node(struct avtab_node *node)
{
    if (node->key.specified & AVTAB_XPERMS)
        return node->datum.u.xperms == NULL;
    if (!(node->key.specified & AVTAB_AV))
        return false;
    if (node->key.specified & AVTAB_AUDITDENY)
        return node->datum.u.data == ~0U;
    return node->datum.u.data == 0U;
}

static bool remove_avtab_node(struct policydb *db, struct avtab_node *node)
{
    int i;
    int ret;
    // https://github.com/torvalds/linux/blob/v6.1/security/selinux/ss/avtab.c#L619
    int shrink_size = sizeof(node->key.source_type) + sizeof(node->key.target_type) + sizeof(node->key.target_class) +
                      sizeof(node->key.specified);
    struct avtab removed = {};
    struct avtab_node *n;
    struct avtab_node *prev;

    ret = avtab_alloc(&removed, 1);
    if (ret < 0)
        return false;

    for (i = 0; i < db->te_avtab.nslot; i++) {
        prev = NULL;
        for (n = db->te_avtab.htable[i]; n; prev = n, n = n->next) {
            if (n != node)
                continue;

            if (prev)
                prev->next = n->next;
            else
                db->te_avtab.htable[i] = n->next;

            if (db->te_avtab.nel > 0)
                db->te_avtab.nel--;

            if (n->key.specified & AVTAB_XPERMS)
                // specified and driver are u8, perms.p holds 8 u32s
                shrink_size += sizeof(n->datum.u.xperms->specified) + sizeof(n->datum.u.xperms->driver) +
                               sizeof(n->datum.u.xperms->perms.p);
            else
                // data is u32
                shrink_size += sizeof(n->datum.u.data);
            n->next = NULL;
            removed.htable[0] = n;
            removed.nel = 1;
            avtab_destroy(&removed);
            if (db->len >= shrink_size)
                db->len -= shrink_size;
            return true;
        }
    }

    avtab_destroy(&removed);
    return false;
}

static bool add_rule(struct policydb *db, const char *s, const char *t, const char *c, const char *p, int effect,
                     bool invert)
{
    struct type_datum *src = NULL, *tgt = NULL;
    struct class_datum *cls = NULL;
    struct perm_datum *perm = NULL;

    if (s) {
        src = symtab_search(&db->p_types, s);
        if (src == NULL) {
            pr_info("source type %s does not exist\n", s);
            return false;
        }
    }

    if (t) {
        tgt = symtab_search(&db->p_types, t);
        if (tgt == NULL) {
            pr_info("target type %s does not exist\n", t);
            return false;
        }
    }

    if (c) {
        cls = symtab_search(&db->p_classes, c);
        if (cls == NULL) {
            pr_info("class %s does not exist\n", c);
            return false;
        }
    }

    if (p) {
        if (c == NULL) {
            pr_info("No class is specified, cannot add perm [%s] \n", p);
            return false;
        }

        perm = symtab_search(&cls->permissions, p);
        if (perm == NULL && cls->comdatum != NULL) {
            perm = symtab_search(&cls->comdatum->permissions, p);
        }
        if (perm == NULL) {
            pr_info("perm %s does not exist in class %s\n", p, c);
            return false;
        }
    }
    return add_rule_raw(db, src, tgt, cls, perm, effect, invert);
}

static bool add_rule_raw(struct policydb *db, struct type_datum *src, struct type_datum *tgt, struct class_datum *cls,
                         struct perm_datum *perm, int effect, bool invert)
{
    bool success = true;

    if (src == NULL) {
        struct hashtab_node *node;
        if (strip_av(effect, invert)) {
            ksu_hashtab_for_each(db->p_types.table, node)
            {
                success &= add_rule_raw(db, (struct type_datum *)node->datum, tgt, cls, perm, effect, invert);
            };
        } else {
            ksu_hashtab_for_each(db->p_types.table, node)
            {
                struct type_datum *type = (struct type_datum *)(node->datum);
                if (type->attribute) {
                    success &= add_rule_raw(db, type, tgt, cls, perm, effect, invert);
                }
            };
        }
    } else if (tgt == NULL) {
        struct hashtab_node *node;
        if (strip_av(effect, invert)) {
            ksu_hashtab_for_each(db->p_types.table, node)
            {
                success &= add_rule_raw(db, src, (struct type_datum *)node->datum, cls, perm, effect, invert);
            };
        } else {
            ksu_hashtab_for_each(db->p_types.table, node)
            {
                struct type_datum *type = (struct type_datum *)(node->datum);
                if (type->attribute) {
                    success &= add_rule_raw(db, src, type, cls, perm, effect, invert);
                }
            };
        }
    } else if (cls == NULL) {
        struct hashtab_node *node;
        ksu_hashtab_for_each(db->p_classes.table, node)
        {
            success &= add_rule_raw(db, src, tgt, (struct class_datum *)node->datum, perm, effect, invert);
        }
    } else {
        struct avtab_key key;
        struct avtab_node *node;

        key.source_type = src->value;
        key.target_type = tgt->value;
        key.target_class = cls->value;
        key.specified = effect;

        if (invert && effect != AVTAB_AUDITDENY) {
            node = avtab_search_node(&db->te_avtab, &key);
            if (!node)
                return true;
        } else {
            node = get_avtab_node(db, &key, NULL);
            if (!node)
                return false;
        }

        if (invert) {
            if (perm)
                node->datum.u.data &= ~(1U << (perm->value - 1));
            else
                node->datum.u.data = 0U;
        } else {
            if (perm)
                node->datum.u.data |= 1U << (perm->value - 1);
            else
                node->datum.u.data = ~0U;
        }
        if (is_redundant_avtab_node(node))
            return remove_avtab_node(db, node);
    }

    return success;
}

#define ioctl_driver(x) (x >> 8 & 0xFF)
#define ioctl_func(x) (x & 0xFF)

#define xperm_test(x, p) (1 & (p[x >> 5] >> (x & 0x1f)))
#define xperm_set(x, p) (p[x >> 5] |= (1 << (x & 0x1f)))
#define xperm_clear(x, p) (p[x >> 5] &= ~(1 << (x & 0x1f)))

static bool add_xperm_rule_raw(struct policydb *db, struct type_datum *src, struct type_datum *tgt,
                               struct class_datum *cls, uint16_t low, uint16_t high, int effect, bool invert)
{
    if (src == NULL) {
        struct hashtab_node *node;
        ksu_hashtab_for_each(db->p_types.table, node)
        {
            struct type_datum *type = (struct type_datum *)(node->datum);
            if (type->attribute) {
                if (!add_xperm_rule_raw(db, type, tgt, cls, low, high, effect, invert))
                    return false;
            }
        };
    } else if (tgt == NULL) {
        struct hashtab_node *node;
        ksu_hashtab_for_each(db->p_types.table, node)
        {
            struct type_datum *type = (struct type_datum *)(node->datum);
            if (type->attribute) {
                if (!add_xperm_rule_raw(db, src, type, cls, low, high, effect, invert))
                    return false;
            }
        };
    } else if (cls == NULL) {
        struct hashtab_node *node;
        ksu_hashtab_for_each(db->p_classes.table, node)
        {
            if (!add_xperm_rule_raw(db, src, tgt, (struct class_datum *)(node->datum), low, high, effect, invert))
                return false;
        };
    } else {
        struct avtab_key key;
        key.source_type = src->value;
        key.target_type = tgt->value;
        key.target_class = cls->value;
        key.specified = effect;

        struct avtab_datum *datum;
        struct avtab_node *node;
        struct avtab_extended_perms xperms;

        memset(&xperms, 0, sizeof(xperms));
        if (ioctl_driver(low) != ioctl_driver(high)) {
            xperms.specified = AVTAB_XPERMS_IOCTLDRIVER;
            xperms.driver = 0;
        } else {
            xperms.specified = AVTAB_XPERMS_IOCTLFUNCTION;
            xperms.driver = ioctl_driver(low);
        }
        int i;
        if (xperms.specified == AVTAB_XPERMS_IOCTLDRIVER) {
            for (i = ioctl_driver(low); i <= ioctl_driver(high); ++i) {
                if (invert)
                    xperm_clear(i, xperms.perms.p);
                else
                    xperm_set(i, xperms.perms.p);
            }
        } else {
            for (i = ioctl_func(low); i <= ioctl_func(high); ++i) {
                if (invert)
                    xperm_clear(i, xperms.perms.p);
                else
                    xperm_set(i, xperms.perms.p);
            }
        }

        node = get_avtab_node(db, &key, &xperms);
        if (!node) {
            pr_warn("add_xperm_rule_raw cannot found node!\n");
            return false;
        }
        datum = &node->datum;

        // Allow updating permission bits of existing xperms
        for (i = 0; i < ARRAY_SIZE(xperms.perms.p); i++)
            datum->u.xperms->perms.p[i] |= xperms.perms.p[i];
    }
    return true;
}

static bool add_xperm_rule(struct policydb *db, const char *s, const char *t, const char *c, const char *range,
                           int effect, bool invert)
{
    struct type_datum *src = NULL, *tgt = NULL;
    struct class_datum *cls = NULL;

    if (s) {
        src = symtab_search(&db->p_types, s);
        if (src == NULL) {
            pr_info("source type %s does not exist\n", s);
            return false;
        }
    }

    if (t) {
        tgt = symtab_search(&db->p_types, t);
        if (tgt == NULL) {
            pr_info("target type %s does not exist\n", t);
            return false;
        }
    }

    if (c) {
        cls = symtab_search(&db->p_classes, c);
        if (cls == NULL) {
            pr_info("class %s does not exist\n", c);
            return false;
        }
    }

    u16 low, high;

    if (range) {
        if (strchr(range, '-')) {
            sscanf(range, "%hx-%hx", &low, &high);
        } else {
            sscanf(range, "%hx", &low);
            high = low;
        }
    } else {
        low = 0;
        high = 0xFFFF;
    }

    return add_xperm_rule_raw(db, src, tgt, cls, low, high, effect, invert);
}

static bool add_type_rule(struct policydb *db, const char *s, const char *t, const char *c, const char *d, int effect)
{
    struct type_datum *src, *tgt, *def;
    struct class_datum *cls;

    src = symtab_search(&db->p_types, s);
    if (src == NULL) {
        pr_info("source type %s does not exist\n", s);
        return false;
    }
    tgt = symtab_search(&db->p_types, t);
    if (tgt == NULL) {
        pr_info("target type %s does not exist\n", t);
        return false;
    }
    cls = symtab_search(&db->p_classes, c);
    if (cls == NULL) {
        pr_info("class %s does not exist\n", c);
        return false;
    }
    def = symtab_search(&db->p_types, d);
    if (def == NULL) {
        pr_info("default type %s does not exist\n", d);
        return false;
    }

    struct avtab_key key;
    key.source_type = src->value;
    key.target_type = tgt->value;
    key.target_class = cls->value;
    key.specified = effect;

    struct avtab_node *node = get_avtab_node(db, &key, NULL);
    if (!node)
        return false;
    node->datum.u.data = def->value;

    return true;
}

// 5.9.0 : static inline int hashtab_insert(struct hashtab *h, void *key, void
// *datum, struct hashtab_key_params key_params) 5.8.0: int
// hashtab_insert(struct hashtab *h, void *k, void *d);
#if LINUX_VERSION_CODE >= KERNEL_VERSION(5, 9, 0)
static u32 filenametr_hash(const void *k)
{
    const struct filename_trans_key *ft = k;
    unsigned long hash;
    unsigned int byte_num;
    unsigned char focus;

    hash = ft->ttype ^ ft->tclass;

    byte_num = 0;
    while ((focus = ft->name[byte_num++]))
        hash = partial_name_hash(focus, hash);
    return hash;
}

static int filenametr_cmp(const void *k1, const void *k2)
{
    const struct filename_trans_key *ft1 = k1;
    const struct filename_trans_key *ft2 = k2;
    int v;

    v = ft1->ttype - ft2->ttype;
    if (v)
        return v;

    v = ft1->tclass - ft2->tclass;
    if (v)
        return v;

    return strcmp(ft1->name, ft2->name);
}

static const struct hashtab_key_params filenametr_key_params = {
    .hash = filenametr_hash,
    .cmp = filenametr_cmp,
};
#endif

static bool add_filename_trans(struct policydb *db, const char *s, const char *t, const char *c, const char *d,
                               const char *o)
{
    struct type_datum *src, *tgt, *def;
    struct class_datum *cls;
    struct filename_trans_key *new_key = NULL;
    struct filename_trans_datum *old = NULL, *dest = NULL, *last = NULL, *trans;
    bool had_ttype;
    int rc;

    src = symtab_search(&db->p_types, s);
    if (src == NULL) {
        pr_warn("source type %s does not exist\n", s);
        return false;
    }
    tgt = symtab_search(&db->p_types, t);
    if (tgt == NULL) {
        pr_warn("target type %s does not exist\n", t);
        return false;
    }
    cls = symtab_search(&db->p_classes, c);
    if (cls == NULL) {
        pr_warn("class %s does not exist\n", c);
        return false;
    }
    def = symtab_search(&db->p_types, d);
    if (def == NULL) {
        pr_warn("default type %s does not exist\n", d);
        return false;
    }

    struct filename_trans_key key;
    key.ttype = tgt->value;
    key.tclass = cls->value;
    key.name = (char *)o;

    /* Search the whole chain: the destination can precede the old source's
     * datum. Each datum's otype applies to every bit in its stypes bitmap.
     */
    for (trans = policydb_filenametr_search(db, &key); trans; trans = trans->next) {
        if (ebitmap_get_bit(&trans->stypes, src->value - 1))
            old = trans;
        if (trans->otype == def->value)
            dest = trans;
        last = trans;
    }
    if (old && old == dest)
        return true;
    if (!old && db->compat_filename_trans_count == U32_MAX)
        return false;

    if (dest) {
        if (ebitmap_set_bit(&dest->stypes, src->value - 1, 1))
            return false;
    } else {
        trans = kzalloc(sizeof(*trans), GFP_KERNEL);
        if (!trans)
            return false;
        trans->otype = def->value;
        if (ebitmap_set_bit(&trans->stypes, src->value - 1, 1))
            goto free_trans;
        if (last) {
            /* The key is already in the hash table; append, never reinsert. */
            last->next = trans;
        } else {
            new_key = kzalloc(sizeof(*new_key), GFP_KERNEL);
            if (!new_key)
                goto free_trans;
            *new_key = key;
            new_key->name = kstrdup(key.name, GFP_KERNEL);
            if (!new_key->name)
                goto free_key;
            had_ttype = ebitmap_get_bit(&db->filename_trans_ttypes, key.ttype);
            if (ebitmap_set_bit(&db->filename_trans_ttypes, key.ttype, 1))
                goto free_name;
            rc = hashtab_insert(&db->filename_trans, new_key, trans, filenametr_key_params);
            if (rc) {
                if (!had_ttype)
                    ebitmap_set_bit(&db->filename_trans_ttypes, key.ttype, 0);
                goto free_name;
            }
        }
    }

    /* All fallible operations are complete. Clearing an existing bit cannot
     * allocate. Empty source bitmaps are valid policydb datums and reusable.
     */
    if (old)
        ebitmap_set_bit(&old->stypes, src->value - 1, 0);
    else
        db->compat_filename_trans_count++;
    return true;

free_name:
    kfree(new_key->name);
free_key:
    kfree(new_key);
free_trans:
    ebitmap_destroy(&trans->stypes);
    kfree(trans);
    return false;
}

static bool add_genfscon(struct policydb *db, const char *fs_name, const char *path, const char *context)
{
    return false;
}

static bool add_type(struct policydb *db, const char *type_name, bool attr)
{
    struct type_datum *type = symtab_search(&db->p_types, type_name);
    struct ebitmap *new_type_attr_map_array = NULL, *role_types = NULL;
    struct type_datum **new_type_val_to_struct = NULL;
    char **new_val_to_name_types = NULL;
    char *key = NULL;
    u32 old_count = db->p_types.nprim, value;
    u32 i;

    if (type) {
        pr_warn("Type %s already exists\n", type_name);
        return true;
    }
    if (old_count == U32_MAX)
        return false;
    value = old_count + 1;

    /* Prepare everything without touching db. In particular, realloc can free
     * an old array before a later allocation fails. The old bitmap nodes stay
     * owned by db; only the new slot and the role copies are owned here. */
    type = kzalloc(sizeof(*type), GFP_KERNEL);
    if (!type)
        goto out;
    type->primary = 1;
    type->value = value;
    type->attribute = attr;

    key = kstrdup(type_name, GFP_KERNEL);
    if (!key)
        goto out;
    new_type_attr_map_array = kvcalloc(value, sizeof(*new_type_attr_map_array), GFP_KERNEL);
    new_type_val_to_struct = kvcalloc(value, sizeof(*new_type_val_to_struct), GFP_KERNEL);
    new_val_to_name_types = kvcalloc(value, sizeof(*new_val_to_name_types), GFP_KERNEL);
    if (!new_type_attr_map_array || !new_type_val_to_struct || !new_val_to_name_types)
        goto out;

    if (old_count) {
        memcpy(new_type_attr_map_array, db->type_attr_map_array, old_count * sizeof(*new_type_attr_map_array));
        memcpy(new_type_val_to_struct, db->type_val_to_struct, old_count * sizeof(*new_type_val_to_struct));
        memcpy(new_val_to_name_types, db->sym_val_to_name[SYM_TYPES], old_count * sizeof(*new_val_to_name_types));
    }
    if (ebitmap_set_bit(&new_type_attr_map_array[old_count], old_count, 1))
        goto out;
    new_type_val_to_struct[old_count] = type;
    new_val_to_name_types[old_count] = key;

    if (db->p_roles.nprim) {
        role_types = kvcalloc(db->p_roles.nprim, sizeof(*role_types), GFP_KERNEL);
        if (!role_types)
            goto out;
        for (i = 0; i < db->p_roles.nprim; ++i) {
            if (ebitmap_cpy(&role_types[i], &db->role_val_to_struct[i]->types) ||
                ebitmap_set_bit(&role_types[i], old_count, 1))
                goto out;
        }
    }

    /* Last fallible operation. On success the symbol table owns key/type and
     * the following pointer/count swaps cannot fail. */
    if (symtab_insert(&db->p_types, key, type))
        goto out;

    kvfree(db->type_attr_map_array);
    db->type_attr_map_array = new_type_attr_map_array;
    kvfree(db->type_val_to_struct);
    db->type_val_to_struct = new_type_val_to_struct;
    kvfree(db->sym_val_to_name[SYM_TYPES]);
    db->sym_val_to_name[SYM_TYPES] = new_val_to_name_types;
    for (i = 0; i < db->p_roles.nprim; ++i) {
        ebitmap_destroy(&db->role_val_to_struct[i]->types);
        db->role_val_to_struct[i]->types = role_types[i];
    }
    db->p_types.nprim = value;
    kvfree(role_types);
    return true;

out:
    if (role_types) {
        for (i = 0; i < db->p_roles.nprim; ++i)
            ebitmap_destroy(&role_types[i]);
    }
    kvfree(role_types);
    if (new_type_attr_map_array)
        ebitmap_destroy(&new_type_attr_map_array[old_count]);
    kvfree(new_type_attr_map_array);
    kvfree(new_type_val_to_struct);
    kvfree(new_val_to_name_types);
    kfree(key);
    kfree(type);
    pr_err("add_type: failed to prepare type %s\n", type_name);
    return false;
}

static bool set_type_state(struct policydb *db, const char *type_name, bool permissive)
{
    struct type_datum *type;
    if (type_name == NULL) {
        struct hashtab_node *node;
        ksu_hashtab_for_each(db->p_types.table, node)
        {
            type = (struct type_datum *)(node->datum);
            if (ebitmap_set_bit(&db->permissive_map, type->value, permissive))
                pr_info("Could not set bit in permissive map\n");
        };
    } else {
        type = (struct type_datum *)symtab_search(&db->p_types, type_name);
        if (type == NULL) {
            pr_info("type %s does not exist\n", type_name);
            return false;
        }
        if (ebitmap_set_bit(&db->permissive_map, type->value, permissive)) {
            pr_info("Could not set bit in permissive map\n");
            return false;
        }
    }
    return true;
}

static bool add_typeattribute_raw(struct policydb *db, struct type_datum *type, struct type_datum *attr)
{
    struct ebitmap *sattr = &db->type_attr_map_array[type->value - 1];
    if (ebitmap_set_bit(sattr, attr->value - 1, 1))
        return false;

    struct hashtab_node *node;
    struct constraint_node *n;
    struct constraint_expr *e;
    ksu_hashtab_for_each(db->p_classes.table, node)
    {
        struct class_datum *cls = (struct class_datum *)(node->datum);
        for (n = cls->constraints; n; n = n->next) {
            for (e = n->expr; e; e = e->next) {
                if (e->expr_type == CEXPR_NAMES && ebitmap_get_bit(&e->type_names->types, attr->value - 1)) {
                    if (ebitmap_set_bit(&e->names, type->value - 1, 1))
                        return false;
                }
            }
        }
    };
    return true;
}

static bool add_typeattribute(struct policydb *db, const char *type, const char *attr)
{
    struct type_datum *type_d = symtab_search(&db->p_types, type);
    if (type_d == NULL) {
        pr_info("type %s does not exist\n", type);
        return false;
    } else if (type_d->attribute) {
        pr_info("type %s is an attribute\n", attr);
        return false;
    }

    struct type_datum *attr_d = symtab_search(&db->p_types, attr);
    if (attr_d == NULL) {
        pr_info("attribute %s does not exist\n", type);
        return false;
    } else if (!attr_d->attribute) {
        pr_info("type %s is not an attribute \n", attr);
        return false;
    }

    return add_typeattribute_raw(db, type_d, attr_d);
}

//////////////////////////////////////////////////////////////////////////

// Operation on types
bool ksu_type(struct policydb *db, const char *name, const char *attr)
{
    return add_type(db, name, false) && add_typeattribute(db, name, attr);
}

bool ksu_attribute(struct policydb *db, const char *name)
{
    return add_type(db, name, true);
}

bool ksu_permissive(struct policydb *db, const char *type)
{
    return set_type_state(db, type, true);
}

bool ksu_enforce(struct policydb *db, const char *type)
{
    return set_type_state(db, type, false);
}

bool ksu_typeattribute(struct policydb *db, const char *type, const char *attr)
{
    return add_typeattribute(db, type, attr);
}

bool ksu_exists(struct policydb *db, const char *type)
{
    return symtab_search(&db->p_types, type) != NULL;
}

// Access vector rules
bool ksu_allow(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *perm)
{
    return add_rule(db, src, tgt, cls, perm, AVTAB_ALLOWED, false);
}

bool ksu_deny(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *perm)
{
    return add_rule(db, src, tgt, cls, perm, AVTAB_ALLOWED, true);
}

bool ksu_auditallow(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *perm)
{
    return add_rule(db, src, tgt, cls, perm, AVTAB_AUDITALLOW, false);
}
bool ksu_dontaudit(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *perm)
{
    return add_rule(db, src, tgt, cls, perm, AVTAB_AUDITDENY, true);
}

// Extended permissions access vector rules
bool ksu_allowxperm(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *range)
{
    return add_xperm_rule(db, src, tgt, cls, range, AVTAB_XPERMS_ALLOWED, false);
}

bool ksu_auditallowxperm(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *range)
{
    return add_xperm_rule(db, src, tgt, cls, range, AVTAB_XPERMS_AUDITALLOW, false);
}

bool ksu_dontauditxperm(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *range)
{
    return add_xperm_rule(db, src, tgt, cls, range, AVTAB_XPERMS_DONTAUDIT, false);
}

// Type rules
bool ksu_type_transition(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *def,
                         const char *obj)
{
    if (obj) {
        return add_filename_trans(db, src, tgt, cls, def, obj);
    } else {
        return add_type_rule(db, src, tgt, cls, def, AVTAB_TRANSITION);
    }
}

bool ksu_type_change(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *def)
{
    return add_type_rule(db, src, tgt, cls, def, AVTAB_CHANGE);
}

bool ksu_type_member(struct policydb *db, const char *src, const char *tgt, const char *cls, const char *def)
{
    return add_type_rule(db, src, tgt, cls, def, AVTAB_MEMBER);
}

// File system labeling
bool ksu_genfscon(struct policydb *db, const char *fs_name, const char *path, const char *ctx)
{
    return add_genfscon(db, fs_name, path, ctx);
}

// ======== sepolicy ========

void ksu_destroy_sepolicy(struct selinux_policy *pol)
{
    policydb_destroy(&pol->policydb);
    kfree(pol);
}

struct selinux_policy *ksu_dup_sepolicy(struct selinux_policy *old_pol)
{
    int ret;
    size_t len;
    struct selinux_policy *new_pol;
    void *data;
    struct policy_file fp;

    // Some device policy db seems not marking type itself in type_attr_map_array
    // policydb_read() adds each type to its own attribute map, so old_pol->policydb.len may be smaller
    // preserve one ebitmap entry for this condition to avoid trigger -EINVAL
    len = old_pol->policydb.len + (size_t)old_pol->policydb.p_types.nprim * (sizeof(u32) + sizeof(u64));

    data = vmalloc(len);
    if (!data) {
        pr_err("alloc policy buffer len %zu\n", len);
        ret = -ENOMEM;
        goto out_free_data;
    }

    fp.data = data;
    fp.len = len;

    ret = policydb_write(&old_pol->policydb, &fp);
    if (ret) {
        pr_err("sepolicy: policydb_write: %d\n", ret);
        goto out_free_data;
    }
    len -= fp.len;
    // https://android.googlesource.com/kernel/common/+/35a7845718734ae638b85b420534cb859498dab6%5E%21
#if LINUX_VERSION_CODE < KERNEL_VERSION(6, 18, 0)
    // https://android-review.googlesource.com/c/kernel/common/+/3009995/11/security/selinux/ss/policydb.c
    // fixup config
    // 4*2+8+4
    static const size_t kConfigOff = 20;
    if (len >= kConfigOff + sizeof(u32)) {
        u32 *config_ptr = (u32 *)((unsigned long)data + kConfigOff);
        pr_info("old config: %u\n", *config_ptr);
        if (old_pol->policydb.android_netlink_route) {
            pr_info("adding POLICYDB_CONFIG_ANDROID_NETLINK_ROUTE\n");
            *config_ptr |= POLICYDB_CONFIG_ANDROID_NETLINK_ROUTE;
        }
        if (old_pol->policydb.android_netlink_getneigh) {
            pr_info("adding POLICYDB_CONFIG_ANDROID_NETLINK_GETNEIGH\n");
            *config_ptr |= POLICYDB_CONFIG_ANDROID_NETLINK_GETNEIGH;
        }
        pr_info("new config: %u\n", *config_ptr);
    }
#endif
    new_pol = kmemdup(old_pol, sizeof(*old_pol), GFP_KERNEL);
    if (!new_pol) {
        ret = -ENOMEM;
        pr_err("sepolicy: dup old pol\n");
        goto out_free_data;
    }
    memset(&new_pol->policydb, 0, sizeof(new_pol->policydb));

    // rewind fp
    fp.data = data;
    fp.len = len;

    ret = policydb_read(&new_pol->policydb, &fp);
    if (ret) {
        pr_err("sepolicy: policydb_read: %d\n", ret);
        goto out_free_policydb;
    }
    new_pol->policydb.len = len;
    kvfree(data);

    return new_pol;

out_free_policydb:
    kfree(new_pol);

out_free_data:
    kvfree(data);

    return ERR_PTR(ret);
}
