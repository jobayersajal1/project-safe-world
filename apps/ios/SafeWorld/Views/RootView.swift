import SwiftUI

/// The tab bar, plus every PIN-related sheet.
///
/// The sheets are presented here rather than inside Home and Settings because
/// both screens raise the same challenges, and a sheet owned by a tab disappears
/// when that tab does.
struct RootView: View {
    @EnvironmentObject private var store: SettingsStore
    @EnvironmentObject private var pins: PinStore
    @EnvironmentObject private var gate: PinGate

    /// Which tab to open on.
    ///
    /// Seeded from `-startTab settings` so a screenshot of the Settings screen
    /// can be taken from the command line. The Simulator cannot be tapped
    /// without granting accessibility permission to whatever is driving it, so
    /// without this hook the second tab is unreachable in an automated check —
    /// and a screen nobody can screenshot is a screen that regresses quietly.
    @State private var tab = UserDefaults.standard.string(forKey: "startTab") == "settings" ? 1 : 0

    var body: some View {
        // First run: every decision made once, in order, and applied — see
        // `OnboardingView`. Placed ahead of the tabs so setup cannot be
        // sidestepped by tapping Settings.
        if !store.onboardingComplete {
            OnboardingView(store: store, pins: pins)
                .environment(\.locale, Language.effectiveLocale)
                .environment(\.layoutDirection, Language.layoutDirection)
                .id(store.language)
        } else {
            tabs
        }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            HomeView()
                .tabItem { Label(L("tab_home"), systemImage: "shield.fill") }
                .tag(0)
            SettingsView()
                .tabItem { Label(L("tab_settings"), systemImage: "gearshape.fill") }
                .tag(1)
        }
        // The language picker changes `store.language`, which redraws this whole
        // tree — that is what re-resolves every `L(...)` through the new bundle.
        // The locale goes into the environment too, so dates and numbers agree
        // with the words around them; `Language` explains why the locale alone
        // is not enough to switch the text.
        .id(store.language)
        .environment(\.locale, Language.effectiveLocale)
        .environment(\.layoutDirection, Language.layoutDirection)
        .sheet(item: $gate.challenge) { challenge in
            PinPromptView(
                challenge: challenge,
                pins: pins,
                onForgot: {
                    gate.challenge = nil
                    gate.route = .enterRecoveryCode
                },
                onDismiss: { gate.challenge = nil }
            )
        }
        .sheet(item: $gate.route) { route in
            switch route {
            case .choosePin(let title, let confirmLabel, let onChosen):
                SetPinView(
                    title: title,
                    confirmLabel: confirmLabel,
                    onPinChosen: { pin in
                        gate.route = nil
                        onChosen(pin)
                    },
                    onCancel: { gate.route = nil }
                )

            case .showRecoveryCode(let code, let isReplacement):
                RecoveryCodeView(
                    code: code,
                    isReplacement: isReplacement,
                    onAcknowledge: { gate.route = nil }
                )

            case .enterRecoveryCode:
                RecoveryEntryView(
                    pins: pins,
                    onReset: { code, pin in
                        // Re-checks the code rather than trusting the sheet's
                        // own check. Also clears the cooldown, so the new PIN
                        // works now rather than after the fifteen minutes that
                        // sent the user here in the first place.
                        if pins.resetPin(withRecoveryCode: code, newPin: pin) {
                            gate.route = nil
                        }
                    },
                    onDismiss: { gate.route = nil }
                )
            }
        }
    }
}
