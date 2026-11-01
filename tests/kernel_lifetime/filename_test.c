/* The real add_filename_trans(), with deterministic allocation failures. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define U32_MAX UINT32_MAX
#define GFP_KERNEL 0
#define pr_warn(...) ((void)0)
#define pr_err(...) ((void)0)
static unsigned attempts, fail_at, live;
static void *allocate(size_t size)
{
    if (++attempts == fail_at)
        return NULL;
    void *p = calloc(1, size);
    assert(p);
    live++;
    return p;
}
static void kfree(void *p) { if (p) { assert(live); live--; free(p); } }
#define kzalloc(size, flags) allocate(size)
static char *kstrdup(const char *s, int flags)
{
    char *p = allocate(strlen(s) + 1);
    if (p) strcpy(p, s);
    return p;
}
struct bit { unsigned index; struct bit *next; };
struct ebitmap { struct bit *node; };
static bool ebitmap_get_bit(const struct ebitmap *map, unsigned index)
{
    for (struct bit *b = map->node; b; b = b->next)
        if (b->index == index) return true;
    return false;
}
static int ebitmap_set_bit(struct ebitmap *map, unsigned index, int value)
{
    struct bit **p = &map->node;
    while (*p && (*p)->index != index) p = &(*p)->next;
    if (*p) {
        if (!value) { struct bit *b = *p; *p = b->next; kfree(b); }
        return 0;
    }
    if (!value) return 0;
    struct bit *b = allocate(sizeof(*b));
    if (!b) return -ENOMEM;
    b->index = index;
    *p = b;
    return 0;
}
static void ebitmap_destroy(struct ebitmap *map)
{
    while (map->node) ebitmap_set_bit(map, map->node->index, 0);
}
struct type_datum { unsigned value; };
struct class_datum { unsigned value; };
struct filename_trans_key { unsigned ttype, tclass; char *name; };
struct filename_trans_datum { struct ebitmap stypes; unsigned otype; struct filename_trans_datum *next; };
struct hashtab { struct filename_trans_key *key; struct filename_trans_datum *datum; void *node; };
struct policydb { int p_types, p_classes; struct hashtab filename_trans; struct ebitmap filename_trans_ttypes;
                  unsigned compat_filename_trans_count; };
static struct type_datum types[] = {{1}, {65}, {128}, {129}, {130}, {131}};
static struct class_datum cls = {1};
static void *symtab_search(int *tab, const char *s)
{
    const char *names[] = {"A", "B", "C", "target", "old", "new"};
    if (!strcmp(s, "file")) return &cls;
    for (unsigned i = 0; i < 6; i++) if (!strcmp(s, names[i])) return &types[i];
    return NULL;
}
static struct filename_trans_datum *policydb_filenametr_search(struct policydb *db, struct filename_trans_key *key)
{
    if (!db->filename_trans.key) return NULL;
    assert(!strcmp(db->filename_trans.key->name, key->name));
    return db->filename_trans.datum;
}
#define filenametr_key_params 0
static int hashtab_insert(struct hashtab *h, struct filename_trans_key *key, struct filename_trans_datum *d, int params)
{
    if (h->key) return -EEXIST;
    void *node = allocate(1);
    if (!node) return -ENOMEM;
    *h = (struct hashtab){key, d, node};
    return 0;
}
#include "filename.inc"
static bool add(struct policydb *db, const char *src, const char *dst)
{
    return add_filename_trans(db, src, "target", "file", dst, "name");
}
static unsigned lookup(struct policydb *db, unsigned bit)
{
    unsigned result = 0;
    for (struct filename_trans_datum *d = db->filename_trans.datum; d; d = d->next) {
        if (!ebitmap_get_bit(&d->stypes, bit)) continue;
        assert(!result); /* A source must never appear twice in the chain. */
        result = d->otype;
    }
    return result;
}
static void destroy(struct policydb *db)
{
    struct filename_trans_datum *d = db->filename_trans.datum;
    while (d) { struct filename_trans_datum *next = d->next; ebitmap_destroy(&d->stypes); kfree(d); d = next; }
    if (db->filename_trans.key) kfree(db->filename_trans.key->name);
    kfree(db->filename_trans.key);
    kfree(db->filename_trans.node);
    ebitmap_destroy(&db->filename_trans_ttypes);
    assert(!live);
}
int main(void)
{
    struct policydb db = {0};
    assert(add(&db, "A", "old") && add(&db, "B", "old"));
    assert(add(&db, "A", "new"));
    assert(lookup(&db, 0) == 131 && lookup(&db, 64) == 130);
    assert(db.compat_filename_trans_count == 2);
    assert(add(&db, "C", "new"));
    assert(add(&db, "A", "old")); /* Destination precedes old datum. */
    assert(lookup(&db, 0) == 130 && lookup(&db, 64) == 130 && lookup(&db, 127) == 131);
    assert(add(&db, "C", "old")); /* Empty source datum is still valid/reusable. */
    assert(add(&db, "A", "new") && add(&db, "A", "new"));
    assert(db.compat_filename_trans_count == 3);
    assert(ebitmap_get_bit(&db.filename_trans_ttypes, 129));
    destroy(&db);

    /* Every allocation failure on a new key, new output, and existing output. */
    for (int mode = 0; mode < 3; mode++) {
        unsigned allocations = 0;
        for (unsigned fault = 0; !fault || fault <= allocations; fault++) {
            db = (struct policydb){0};
            fail_at = 0;
            if (mode) { assert(add(&db, "A", "old")); assert(add(&db, "B", "old")); }
            if (mode == 2) assert(add(&db, "C", "new"));
            unsigned before = live, count = db.compat_filename_trans_count;
            attempts = 0; fail_at = fault;
            bool ok = add(&db, "A", "new");
            if (!fault) { assert(ok); allocations = attempts; }
            else {
                assert(!ok && live == before && db.compat_filename_trans_count == count);
                assert(lookup(&db, 0) == (mode ? 130U : 0U));
                if (mode) assert(lookup(&db, 64) == 130);
                if (!mode) assert(!db.filename_trans.key && !db.filename_trans_ttypes.node);
            }
            destroy(&db);
        }
    }
    /* A failed insert for a new name must retain another name's target bit. */
    for (unsigned fault = 1; fault <= 5; fault++) {
        db = (struct policydb){0}; fail_at = 0;
        assert(!ebitmap_set_bit(&db.filename_trans_ttypes, 129, 1));
        unsigned before = live;
        attempts = 0; fail_at = fault;
        assert(!add(&db, "A", "new"));
        assert(live == before && !db.filename_trans.key && ebitmap_get_bit(&db.filename_trans_ttypes, 129));
        destroy(&db);
    }
    puts("filename transitions: shared source isolation, merge, count and allocation rollback passed");
}
