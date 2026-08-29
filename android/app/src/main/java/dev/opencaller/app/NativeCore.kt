package dev.opencaller.app

/**
 * JNI surface of `opencaller-core` (crates/opencaller-android).
 *
 * Contract: [nativeOpen] returns 0 on failure; a non-zero handle is owned by
 * the caller and must be passed to [nativeClose] exactly once. Lookups
 * return "category|report_count|last_seen_days" or null.
 */
object NativeCore {
  init {
    System.loadLibrary("opencaller_android")
  }

  external fun nativeOpen(path: String): Long
  external fun nativeLookup(handle: Long, number: String): String?
  external fun nativeEntryCount(handle: Long): Long
  external fun nativeClose(handle: Long)
  external fun nativeVerify(shardPath: String, sigPath: String, pubkeyPath: String): Boolean
}
