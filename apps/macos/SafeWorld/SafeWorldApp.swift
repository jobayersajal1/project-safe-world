import SwiftUI
import AppKit
import Combine

@main
struct SafeWorldApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ObservedObject private var store = SettingsStore.shared

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

/// Owns the two things a `MenuBarExtra`-only app cannot express as a scene: the first-run setup
/// window, and the app-wide appearance.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var setupWindow: NSWindow?
    private var themeObserver: AnyCancellable?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let store = SettingsStore.shared

        // Set on `NSApp` rather than with `.preferredColorScheme` on the view. A `MenuBarExtra`'s
        // window is not part of the SwiftUI window hierarchy that modifier reaches, so it would
        // leave the one surface this app actually has following the system.
        applyAppearance(store.darkTheme)
        themeObserver = store.$darkTheme.sink { [weak self] dark in
            self?.applyAppearance(dark)
        }

        guard !store.onboardingComplete else { return }
        presentSetup(store: store)
    }

    private func applyAppearance(_ dark: Bool) {
        NSApp.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    }

    /// A real window, not a popover.
    ///
    /// Setup asks six questions and can trigger an admin password prompt; a menu-bar popover
    /// dismisses the moment focus moves, which that prompt does. The window is built by hand
    /// rather than declared as a `Window` scene because this app is an `LSUIElement` accessory —
    /// there is no scene to open one *from* at launch, since `MenuBarExtra` content does not exist
    /// until the user clicks the shield.
    private func presentSetup(store: SettingsStore) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 460, height: 380),
            styleMask: [.titled, .closable],
            backing: .buffered,
            defer: false
        )
        window.title = "Set up Safe World"
        window.isReleasedWhenClosed = false

        let hosting = NSHostingView(
            rootView: SetupView(
                store: store,
                daemon: DaemonController(),
                onFinish: { [weak self] in self?.dismissSetup() }
            )
        )
        window.contentView = hosting
        // Sized from the view rather than trusting the `contentRect` above to survive being handed
        // a hosting view, so changing `SetupView`'s frame is enough to resize the window.
        window.setContentSize(hosting.fittingSize)
        window.center()

        // An accessory app does not come to the front on its own, so a window it opens at launch
        // would otherwise appear behind whatever the user was already doing.
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        setupWindow = window
    }

    private func dismissSetup() {
        setupWindow?.close()
        setupWindow = nil
    }
}
