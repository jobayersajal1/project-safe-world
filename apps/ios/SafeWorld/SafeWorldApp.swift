import SwiftUI

@main
struct SafeWorldApp: App {
    @StateObject private var store = SettingsStore()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
        }
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .active { store.refreshRemoteIfDue() }
        }
    }
}
