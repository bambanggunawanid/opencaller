//! JNI exports for the Android shell (`dev.opencaller.app.NativeCore`).
//!
//! Hand-rolled JNI for the M0 spike — four functions on the hot/cold paths.
//! UniFFI can replace this seam later without touching `opencaller-core`
//! (PRD §7: "UniFFI or direct JNI where hot paths demand it").
//!
//! Contract: `nativeOpen` returns an opaque handle (0 = failure) that the
//! Kotlin side owns and must pass to `nativeClose` exactly once. Lookups
//! return `"category|report_count|last_seen_days"` or null for a miss —
//! deliberately primitive so the screening hot path allocates almost
//! nothing.

use std::fs;
use std::path::Path;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use opencaller_core::db::SpamDb;
use opencaller_core::update::verify_shard;

fn get_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
  env.get_string(s).ok().map(|j| j.into())
}

/// Open an OCDB shard; returns a handle, or 0 on any failure.
#[no_mangle]
pub extern "system" fn Java_dev_opencaller_app_NativeCore_nativeOpen(
  mut env: JNIEnv,
  _class: JClass,
  path: JString,
) -> jlong {
  let Some(path) = get_string(&mut env, &path) else {
    return 0;
  };
  match SpamDb::open(Path::new(&path)) {
    Ok(db) => Box::into_raw(Box::new(db)) as jlong,
    Err(_) => 0,
  }
}

/// Hot path: lookup for the CallScreeningService.
/// Returns "category|report_count|last_seen_days" or null.
#[no_mangle]
pub extern "system" fn Java_dev_opencaller_app_NativeCore_nativeLookup(
  mut env: JNIEnv,
  _class: JClass,
  handle: jlong,
  number: JString,
) -> jstring {
  if handle == 0 {
    return std::ptr::null_mut();
  }
  // SAFETY: handle originates from nativeOpen and is not yet closed —
  // enforced by the Kotlin owner (single DbManager instance).
  let db = unsafe { &*(handle as *const SpamDb) };
  let Some(number) = get_string(&mut env, &number) else {
    return std::ptr::null_mut();
  };
  match db.lookup_str(&number) {
    Some(info) => {
      let s = format!(
        "{}|{}|{}",
        info.category.label(),
        info.report_count,
        info.last_seen_days
      );
      env
        .new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
    }
    None => std::ptr::null_mut(),
  }
}

#[no_mangle]
pub extern "system" fn Java_dev_opencaller_app_NativeCore_nativeEntryCount(
  _env: JNIEnv,
  _class: JClass,
  handle: jlong,
) -> jlong {
  if handle == 0 {
    return 0;
  }
  let db = unsafe { &*(handle as *const SpamDb) };
  db.len() as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_opencaller_app_NativeCore_nativeClose(
  _env: JNIEnv,
  _class: JClass,
  handle: jlong,
) {
  if handle != 0 {
    // SAFETY: exactly-once contract owned by the Kotlin side.
    drop(unsafe { Box::from_raw(handle as *mut SpamDb) });
  }
}

/// Cold path: verify a downloaded/bundled shard against the pinned pubkey
/// before it is ever opened.
#[no_mangle]
pub extern "system" fn Java_dev_opencaller_app_NativeCore_nativeVerify(
  mut env: JNIEnv,
  _class: JClass,
  shard_path: JString,
  sig_path: JString,
  pubkey_path: JString,
) -> jboolean {
  let (Some(shard), Some(sig), Some(pubkey)) = (
    get_string(&mut env, &shard_path),
    get_string(&mut env, &sig_path),
    get_string(&mut env, &pubkey_path),
  ) else {
    return JNI_FALSE;
  };
  let (Ok(shard), Ok(sig), Ok(pubkey)) =
    (fs::read(&shard), fs::read(&sig), fs::read(&pubkey))
  else {
    return JNI_FALSE;
  };
  if verify_shard(&shard, &sig, &pubkey) {
    JNI_TRUE
  } else {
    JNI_FALSE
  }
}
