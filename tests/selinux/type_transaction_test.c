/* SPDX-License-Identifier: GPL-2.0 */
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef uint32_t u32;
#define U32_MAX UINT32_MAX
#define GFP_KERNEL 0
#define SYM_TYPES 0
#define pr_warn(...) ((void)0)
#define pr_err(...) ((void)0)

static unsigned attempts, fail_at, live;
static void *allocations[2048];

static void *allocate(size_t size)
{
    if (++attempts == fail_at)
        return NULL;
    void *p = calloc(1, size);
    assert(p);
    for (size_t i = 0; i < 2048; ++i) {
        if (!allocations[i]) {
            allocations[i] = p;
            live++;
            return p;
        }
    }
    abort();
}

static void release(void *p)
{
    if (!p)
        return;
    for (size_t i = 0; i < 2048; ++i) {
        if (allocations[i] == p) {
            allocations[i] = NULL;
            live--;
            free(p);
            return;
        }
    }
    abort(); /* Double free or freeing an unowned/old pointer. */
}

#define kzalloc(size, flags) allocate(size)
#define kvcalloc(n, size, flags) allocate((n) * (size))
#define kfree(p) release(p)
#define kvfree(p) release(p)
static char *kstrdup(const char *s, int flags)
{
    (void)flags;
    char *p = allocate(strlen(s) + 1);
    if (p)
        strcpy(p, s);
    return p;
}

/* Sparse nodes force allocations for the new bit and midway through copies.
 * Like kernel ebitmap_cpy/destroy, failed copies free and reset the destination. */
struct bit { u32 index; struct bit *next; };
struct ebitmap { struct bit *node; };
static int ebitmap_set_bit(struct ebitmap *map, u32 bit, int value)
{
    assert(value == 1);
    for (struct bit *b = map->node; b; b = b->next)
        if (b->index == bit)
            return 0;
    struct bit *b = allocate(sizeof(*b));
    if (!b)
        return -1;
    b->index = bit;
    b->next = map->node;
    map->node = b;
    return 0;
}
static bool has_bit(const struct ebitmap *map, u32 bit)
{
    for (struct bit *b = map->node; b; b = b->next)
        if (b->index == bit)
            return true;
    return false;
}
static void ebitmap_destroy(struct ebitmap *map)
{
    while (map->node) {
        struct bit *b = map->node;
        map->node = b->next;
        release(b);
    }
}
static int ebitmap_cpy(struct ebitmap *dst, struct ebitmap *src)
{
    dst->node = NULL;
    for (struct bit *b = src->node; b; b = b->next) {
        if (ebitmap_set_bit(dst, b->index, 1)) {
            ebitmap_destroy(dst);
            return -1;
        }
    }
    return 0;
}

struct type_datum { u32 primary, value; bool attribute; };
struct symbol { char *key; struct type_datum *type; struct symbol *next; };
struct symtab { u32 nprim; struct symbol *head; };
struct role_datum { struct ebitmap types; };
struct policydb {
    struct symtab p_types, p_roles;
    struct ebitmap *type_attr_map_array;
    struct type_datum **type_val_to_struct;
    char **sym_val_to_name[1];
    struct role_datum **role_val_to_struct;
};
static struct type_datum *symtab_search(struct symtab *tab, const char *key)
{
    for (struct symbol *s = tab->head; s; s = s->next)
        if (!strcmp(s->key, key))
            return s->type;
    return NULL;
}
static int symtab_insert(struct symtab *tab, char *key, struct type_datum *type)
{
    assert(!symtab_search(tab, key));
    struct symbol *s = allocate(sizeof(*s));
    if (!s)
        return -1;
    *s = (struct symbol){key, type, tab->head};
    tab->head = s;
    return 0;
}

#include "production_type.inc"

static void destroy(struct policydb *db)
{
    for (u32 i = 0; i < db->p_types.nprim; ++i)
        ebitmap_destroy(&db->type_attr_map_array[i]);
    for (u32 i = 0; i < db->p_roles.nprim; ++i)
        ebitmap_destroy(&db->role_val_to_struct[i]->types);
    release(db->type_attr_map_array);
    release(db->type_val_to_struct);
    release(db->sym_val_to_name[SYM_TYPES]);
    while (db->p_types.head) {
        struct symbol *s = db->p_types.head;
        db->p_types.head = s->next;
        release(s->key);
        release(s->type);
        release(s);
    }
}

static struct policydb baseline(struct role_datum **roles)
{
    struct policydb db = {.p_roles = {.nprim = 2}, .role_val_to_struct = roles};
    assert(!ebitmap_set_bit(&roles[0]->types, 0, 1));
    assert(!ebitmap_set_bit(&roles[0]->types, 64, 1));
    assert(!ebitmap_set_bit(&roles[1]->types, 1, 1));
    assert(!ebitmap_set_bit(&roles[1]->types, 65, 1));
    assert(add_type(&db, "old_type", false));
    assert(add_type(&db, "old_attribute", true));
    return db;
}

int main(void)
{
    unsigned fault_count = 0;
    for (unsigned fault = 0; fault == 0 || fault <= fault_count; ++fault) {
        struct role_datum role[2] = {0};
        struct role_datum *roles[] = {&role[0], &role[1]};
        fail_at = 0;
        struct policydb db = baseline(roles);
        struct policydb before = db;
        struct role_datum before_roles[2] = {role[0], role[1]};
        struct ebitmap before_maps[2] = {db.type_attr_map_array[0], db.type_attr_map_array[1]};
        unsigned baseline_live = live;
        attempts = 0;
        fail_at = fault;
        bool ok = add_type(&db, "new_type", false);
        if (!fault) {
            assert(ok);
            fault_count = attempts;
        } else {
            assert(!ok && live == baseline_live);
            assert(!memcmp(&db, &before, sizeof(db)));
            assert(!memcmp(role, before_roles, sizeof(role)));
            assert(!memcmp(db.type_attr_map_array, before_maps, sizeof(before_maps)));
            assert(!symtab_search(&db.p_types, "new_type"));
            assert(!has_bit(&role[0].types, 2) && !has_bit(&role[1].types, 2));
            fail_at = 0;
            assert(add_type(&db, "new_type", false)); /* Retry the unchanged db. */
        }
        assert(db.p_types.nprim == 3);
        struct type_datum *type = symtab_search(&db.p_types, "new_type");
        assert(type && type->value == 3 && type->primary && !type->attribute);
        assert(db.type_val_to_struct[2] == type && !strcmp(db.sym_val_to_name[0][2], "new_type"));
        assert(has_bit(&db.type_attr_map_array[2], 2));
        assert(has_bit(&role[0].types, 0) && has_bit(&role[0].types, 64) && has_bit(&role[0].types, 2));
        assert(has_bit(&role[1].types, 1) && has_bit(&role[1].types, 65) && has_bit(&role[1].types, 2));
        unsigned old_attempts = attempts;
        assert(add_type(&db, "new_type", false) && attempts == old_attempts);
        fail_at = 0;
        assert(add_type(&db, "next_attribute", true));
        assert(db.p_types.nprim == 4 && symtab_search(&db.p_types, "next_attribute")->attribute);
        db.p_types.nprim = U32_MAX;
        old_attempts = attempts;
        assert(!add_type(&db, "overflow", false) && attempts == old_attempts);
        db.p_types.nprim = 4;
        destroy(&db);
        assert(live == 0);
    }
    struct policydb empty = {0};
    assert(add_type(&empty, "first", true) && empty.p_types.nprim == 1);
    destroy(&empty);
    assert(live == 0);
    printf("PASS: production add_type, %u allocation faults, unchanged ownership/counts on failure, retry, duplicate, overflow, zero roles\n", fault_count);
}
