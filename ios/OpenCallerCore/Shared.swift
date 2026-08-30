import Foundation

/// Everything the app and its extensions share through the App Group:
/// the verified shard files and the user's settings. Mirrors Android's
/// DbManager/Prefs contract — nothing here ever leaves the device.
enum Shared {
  static let appGroup = "group.dev.opencaller.app"
  static let callDirectoryId = "dev.opencaller.app.calldirectory"
  static let shardName = "us.ocdb"
  static let pubkeyName = "shard_signing.pub"

  // Category ordinals match the core's Category enum / Android's list.
  static let categorySlugs = [
    "scam", "robocall", "telemarketing", "debt-collection", "survey",
    "other", "sms-spam",
  ]
  static let smsSpamCategory = 6
  /// iOS has no "silence" — only block or identify. Default-block the
  /// categories Android default-silences.
  static let defaultBlockedCategories: Set<Int> = [0, 1]

  // Shared UserDefaults keys.
  static let blockedCategoriesKey = "blockedCategories" // [Int]
  static let userBlockedKey = "userBlockedNumbers" // [String] digit strings
  static let lastSyncKey = "lastSync" // TimeInterval since 1970

  static var container: URL? {
    FileManager.default
      .containerURL(forSecurityApplicationGroupIdentifier: appGroup)
  }

  static var shardURL: URL? { container?.appendingPathComponent(shardName) }
  static var sigURL: URL? {
    container?.appendingPathComponent(shardName + ".sig")
  }
  static var pubkeyURL: URL? { container?.appendingPathComponent(pubkeyName) }

  static var defaults: UserDefaults? { UserDefaults(suiteName: appGroup) }

  static func blockedCategories() -> Set<Int> {
    guard let raw = defaults?.array(forKey: blockedCategoriesKey) as? [Int]
    else { return defaultBlockedCategories }
    return Set(raw)
  }

  static func setBlockedCategories(_ set: Set<Int>) {
    defaults?.set(Array(set).sorted(), forKey: blockedCategoriesKey)
  }

  /// User block rules as sorted Int64 numbers (full international digits).
  static func userBlockedNumbers() -> [Int64] {
    let raw = defaults?.stringArray(forKey: userBlockedKey) ?? []
    return raw.compactMap { Int64($0.filter(\.isNumber)) }.sorted()
  }

  static func userBlockedRaw() -> [String] {
    defaults?.stringArray(forKey: userBlockedKey) ?? []
  }

  static func setUserBlockedRaw(_ rules: [String]) {
    defaults?.set(rules, forKey: userBlockedKey)
  }

  /// Opens the shared shard ONLY if its signature verifies against the
  /// pinned key (extensions re-verify too: the group container is shared
  /// state, and an unverified file is treated as absent — PRD §9).
  static func openDb() -> SpamCore? {
    guard
      let shard = shardURL, let sig = sigURL, let pubkey = pubkeyURL,
      FileManager.default.fileExists(atPath: shard.path),
      SpamCore.verify(shard: shard.path, sig: sig.path, pubkey: pubkey.path)
    else { return nil }
    return SpamCore(path: shard.path)
  }

  /// First-run seeding from the app bundle (app target only — extensions
  /// read what the app has placed in the group container).
  static func ensureSeed(from bundle: Bundle) {
    guard let container else { return }
    let files = [shardName, shardName + ".sig", pubkeyName]
    for name in files {
      let dst = container.appendingPathComponent(name)
      guard !FileManager.default.fileExists(atPath: dst.path),
        let src = bundle.url(
          forResource: name, withExtension: nil, subdirectory: "Resources")
          ?? bundle.url(forResource: name, withExtension: nil)
      else { continue }
      try? FileManager.default.copyItem(at: src, to: dst)
    }
  }
}
