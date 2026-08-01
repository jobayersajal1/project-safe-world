import SwiftUI

@main
struct SafeWorldApp: App {
    @StateObject private var store = SettingsStore()
    @StateObject private var pins: PinStore
    @StateObject private var gate: PinGate
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // `PinGate` needs the store, and `@StateObject` can't reference another
        // property's value in its initializer, so both are built here and the
        // wrappers wrapped around the finished objects.
        let pins = PinStore()
        _pins = StateObject(wrappedValue: pins)
        _gate = StateObject(wrappedValue: PinGate(pins: pins))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(pins)
                .environmentObject(gate)
                // Brass, matching the Android theme and the website. SwiftUI's default is
                // system blue, which is every other app on the phone — and cold, where this
                // one is trying to reassure rather than alert.
                .tint(Brand.accent)
                // Stated rather than inherited. Without this SwiftUI follows the phone, and
                // the app would be dark on a dark-mode iPhone while Android and the website
                // — which no longer consult the system at all — stayed light. One product,
                // one look. See `SettingsStore.darkTheme`.
                //
                // Set here, on the root, so it reaches `Brand`'s dynamic `UIColor`s through
                // the trait collection as well as the built-in surfaces.
                .preferredColorScheme(store.darkTheme ? .dark : .light)
        }
        .onChange(of: scenePhase) { newPhase in
            if newPhase == .active { store.refreshRemoteIfDue() }
        }
    }
}
