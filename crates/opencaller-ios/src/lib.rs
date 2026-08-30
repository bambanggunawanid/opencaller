//! C ABI over the shared Rust core for the iOS app and its extensions
//! (declared in `ios/OpenCallerCore/opencaller.h`). Same trust rules as
//! Android: shards verify against the pinned Ed25519 key before use, and
//! parsing degrades to a miss, never a crash — the CallKit extension runs
//! under a strict memory/time budget and must not take the app down.
//!
//! Ownership contract (documented in the header): `oc_open` returns an
//! owned handle freed by `oc_close`; an iterator from `oc_iter_new` must
//! be freed with `oc_iter_free` BEFORE the database it came from is
//! closed.

use std::ffi::CStr;
use std::os::raw::c_char;
use std::path::Path;

use opencaller_core::db::{parse_number, EntryIter, SpamDb};
use opencaller_core::update::verify_shard;

pub struct OcDb {
  db: SpamDb,
}

pub struct OcIter {
  // SAFETY: the iterator borrows the SpamDb inside the OcDb box. The C
  // caller guarantees (header contract) that the iterator is freed before
  // the database; the OcDb box never moves after oc_open returns it.
  iter: EntryIter<'static>,
}

unsafe fn cstr_path<'a>(p: *const c_char) -> Option<&'a Path> {
  if p.is_null() {
    return None;
  }
  CStr::from_ptr(p).to_str().ok().map(Path::new)
}

/// 1 when the shard's signature verifies against the pinned public key.
#[no_mangle]
pub unsafe extern "C" fn oc_verify(
  shard_path: *const c_char,
  sig_path: *const c_char,
  pubkey_path: *const c_char,
) -> i32 {
  let (Some(shard), Some(sig), Some(pubkey)) = (
    cstr_path(shard_path),
    cstr_path(sig_path),
    cstr_path(pubkey_path),
  ) else {
    return 0;
  };
  let (Ok(shard), Ok(sig), Ok(pubkey)) = (
    std::fs::read(shard),
    std::fs::read(sig),
    std::fs::read(pubkey),
  ) else {
    return 0;
  };
  verify_shard(&shard, &sig, &pubkey) as i32
}

/// Opens a shard read-only (mmap). Returns null on any error.
/// NOTE: does NOT verify — call `oc_verify` first, exactly like Android.
#[no_mangle]
pub unsafe extern "C" fn oc_open(path: *const c_char) -> *mut OcDb {
  let Some(path) = cstr_path(path) else {
    return std::ptr::null_mut();
  };
  match SpamDb::open(path) {
    Ok(db) => Box::into_raw(Box::new(OcDb { db })),
    Err(_) => std::ptr::null_mut(),
  }
}

#[no_mangle]
pub unsafe extern "C" fn oc_close(db: *mut OcDb) {
  if !db.is_null() {
    drop(Box::from_raw(db));
  }
}

#[no_mangle]
pub unsafe extern "C" fn oc_entry_count(db: *const OcDb) -> u64 {
  db.as_ref().map_or(0, |d| d.db.len())
}

/// Build date as days since the Unix epoch (0 when db is null).
#[no_mangle]
pub unsafe extern "C" fn oc_built_days(db: *const OcDb) -> u32 {
  db.as_ref().map_or(0, |d| u32::from(d.db.built_days()))
}

/// Looks up a dialable string (exact match, then spam-block prefixes).
/// Returns -1 on miss, else `(category << 32) | report_count`.
#[no_mangle]
pub unsafe extern "C" fn oc_lookup(db: *const OcDb, number: *const c_char) -> i64 {
  let (Some(d), false) = (db.as_ref(), number.is_null()) else {
    return -1;
  };
  let Ok(s) = CStr::from_ptr(number).to_str() else {
    return -1;
  };
  let Some(n) = parse_number(s) else {
    return -1;
  };
  match d.db.lookup(n) {
    Some(info) => (i64::from(info.category as u8) << 32) | i64::from(info.report_count),
    None => -1,
  }
}

/// Streaming iterator over all entries in ascending number order — the
/// order `CXCallDirectory` requires. Free with `oc_iter_free` before
/// closing the database.
#[no_mangle]
pub unsafe extern "C" fn oc_iter_new(db: *const OcDb) -> *mut OcIter {
  let Some(d) = db.as_ref() else {
    return std::ptr::null_mut();
  };
  let iter: EntryIter<'_> = d.db.iter_entries();
  // SAFETY: lifetime extended per the header contract (iterator freed
  // before its database; the OcDb allocation is stable).
  let iter: EntryIter<'static> = std::mem::transmute(iter);
  Box::into_raw(Box::new(OcIter { iter }))
}

/// Writes the next entry into the out-params and returns 1, or returns 0
/// when exhausted.
#[no_mangle]
pub unsafe extern "C" fn oc_iter_next(
  it: *mut OcIter,
  out_number: *mut u64,
  out_category: *mut u8,
  out_count: *mut u16,
) -> i32 {
  let Some(it) = it.as_mut() else {
    return 0;
  };
  match it.iter.next() {
    Some(e) => {
      if !out_number.is_null() {
        *out_number = e.number;
      }
      if !out_category.is_null() {
        *out_category = e.category as u8;
      }
      if !out_count.is_null() {
        *out_count = e.report_count;
      }
      1
    }
    None => 0,
  }
}

#[no_mangle]
pub unsafe extern "C" fn oc_iter_free(it: *mut OcIter) {
  if !it.is_null() {
    drop(Box::from_raw(it));
  }
}
