import CallKit
import Foundation

/// The distribution leg on iOS: fetch the newest signed shard from static
/// hosting, verify against the pinned key, rollback-check, swap into the
/// App Group, and ask the system to reload the Call Directory extension.
/// The transport is untrusted — only the Ed25519 signature matters.
enum UpdateManager {
  static let baseURL =
    "https://github.com/bambanggunawanid/opencaller/releases/latest/download"
  static let maxShardBytes = 64 * 1024 * 1024

  enum UpdateError: LocalizedError {
    case noContainer
    case network(String)
    case badSignature
    case rollback
    case malformed

    var errorDescription: String? {
      switch self {
      case .noContainer: return "App Group container unavailable"
      case .network(let m): return "network: \(m)"
      case .badSignature: return "signature verification failed"
      case .rollback: return "server offered an older database"
      case .malformed: return "malformed database"
      }
    }
  }

  struct Outcome {
    let entries: UInt64
    let builtDays: UInt32
  }

  static func sync() async throws -> Outcome {
    guard let container = Shared.container,
      let shardURL = Shared.shardURL, let sigURL = Shared.sigURL,
      let pubkeyURL = Shared.pubkeyURL
    else { throw UpdateError.noContainer }

    let shardData = try await fetch("\(baseURL)/\(Shared.shardName)")
    let sigData = try await fetch("\(baseURL)/\(Shared.shardName).sig")

    let tmpShard = container.appendingPathComponent(Shared.shardName + ".new")
    let tmpSig = container.appendingPathComponent(Shared.shardName + ".sig.new")
    try shardData.write(to: tmpShard, options: .atomic)
    try sigData.write(to: tmpSig, options: .atomic)
    defer {
      try? FileManager.default.removeItem(at: tmpShard)
      try? FileManager.default.removeItem(at: tmpSig)
    }

    // Verify → validate → rollback check → swap (Android's F4 order).
    guard
      SpamCore.verify(
        shard: tmpShard.path, sig: tmpSig.path, pubkey: pubkeyURL.path)
    else { throw UpdateError.badSignature }
    guard let newDb = SpamCore(path: tmpShard.path) else {
      throw UpdateError.malformed
    }
    let newBuilt = newDb.builtDays
    let newEntries = newDb.entryCount
    if let current = Shared.openDb(), newBuilt < current.builtDays {
      throw UpdateError.rollback
    }

    if FileManager.default.fileExists(atPath: shardURL.path) {
      _ = try FileManager.default.replaceItemAt(shardURL, withItemAt: tmpShard)
      _ = try FileManager.default.replaceItemAt(sigURL, withItemAt: tmpSig)
    } else {
      try FileManager.default.moveItem(at: tmpShard, to: shardURL)
      try FileManager.default.moveItem(at: tmpSig, to: sigURL)
    }
    Shared.defaults?.set(
      Date().timeIntervalSince1970, forKey: Shared.lastSyncKey)

    try await reloadCallDirectory()
    return Outcome(entries: newEntries, builtDays: newBuilt)
  }

  private static func fetch(_ url: String) async throws -> Data {
    guard let u = URL(string: url) else { throw UpdateError.network("bad URL") }
    let (data, response): (Data, URLResponse)
    do {
      (data, response) = try await URLSession.shared.data(from: u)
    } catch {
      throw UpdateError.network(error.localizedDescription)
    }
    guard let http = response as? HTTPURLResponse, http.statusCode == 200
    else { throw UpdateError.network("bad status") }
    guard data.count <= maxShardBytes else {
      throw UpdateError.network("download exceeds size limit")
    }
    return data
  }

  /// Pushes the new list into the system's caller-ID database.
  static func reloadCallDirectory() async throws {
    try await withCheckedThrowingContinuation {
      (cont: CheckedContinuation<Void, Error>) in
      CXCallDirectoryManager.sharedInstance.reloadExtension(
        withIdentifier: Shared.callDirectoryId
      ) { error in
        if let error { cont.resume(throwing: error) } else { cont.resume() }
      }
    }
  }
}
