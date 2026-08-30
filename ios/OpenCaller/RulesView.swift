import SwiftUI

/// Tab 2 — personal block rules. Private to this phone; they feed the
/// Call Directory's blocking list and the SMS filter, never any shared
/// database. iOS needs full international digits (CXCallDirectory numbers
/// are E.164 integers).
struct RulesView: View {
  @State private var rules: [String] = Shared.userBlockedRaw()
  @State private var input = ""

  var body: some View {
    NavigationView {
      List {
        Section {
          HStack {
            TextField("Number, e.g. 6281234567890", text: $input)
              .keyboardType(.phonePad)
            Button("Block") { addRule() }
              .disabled(normalized(input) == nil)
          }
        } footer: {
          Text(
            "Use the full international format without + (country code first). Rules stay on this phone and never mark anyone in the shared database."
          )
        }

        if !rules.isEmpty {
          Section(header: Text("Blocked")) {
            ForEach(rules, id: \.self) { rule in
              Text(rule)
            }
            .onDelete { offsets in
              rules.remove(atOffsets: offsets)
              save()
            }
          }
        }
      }
      .navigationTitle("Your rules")
    }
  }

  private func normalized(_ raw: String) -> String? {
    let digits = raw.filter(\.isNumber)
    guard digits.count >= 7, digits.count <= 15, Int64(digits) != nil
    else { return nil }
    return digits
  }

  private func addRule() {
    guard let digits = normalized(input), !rules.contains(digits) else {
      return
    }
    rules.append(digits)
    rules.sort()
    input = ""
    save()
  }

  private func save() {
    Shared.setUserBlockedRaw(rules)
    Task { try? await UpdateManager.reloadCallDirectory() }
  }
}
