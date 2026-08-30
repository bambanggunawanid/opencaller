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

  /**
   * F7 data-free heuristics for DB misses. Returns a suspicion label
   * ("own-number-spoof", "neighbor-spoof", "invalid-number", "too-short")
   * or null. Pass "" for ownNumber when unset. Stateless.
   */
  external fun nativeHeuristic(number: String, ownNumber: String): String?
  external fun nativeEntryCount(handle: Long): Long
  external fun nativeBuiltDays(handle: Long): Int
  external fun nativeClose(handle: Long)
  external fun nativeVerify(shardPath: String, sigPath: String, pubkeyPath: String): Boolean

  /**
   * Full update transaction (verify → validate → rollback check → atomic
   * swap). Returns "ok|entries|builtDays" or "error|message". Close and
   * reopen the handle afterwards.
   */
  external fun nativeApplyUpdate(
    dir: String,
    shardName: String,
    newShard: ByteArray,
    newSig: ByteArray,
    pubkeyPath: String,
  ): String
}
