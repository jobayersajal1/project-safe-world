import SwiftUI
import SafeWorldCore

/// Shortest PIN we accept. Four digits is the familiar phone-unlock length.
let minPinLength = 4

private extension String {
    var digitsOnly: String { filter(\.isNumber) }
}

/// A challenge the user must pass before a protection-weakening action goes
/// through. `onVerified` runs only on a correct PIN.
struct PinChallenge: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let onVerified: () -> Void
}

/// Owns every PIN-related sheet, so the screens don't each carry their own copy
/// of the same presentation state.
///
/// Port of the `requestPin` plumbing in `SafeWorldApp.kt`. The rule it exists to
/// serve is the same one Android's home screen states: **strengthening is free,
/// weakening costs the PIN.** Callers only wrap the weakening direction.
@MainActor
final class PinGate: ObservableObject {

    enum Route: Identifiable {
        case choosePin(title: String, confirmLabel: String, onChosen: (String) -> Void)
        case showRecoveryCode(code: String, isReplacement: Bool)
        case enterRecoveryCode

        var id: String {
            switch self {
            case .choosePin: return "choosePin"
            case .showRecoveryCode: return "showRecoveryCode"
            case .enterRecoveryCode: return "enterRecoveryCode"
            }
        }
    }

    @Published var challenge: PinChallenge?
    @Published var route: Route?

    private let pins: PinStore

    init(pins: PinStore) { self.pins = pins }

    /// Runs `action` immediately when no PIN has been set — there is nothing to
    /// verify against yet, and refusing would lock the user out of their own app
    /// before they ever chose a PIN.
    func requiringPin(title: String, message: String, action: @escaping () -> Void) {
        guard pins.hasPin else { return action() }
        challenge = PinChallenge(title: title, message: message, onVerified: action)
    }

    /// Applies a settings change, asking for the PIN only if it weakens
    /// protection.
    ///
    /// Every gated action on both screens goes through here, so no call site
    /// decides for itself which direction it is going —
    /// `ProtectionChange.weakens` answers that once, and is tested against
    /// `Matcher.decide` in `ProtectionChangeTests`. A new setting added later
    /// gets its gate by construction rather than by the author remembering.
    func apply(
        to store: SettingsStore,
        title: String,
        message: String,
        _ mutate: @escaping (inout Settings) -> Void
    ) {
        var candidate = store.settings
        mutate(&candidate)

        guard ProtectionChange.weakens(from: store.settings, to: candidate) else {
            return store.update(mutate)
        }
        requiringPin(title: title, message: message) { store.update(mutate) }
    }

    func choosePin(
        title: String = L("pin_choose_title"),
        confirmLabel: String = L("pin_set"),
        onChosen: @escaping (String) -> Void
    ) {
        route = .choosePin(title: title, confirmLabel: confirmLabel, onChosen: onChosen)
    }
}

// MARK: - Prompt

/// Asks for the existing PIN. Deliberately gives no hint about how wrong the
/// entry was, and stays open until the user passes or explicitly cancels.
struct PinPromptView: View {
    let challenge: PinChallenge
    let pins: PinStore
    let onForgot: () -> Void
    let onDismiss: () -> Void

    @State private var pin = ""
    @State private var message: String?
    @State private var remaining: Int64 = 0

    /// Ticks once a second so the cooldown visibly counts down instead of
    /// looking frozen.
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var locked: Bool { remaining > 0 }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(locked ? L("pin_locked_body", Self.countdown(remaining)) : challenge.message)
                        .font(.callout)

                    if !locked {
                        SecureField(L("pin_field"), text: $pin)
                            .keyboardType(.numberPad)
                            .textContentType(.password)
                            .onChange(of: pin) { _ in
                                pin = pin.digitsOnly
                                message = nil
                            }
                    }

                    if let message {
                        Text(message).font(.footnote).foregroundStyle(.red)
                    }
                }

                Section {
                    // Offered while locked out as well — that is the state
                    // people are usually in when they reach for it.
                    Button(L("pin_forgot"), action: onForgot)
                }
            }
            .navigationTitle(locked ? L("pin_locked_title") : challenge.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(locked ? L("action_close") : L("action_cancel"), action: onDismiss)
                }
                if !locked {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(L("action_confirm"), action: submit)
                            .disabled(pin.count < minPinLength)
                    }
                }
            }
        }
        .onAppear { remaining = pins.lockoutRemaining() }
        .onReceive(tick) { _ in
            if remaining > 0 { remaining = max(0, remaining - 1000) }
        }
    }

    private func submit() {
        switch pins.verifyPin(pin) {
        case .success:
            challenge.onVerified()
            onDismiss()
        case .wrong(let attemptsRemaining):
            message = Language.plural("pin_incorrect", attemptsRemaining)
            pin = ""
        case .lockedOut(let millisecondsRemaining):
            remaining = millisecondsRemaining
            message = nil
            pin = ""
        }
    }

    private static func countdown(_ milliseconds: Int64) -> String {
        let seconds = (milliseconds + 999) / 1000
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

// MARK: - Choosing a PIN

/// Choosing a PIN — first run, changing it, or after a recovery reset. The three
/// differ only in wording, so they share one screen rather than drifting apart.
struct SetPinView: View {
    let title: String
    let confirmLabel: String
    let onPinChosen: (String) -> Void
    var onCancel: (() -> Void)?

    @State private var pin = ""
    @State private var confirm = ""

    private var mismatch: Bool { !confirm.isEmpty && confirm != pin }
    private var canSubmit: Bool { pin.count >= minPinLength && confirm == pin }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(L("pin_choose_body"))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Section {
                    SecureField(L("pin_field_min", minPinLength), text: $pin)
                        .keyboardType(.numberPad)
                        .onChange(of: pin) { _ in pin = pin.digitsOnly }

                    SecureField(L("pin_confirm_field"), text: $confirm)
                        .keyboardType(.numberPad)
                        .onChange(of: confirm) { _ in confirm = confirm.digitsOnly }

                    if mismatch {
                        Text(L("pin_mismatch")).font(.footnote).foregroundStyle(.red)
                    }
                }

                Section {
                    Button(confirmLabel) { onPinChosen(pin) }
                        .disabled(!canSubmit)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if let onCancel {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(L("action_cancel"), action: onCancel)
                    }
                }
            }
        }
    }
}

// MARK: - Recovery code

/// Shows a freshly issued recovery code. This is the only moment it is readable
/// — only its hash is stored — so the screen can't be left until the user says
/// they have written it down.
struct RecoveryCodeView: View {
    let code: String
    let isReplacement: Bool
    let onAcknowledge: () -> Void

    @State private var copied = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(isReplacement ? L("recovery_replaced") : L("recovery_show_body"))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Text(code)
                        .font(.system(.title3, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.vertical, 8)

                    Button(copied ? L("recovery_copied") : L("recovery_copy")) {
                        UIPasteboard.general.string = code
                        copied = true
                    }
                }

                Section {
                    Button(L("recovery_acknowledge"), action: onAcknowledge)
                }
            }
            .navigationTitle(L("recovery_show_title"))
            .navigationBarTitleDisplayMode(.inline)
            // No cancel: leaving without reading it is the one outcome this
            // screen exists to prevent.
            .interactiveDismissDisabled()
        }
    }
}

/// The way back in: recovery code, then a new PIN.
struct RecoveryEntryView: View {
    let pins: PinStore
    /// Called with the verified code and the chosen PIN. Both are passed on so
    /// the reset goes through `PinStore.resetPin(withRecoveryCode:newPin:)` and
    /// re-checks the code — this screen's own check is for the UI's benefit, and
    /// should not be the only thing standing between a stranger and a new PIN.
    let onReset: (String, String) -> Void
    let onDismiss: () -> Void

    @State private var code = ""
    @State private var verified = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Group {
                if verified {
                    SetPinView(
                        title: L("recovery_new_pin_title"),
                        confirmLabel: L("pin_save"),
                        onPinChosen: { onReset(code, $0) },
                        onCancel: onDismiss
                    )
                } else {
                    Form {
                        Section {
                            Text(pins.hasRecoveryCode ? L("recovery_enter_body") : L("recovery_none"))
                                .font(.callout)
                                .foregroundStyle(.secondary)
                        }

                        if pins.hasRecoveryCode {
                            Section {
                                TextField(L("recovery_field"), text: $code)
                                    .font(.system(.body, design: .monospaced))
                                    .textInputAutocapitalization(.characters)
                                    .autocorrectionDisabled()
                                    .onChange(of: code) { _ in error = nil }

                                if let error {
                                    Text(error).font(.footnote).foregroundStyle(.red)
                                }
                            }
                        }
                    }
                    .navigationTitle(L("recovery_enter_title"))
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L("action_cancel"), action: onDismiss)
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L("action_continue")) {
                                if pins.verifyRecoveryCode(code) {
                                    verified = true
                                } else {
                                    error = L("recovery_wrong")
                                }
                            }
                            // Checked before spending PBKDF2 on it.
                            .disabled(!RecoveryCode.isWellFormed(code))
                        }
                    }
                }
            }
        }
    }
}
