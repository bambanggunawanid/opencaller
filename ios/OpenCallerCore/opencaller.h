// C ABI of the shared Rust core (crates/opencaller-ios). Used as the
// Swift bridging header for the app and both extensions.
//
// Ownership: oc_open returns an owned handle, freed with oc_close.
// An iterator from oc_iter_new MUST be freed with oc_iter_free BEFORE
// the database it came from is closed.
#ifndef OPENCALLER_H
#define OPENCALLER_H

#include <stdint.h>

typedef struct OcDb OcDb;
typedef struct OcIter OcIter;

// 1 when the shard's Ed25519 signature verifies against the pinned key.
int32_t oc_verify(const char *shard_path, const char *sig_path,
                  const char *pubkey_path);

// Opens a shard read-only (mmap). NULL on error. Does NOT verify.
OcDb *oc_open(const char *path);
void oc_close(OcDb *db);

uint64_t oc_entry_count(const OcDb *db);
// Build date, days since Unix epoch (0 for NULL db).
uint32_t oc_built_days(const OcDb *db);

// Looks up a dialable string (exact, then spam-block prefixes).
// Returns -1 on miss, else (category << 32) | report_count.
int64_t oc_lookup(const OcDb *db, const char *number);

// Streaming iteration over all entries in ascending number order.
OcIter *oc_iter_new(const OcDb *db);
int32_t oc_iter_next(OcIter *it, uint64_t *out_number, uint8_t *out_category,
                     uint16_t *out_count);
void oc_iter_free(OcIter *it);

#endif
