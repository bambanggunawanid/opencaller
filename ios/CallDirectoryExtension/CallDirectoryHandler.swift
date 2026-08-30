import CallKit
import Foundation

/// The iOS hot path. Unlike Android's per-call CallScreeningService, iOS
/// consults a directory we pre-load here: blocking entries never ring,
/// identification entries put our label on the incoming-call screen.
/// Entries MUST be added in ascending order; the extension runs under a
/// strict memory budget, so everything streams from the mmap'd shard via
/// the Rust core — no collection of 200k+ numbers is ever built.
final class CallDirectoryHandler: CXCallDirectoryProvider,
  CXCallDirectoryExtensionContextDelegate
{
  override func beginRequest(with context: CXCallDirectoryExtensionContext) {
    context.delegate = self

    // Full reload every time: our shard swaps atomically, so incremental
    // diffs buy little and complicate ordering. Reset first if asked.
    if context.isIncremental {
      context.removeAllBlockingEntries()
      context.removeAllIdentificationEntries()
    }

    let db = Shared.openDb() // nil = no verified shard yet; rules still apply
    addBlockingEntries(db: db, context: context)
    if let db {
      addIdentificationEntries(db: db, context: context)
    }
    context.completeRequest()
  }

  func requestFailed(
    for extensionContext: CXCallDirectoryExtensionContext,
    withError error: Error
  ) {
    // Nothing to clean up: the next reload rebuilds from scratch.
  }

  /// User rules + blocked categories, merged into one ascending stream.
  private func addBlockingEntries(
    db: SpamCore?, context: CXCallDirectoryExtensionContext
  ) {
    let rules = Shared.userBlockedNumbers()
    let categories = Shared.blockedCategories()
    var ruleIdx = 0
    var last: Int64 = -1

    func emit(_ number: Int64) {
      guard number != last else { return }
      context.addBlockingEntry(withNextSequentialPhoneNumber: number)
      last = number
    }

    if let db {
      db.forEachEntry { number, category, _ in
        while ruleIdx < rules.count, rules[ruleIdx] <= number {
          emit(rules[ruleIdx])
          ruleIdx += 1
        }
        if categories.contains(category) {
          emit(number)
        }
        return true
      }
    }
    while ruleIdx < rules.count {
      emit(rules[ruleIdx])
      ruleIdx += 1
    }
  }

  /// Every reported number gets a caller-ID label, blocked or not — the
  /// label is what tells the user WHY when a non-blocked category rings.
  private func addIdentificationEntries(
    db: SpamCore, context: CXCallDirectoryExtensionContext
  ) {
    let names = [
      NSLocalizedString("Scam", comment: ""),
      NSLocalizedString("Robocall", comment: ""),
      NSLocalizedString("Telemarketing", comment: ""),
      NSLocalizedString("Debt collection", comment: ""),
      NSLocalizedString("Survey", comment: ""),
      NSLocalizedString("Reported spam", comment: ""),
      NSLocalizedString("SMS spam", comment: ""),
    ]
    let reportsWord = NSLocalizedString("reports", comment: "")
    var batch = 0
    db.forEachEntry { number, category, count in
      let name = category < names.count
        ? names[category]
        : NSLocalizedString("Reported spam", comment: "")
      let label = count > 1
        ? "\(name) · \(count) \(reportsWord) (OpenCaller)"
        : "\(name) (OpenCaller)"
      context.addIdentificationEntry(
        withNextSequentialPhoneNumber: number, label: label)
      // Keep the transient label strings from piling up against the
      // extension's memory cap.
      batch += 1
      if batch % 4096 == 0 {
        autoreleasepool {}
      }
      return true
    }
  }
}
