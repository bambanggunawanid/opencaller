import Foundation

/// Thin Swift wrapper over the Rust core's C ABI (opencaller.h).
/// Same trust rules as Android: callers verify a shard's signature before
/// opening it; parsing failures degrade to a miss, never a crash.
final class SpamCore {
  private let handle: OpaquePointer

  init?(path: String) {
    guard let h = oc_open(path) else { return nil }
    handle = h
  }

  deinit { oc_close(handle) }

  var entryCount: UInt64 { oc_entry_count(handle) }
  var builtDays: UInt32 { oc_built_days(handle) }

  static func verify(shard: String, sig: String, pubkey: String) -> Bool {
    oc_verify(shard, sig, pubkey) == 1
  }

  struct Hit {
    let category: Int
    let reportCount: Int
  }

  /// Exact match first, then spam-block prefixes — mirrors Android.
  func lookup(_ number: String) -> Hit? {
    let packed = oc_lookup(handle, number)
    guard packed >= 0 else { return nil }
    return Hit(
      category: Int(packed >> 32),
      reportCount: Int(packed & 0xFFFF_FFFF)
    )
  }

  /// Streams every entry in ascending number order (the CXCallDirectory
  /// requirement) with O(1) memory. Return false from `body` to stop.
  func forEachEntry(_ body: (Int64, Int, Int) -> Bool) {
    guard let it = oc_iter_new(handle) else { return }
    defer { oc_iter_free(it) }
    var number: UInt64 = 0
    var category: UInt8 = 0
    var count: UInt16 = 0
    while oc_iter_next(it, &number, &category, &count) == 1 {
      // E.164 numbers are ≤ 15 digits, always inside Int64 range.
      if !body(Int64(bitPattern: number), Int(category), Int(count)) {
        break
      }
    }
  }
}
