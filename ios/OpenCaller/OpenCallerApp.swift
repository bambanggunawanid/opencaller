import SwiftUI

@main
struct OpenCallerApp: App {
  init() {
    Shared.ensureSeed(from: Bundle.main)
  }

  var body: some Scene {
    WindowGroup {
      TabView {
        ShieldView()
          .tabItem { Label("Shield", systemImage: "shield.fill") }
        RulesView()
          .tabItem { Label("Rules", systemImage: "nosign") }
        SettingsView()
          .tabItem { Label("Settings", systemImage: "gearshape.fill") }
      }
    }
  }
}
