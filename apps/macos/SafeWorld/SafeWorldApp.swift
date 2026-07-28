import SwiftUI

@main
struct SafeWorldApp: App {
    @StateObject private var store = SettingsStore()

    var body: some Scene {
        MenuBarExtra(
            "Safe World",
            systemImage: store.settings.enabled ? "shield.checkerboard" : "shield.slash"
        ) {
            MenuView()
                .environmentObject(store)
        }
        .menuBarExtraStyle(.window)
    }
}
