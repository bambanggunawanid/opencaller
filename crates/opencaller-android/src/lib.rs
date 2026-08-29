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

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use opencaller_core::db::SpamDb;
use opencaller_core::update::{apply_update, verify_shard};

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

#[no_mangle]
pub extern "system" fn Java_dev_opencaller_app_NativeCore_nativeBuiltDays(
  _env: JNIEnv,
  _class: JClass,
  handle: jlong,
) -> jint {
  if handle == 0 {
    return 0;
  }
  let db = unsafe { &*(handle as *const SpamDb) };
  db.built_days() as jint
}

/// Cold path: the complete update transaction (verify → validate →
/// rollback check → atomic swap). Returns "ok|<entries>|<built_days>" on
/// success, "error|<message>" otherwise. The caller must close and reopen
/// its handle afterwards.
#[no_mangle]
pub extern "system" fn Java_dev_opencaller_app_NativeCore_nativeApplyUpdate(
  mut env: JNIEnv,
  _class: JClass,
  dir: JString,
  shard_name: JString,
  new_shard: JByteArray,
  new_sig: JByteArray,
  pubkey_path: JString,
) -> jstring {
  let result = (|| -> Result<String, String> {
    let dir = get_string(&mut env, &dir).ok_or("bad dir")?;
    let name = get_string(&mut env, &shard_name).ok_or("bad name")?;
    let shard = env.convert_byte_array(&new_shard).map_err(|e| e.to_string())?;
    let sig = env.convert_byte_array(&new_sig).map_err(|e| e.to_string())?;
    let pubkey_path = get_string(&mut env, &pubkey_path).ok_or("bad pubkey path")?;
    let pubkey = fs::read(&pubkey_path).map_err(|e| e.to_string())?;
    let applied = apply_update(Path::new(&dir), &name, &shard, &sig, &pubkey)
      .map_err(|e| e.to_string())?;
    Ok(format!("ok|{}|{}", applied.entries, applied.built_days))
  })();
  let out = match result {
    Ok(s) => s,
    Err(e) => format!("error|{e}"),
  };
  env
    .new_string(out)
    .map(|j| j.into_raw())
    .unwrap_or(std::ptr::null_mut())
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
