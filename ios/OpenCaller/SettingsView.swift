import SwiftUI

/// Tab 3 — what to block. Identification (the caller-ID label) is always
/// on for every reported number; blocking is per category. iOS has no
/// "silence" middle ground, so the honest choice is label-only vs block.
struct SettingsView: View {
  @State private var blocked: Set<Int> = Shared.blockedCategories()

  private let categoryNames = [
    NSLocalizedString("Scam", comment: ""),
    NSLocalizedString("Robocall", comment: ""),
    NSLocalizedString("Telemarketing", comment: ""),
    NSLocalizedString("Debt collection", comment: ""),
    NSLocalizedString("Survey", comment: ""),
    NSLocalizedString("Other reports", comment: ""),
    NSLocalizedString("SMS spam", comment: ""),
  ]

  var body: some View {
    NavigationView {
      List {
        Section {
          ForEach(0..<categoryNames.count, id: \.self) { i in
            Toggle(
              categoryNames[i],
              isOn: Binding(
                get: { blocked.contains(i) },
                set: { on in
                  if on { blocked.insert(i) } else { blocked.remove(i) }
                  Shared.setBlockedCategories(blocked)
                  Task { try? await UpdateManager.reloadCallDirectory() }
                }
              ))
          }
        } header: {
          Text("Block calls from")
        } footer: {
          Text(
            "Categories left off still show a warning label on the call screen — the call rings and you decide. Blocked categories never ring."
          )
        }

        Section {
          Text(
            "OpenCaller is free, open source, and fully offline. No account, no ads, no data leaves your phone."
          )
          .font(.footnote)
        }
      }
      .navigationTitle("Settings")
    }
  }
}
