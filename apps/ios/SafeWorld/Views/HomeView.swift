import SwiftUI
import UIKit
import SafeWorldCore

/// Status, how much is covered, and what else can be added.
///
/// Restructured to match `HomeScreen.kt`. The scam, gambling, and adult lists
/// have no switches — installing this app is the decision to block them, and a
/// toggle that turns gambling back on in a weak moment is the exact failure the
/// PIN exists to prevent. They are stated as a number instead. What *is* offered
/// is the other direction: opt-in categories and the user's own domains, because
/// adding to what's blocked never needs protecting from the user.
///
/// That asymmetry decides every PIN gate on this screen — **strengthening is
/// free, weakening costs the PIN.**
struct HomeView: View {
    @EnvironmentObject private var store: SettingsStore
    @EnvironmentObject private var pins: PinStore
    @EnvironmentObject private var gate: PinGate
    @StateObject private var tunnel = TunnelManager()

    @State private var blockText = ""

    var body: some View {
        NavigationStack {
            Form {
                protectionSection
                blockedCountSection
                optionalCategoriesSection
                addWebsitesSection
                safariSection
                tunnelSection

                if let error = store.lastSyncError {
                    Section {
                        Text(error).foregroundStyle(.red).font(.caption)
                    }
                }
            }
            .navigationTitle("Safe World")
            .onAppear { blockText = store.settings.customBlock.joined(separator: ", ") }
        }
    }

    // MARK: Protection

    private var protectionSection: some View {
        Section {
            Toggle(L("protection_title"), isOn: Binding(
                get: { store.settings.enabled },
                set: { on in
                    // Turning on goes straight through; only the direction that
                    // removes protection is gated, and `gate.apply` is what
                    // decides which direction this is.
                    gate.apply(
                        to: store,
                        title: L("protection_off_pin_title"),
                        message: L("protection_off_pin_message")
                    ) { $0.enabled = on }
                }
            ))
        } footer: {
            if !pins.hasPin {
                // Said here rather than in Settings, because this is the switch
                // the PIN is for.
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("pin_setup_required"))
                    Button(L("pin_setup_action")) {
                        gate.choosePin { pin in
                            pins.setPin(pin)
                            gate.route = .showRecoveryCode(
                                code: pins.issueRecoveryCode(),
                                isReplacement: false
                            )
                        }
                    }
                    .font(.footnote)
                }
            } else {
                Text(store.settings.enabled ? L("protection_on_ios") : L("protection_off"))
            }
        }
    }

    // MARK: How much is blocked

    private var blockedCountSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(L("blocked_headline", CountFormat.compact(store.blockedDomainCount)))
                    .font(.title2.weight(.semibold))
                Text(L("blocked_headline_detail", CountFormat.exact(store.blockedDomainCount)))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)

            LabeledContent(L("blocked_today"), value: CountFormat.exact(store.stats.blocked))
        }
    }

    // MARK: Opt-in categories

    private var optionalCategoriesSection: some View {
        Section(L("block_more_title")) {
            ForEach(Categories.optional, id: \.id) { category in
                let label = L("category_\(category.id.rawValue)_label")
                Toggle(isOn: Binding(
                    get: { store.settings.categories[category.id] ?? false },
                    set: { on in
                        gate.apply(
                            to: store,
                            title: L("category_off_pin_title", label),
                            message: L("category_off_pin_message")
                        ) { $0.categories[category.id] = on }
                    }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(label)
                        Text(L("category_\(category.id.rawValue)_description"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(!store.settings.enabled)
            }
        }
    }

    // MARK: The user's own list

    /// The block list lives here, not in Settings — it is the same `customBlock`
    /// either way, and two editors for one list is how they end up disagreeing.
    private var addWebsitesSection: some View {
        Section {
            TextEditor(text: $blockText)
                .frame(minHeight: 80)
                .font(.system(.body, design: .monospaced))

            Button(L("add_websites_save"), action: saveBlockList)
        } header: {
            Text(L("add_websites_label"))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("add_websites_help"))
                Text(Language.plural("add_websites_count", store.settings.customBlock.count))
            }
        }
    }

    /// Saving is free while the list only grows; dropping an entry unblocks
    /// something, so only that direction is gated — including the case where the
    /// count is unchanged because one domain was swapped for another.
    private func saveBlockList() {
        let next = parseDomainList(blockText)
        gate.apply(
            to: store,
            title: L("add_websites_remove_pin_title"),
            message: L("add_websites_remove_pin_message")
        ) { $0.customBlock = next }
    }

    // MARK: Safari

    private var safariSection: some View {
        Section {
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Label(L("safari_enable_action"), systemImage: "safari")
            }
        } footer: {
            Text(L("safari_enable_help"))
        }
    }

    // MARK: System-wide tunnel

    /// The optional stronger setting: Safari blocking covers Safari, this covers
    /// every app.
    ///
    /// It will not start in the Simulator and needs a `packet-tunnel-provider`
    /// entitlement that only a paid developer account can carry, so any failure
    /// is shown verbatim rather than swallowed. A switch that silently snaps back
    /// to off would read as a bug in the app rather than as a missing capability.
    private var tunnelSection: some View {
        Section {
            Toggle(L("tunnel_title"), isOn: Binding(
                get: { tunnel.isRunning },
                set: { on in
                    if on {
                        Task { await tunnel.start() }
                    } else {
                        gate.requiringPin(
                            title: L("protection_off_pin_title"),
                            message: L("protection_off_pin_message")
                        ) {
                            tunnel.stop()
                        }
                    }
                }
            ))

            if let error = tunnel.lastError {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("tunnel_unavailable")).font(.footnote)
                    Text(error).font(.caption).foregroundStyle(.red)
                }
            }
        } footer: {
            Text(L("tunnel_body"))
        }
    }
}

/// Comma-separated, the way `DomainInput.kt` parses the same field on Android.
func parseDomainList(_ text: String) -> [String] {
    text
        .split(whereSeparator: { $0 == "," || $0.isNewline })
        .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        .filter { !$0.isEmpty }
}
