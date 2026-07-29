import SwiftUI
import SafeWorldCore

/// Language, PIN management, the allow list, the store link, updates, and help.
///
/// Ported section-for-section from `SettingsScreen.kt`, in the same order. The
/// block list is deliberately *not* here — it lives on the home screen, because
/// it is the same `customBlock` either way and two editors for one list is how
/// they end up disagreeing. What stays here is the allow list, the one edit that
/// can *unblock* something, which is why the whole save is gated.
struct SettingsView: View {
    @EnvironmentObject private var store: SettingsStore
    @EnvironmentObject private var pins: PinStore
    @EnvironmentObject private var gate: PinGate
    @StateObject private var updates = UpdateService()
    @Environment(\.openURL) private var openURL

    @State private var allowText = ""
    @State private var linkFailed = false

    var body: some View {
        NavigationStack {
            Form {
                languageSection
                pinSection
                allowListSection
                rateSection
                updateSection
                helpSection
            }
            .navigationTitle(L("settings_title"))
            .onAppear { allowText = store.settings.customAllow.joined(separator: "\n") }
            .alert(L("help_no_app"), isPresented: $linkFailed) {
                Button(L("action_close"), role: .cancel) {}
            }
        }
    }

    // MARK: Language

    private var languageSection: some View {
        Section(L("settings_language")) {
            ForEach(Language.supported, id: \.self) { tag in
                Button {
                    store.language = tag
                } label: {
                    HStack {
                        // Shown in its *own* language, so someone who has landed
                        // in a language they can't read can still find their way
                        // out.
                        Text(Language.displayName(tag))
                            .foregroundStyle(.primary)
                        Spacer()
                        if tag == store.language {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                }
            }
        }
    }

    // MARK: PIN

    private var pinSection: some View {
        Section(L("settings_security")) {
            if pins.hasPin {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("pin_change_description")).font(.footnote).foregroundStyle(.secondary)
                    Button(L("pin_change")) {
                        gate.requiringPin(
                            title: L("pin_change_title"),
                            message: L("pin_change_pin_message")
                        ) {
                            gate.choosePin(
                                title: L("pin_change_title"),
                                confirmLabel: L("pin_save")
                            ) { pins.setPin($0) }
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(L("recovery_settings_description"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button(L("recovery_settings_action")) {
                        gate.requiringPin(
                            title: L("recovery_settings_label"),
                            message: L("recovery_settings_pin_message")
                        ) {
                            gate.route = .showRecoveryCode(
                                code: pins.issueRecoveryCode(),
                                isReplacement: true
                            )
                        }
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("pin_setup_required")).font(.footnote).foregroundStyle(.secondary)
                    Button(L("pin_setup_action")) {
                        gate.choosePin { pin in
                            pins.setPin(pin)
                            gate.route = .showRecoveryCode(
                                code: pins.issueRecoveryCode(),
                                isReplacement: false
                            )
                        }
                    }
                }
            }
        }
    }

    // MARK: Allow list

    private var allowListSection: some View {
        Section {
            TextEditor(text: $allowText)
                .frame(minHeight: 100)
                .font(.system(.body, design: .monospaced))

            Button(L("settings_save")) {
                // Allow-list entries win over every category, so *adding* here
                // is the weakening direction — the opposite of the block list.
                // `gate.apply` knows that; this call site doesn't have to.
                let next = parseLines(allowText)
                gate.apply(
                    to: store,
                    title: L("settings_save_pin_title"),
                    message: L("settings_save_pin_message")
                ) { $0.customAllow = next }
            }
        } header: {
            Text(L("settings_allow_title"))
        } footer: {
            Text(L("settings_allow_help"))
        }
    }

    private func parseLines(_ text: String) -> [String] {
        text
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty }
    }

    // MARK: Rate

    private var rateSection: some View {
        Section {
            Button(L("settings_rate_action"), action: openStoreListing)
        } header: {
            Text(L("settings_rate_title"))
        } footer: {
            Text(L("settings_rate_description"))
        }
    }

    /// Opens this app's App Store listing.
    ///
    /// Deliberately not `SKStoreReviewController.requestReview`: that does
    /// nothing at all for a build the App Store has never seen, which is every
    /// build today, and a button that silently does nothing is worse than one
    /// that says it can't. Falls back to a message when nothing can open it —
    /// the same shape as `openStoreListing` on Android.
    private func openStoreListing() {
        guard
            let id = Bundle.main.object(forInfoDictionaryKey: "CFBundleIdentifier") as? String,
            let url = URL(string: "https://apps.apple.com/app/id\(id)")
        else {
            linkFailed = true
            return
        }
        open(url)
    }

    // MARK: Updates

    /// Installed version, and whatever the update check has found.
    ///
    /// Not PIN-gated: updating can only strengthen protection, and the PIN exists
    /// to make weakening deliberate. Gating it would mean a user who forgot their
    /// PIN also stops receiving fixes.
    ///
    /// The action is always "open the release page", never "install": iOS gives
    /// an app no way to install another build of itself. See `UpdateService`.
    @ViewBuilder
    private var updateSection: some View {
        Section {
            // "Installed version 0.1.0", not a "App version / 0.1.0" row under
            // an "App version" header — the section title already said it.
            Text(L("update_installed", updates.installedVersion))

            switch updates.state {
            case .idle:
                Button(L("update_check_action")) { updates.check(force: true) }

            case .checking:
                HStack {
                    ProgressView()
                    Text(L("update_checking")).foregroundStyle(.secondary)
                }

            case .upToDate:
                Text(L("update_up_to_date")).font(.footnote).foregroundStyle(.secondary)
                Button(L("update_check_action")) { updates.check(force: true) }

            case .available(let update):
                Text(L("update_available", update.version)).foregroundStyle(.tint)
                Button(L("update_view_release_action")) { openURL(update.pageURL) }

            case .failed:
                Text(L("update_error_check")).font(.footnote).foregroundStyle(.red)
                Button(L("update_check_action")) { updates.check(force: true) }
            }
        } header: {
            Text(L("update_title"))
        } footer: {
            if case .available = updates.state {
                // Being upfront beats a button that looks like it will install
                // and then just opens Safari.
                Text(L("update_ios_note"))
            }
        }
        // Throttled inside the service, so reopening Settings doesn't re-ask.
        .task { updates.check() }
    }

    // MARK: Help

    /// Contact routes, for when the app itself is the thing in the way.
    private var helpSection: some View {
        Section {
            contactRow(L("help_whatsapp"), SupportLinks.whatsAppNumber, SupportLinks.whatsAppURL)
            contactRow(L("help_email"), SupportLinks.email, SupportLinks.emailURL)

            // No link yet, so the row shows why rather than being a dead tap.
            if let group = SupportLinks.facebookGroup {
                contactRow(L("help_facebook"), group, URL(string: group))
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("help_facebook"))
                    Text(L("help_facebook_unavailable"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            contactRow(L("help_github"), SupportLinks.github, SupportLinks.githubURL)
        } header: {
            Text(L("settings_help"))
        } footer: {
            Text(L("help_intro"))
        }
    }

    private func contactRow(_ label: String, _ value: String, _ url: URL?) -> some View {
        Button {
            guard let url else { linkFailed = true; return }
            open(url)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).foregroundStyle(.primary)
                Text(value).font(.footnote).foregroundStyle(.tint)
            }
        }
    }

    private func open(_ url: URL) {
        // `canOpenURL` is unreliable for schemes not in LSApplicationQueriesSchemes,
        // so ask to open and report the refusal instead of predicting it.
        UIApplication.shared.open(url, options: [:]) { opened in
            if !opened { linkFailed = true }
        }
    }
}
