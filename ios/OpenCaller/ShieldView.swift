import CallKit
import SwiftUI

/// Tab 1 — "am I protected?" On iOS the OS consults our pre-loaded Call
/// Directory, so the two states that matter are: is the extension enabled
/// in Settings, and how fresh is the list it was loaded from.
struct ShieldView: View {
  @State private var extensionEnabled: Bool?
  @State private var entryCount: UInt64 = 0
  @State private var builtDate: String = "—"
  @State private var syncing = false
  @State private var syncMessage: String?

  var body: some View {
    NavigationView {
      List {
        Section {
          HStack(spacing: 12) {
            Image(
              systemName: extensionEnabled == true
                ? "checkmark.shield.fill" : "exclamationmark.shield.fill"
            )
            .font(.largeTitle)
            .foregroundColor(extensionEnabled == true ? .green : .orange)
            VStack(alignment: .leading, spacing: 4) {
              Text(
                extensionEnabled == true
                  ? "Protecting your calls" : "Not active yet"
              )
              .font(.headline)
              Text("\(entryCount) known spam numbers · built \(builtDate)")
                .font(.footnote)
                .foregroundColor(.secondary)
            }
          }
          if extensionEnabled != true {
            Text(
              "Enable OpenCaller in Settings → Phone → Call Blocking & Identification. iOS then shows our labels on incoming calls and blocks the categories you chose — fully offline."
            )
            .font(.footnote)
            Button("Open call blocking settings") {
              CXCallDirectoryManager.sharedInstance
                .openSettings(completionHandler: nil)
            }
          }
        }

        Section {
          Button(syncing ? "Updating…" : "Update spam list") {
            Task { await runSync() }
          }
          .disabled(syncing)
          if let syncMessage {
            Text(syncMessage).font(.footnote)
          }
        } footer: {
          Text(
            "One tap downloads the latest reported numbers (about 2 MB, signature-verified on your phone) and reloads the system caller-ID list."
          )
        }

        Section {
          Text(
            "Also enable the SMS filter: Settings → Messages → Unknown & Spam → OpenCaller SMS filter. Reported SMS-spam senders land silently in the Junk tab."
          )
          .font(.footnote)
        }
      }
      .navigationTitle("OpenCaller")
    }
    .task {
      refreshStatus()
      await autoSyncIfStale()
    }
  }

  private func refreshStatus() {
    if let db = Shared.openDb() {
      entryCount = db.entryCount
      let date = Date(timeIntervalSince1970: Double(db.builtDays) * 86_400)
      let fmt = DateFormatter()
      fmt.dateStyle = .medium
      builtDate = fmt.string(from: date)
    } else {
      entryCount = 0
      builtDate = "—"
    }
    CXCallDirectoryManager.sharedInstance.getEnabledStatusForExtension(
      withIdentifier: Shared.callDirectoryId
    ) { status, _ in
      DispatchQueue.main.async { extensionEnabled = status == .enabled }
    }
  }

  private func autoSyncIfStale() async {
    let last = Shared.defaults?.double(forKey: Shared.lastSyncKey) ?? 0
    let staleAfter: TimeInterval = 10 * 86_400
    guard last == 0 || Date().timeIntervalSince1970 - last > staleAfter else {
      return
    }
    await runSync()
  }

  private func runSync() async {
    syncing = true
    defer { syncing = false }
    do {
      let outcome = try await UpdateManager.sync()
      syncMessage = String(
        format: NSLocalizedString("Updated — %llu numbers", comment: ""),
        outcome.entries)
      refreshStatus()
    } catch {
      syncMessage = String(
        format: NSLocalizedString("Update failed: %@", comment: ""),
        error.localizedDescription)
    }
  }
}
