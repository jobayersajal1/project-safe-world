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

    private let apps = BlockedAppStore()

    @State private var blockText = ""
    @State private var showingConsent = false
    @State private var pendingConsent: (() -> Void)?

    var body: some View {
        NavigationStack {
            Form {
                protectionSection
                blockedCountSection
                subjectsSection
                blockFurtherSection
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
            // Owned here rather than by the app rows, because two things now lead to the packet
            // tunnel — the subject rows and the picked-apps editor — and one dialog answered once
            // is the point.
            .alert(L("appblock_consent_title"), isPresented: $showingConsent) {
                Button(L("appblock_consent_cancel"), role: .cancel) { pendingConsent = nil }
                Button(L("appblock_consent_accept")) {
                    apps.fullTunnelAcknowledged = true
                    pendingConsent?()
                    pendingConsent = nil
                }
            } message: {
                Text(L("appblock_consent_body"))
            }
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

    /// **Says something different when protection is off**, because the same sentence would be a
    /// lie: nothing is being blocked, and a card announcing 4.4 million blocked sites above a switch
    /// that is off is exactly the kind of reassurance this app must never give. Off, it states the
    /// offer and points at the switch; on, it states the fact and points at what else can be added.
    private var blockedCountSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(L(
                    store.settings.enabled ? "blocked_headline" : "blocked_headline_off",
                    CountFormat.compact(store.blockedDomainCount)
                ))
                .font(.title2.weight(.semibold))

                if store.settings.enabled {
                    // The exact figure, under the rounded headline. The headline is the claim
                    // someone repeats; this is the number that makes it checkable.
                    Text(L("blocked_headline_detail", CountFormat.exact(store.blockedDomainCount)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text(L("blocked_more_hint"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)

            LabeledContent(L("blocked_today"), value: CountFormat.exact(store.stats.blocked))
        }
    }

    // MARK: One subject, one switch

    /// Each row owns the websites **and** the apps for its subject.
    ///
    /// **These used to be two rows and are deliberately one.** Somebody who wants
    /// social media gone does not want it gone in Safari and alive in the
    /// Instagram app; splitting it meant five switches to express one intention,
    /// and a half-set state that looked like protection.
    ///
    /// Games first — it is the one people come looking for.
    private var subjectsSection: some View {
        Section(L("block_more_title")) {
            ForEach(subjectOrder, id: \.id) { category in
                let group = AppGroups.Group.allCases.first { $0.category == category.id }
                let label = L("category_\(category.id.rawValue)_label")

                Toggle(isOn: Binding(
                    get: {
                        // On only when both halves are. A row reading "on" while the app half was
                        // off would claim to block Instagram on a phone where Instagram still
                        // worked — the one thing this app must never say.
                        (store.settings.categories[category.id] ?? false)
                            && (group == nil || apps.enabledGroups.contains(group!))
                    },
                    set: { on in setSubject(category.id, group: group, on: on, label: label) }
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

    private var subjectOrder: [CategoryMeta] {
        let preferred: [CategoryId] = [.games, .social, .entertainment]
        return Categories.optional.sorted {
            (preferred.firstIndex(of: $0.id) ?? preferred.count)
                < (preferred.firstIndex(of: $1.id) ?? preferred.count)
        }
    }

    /// Sets both halves together. Turning on costs the consent dialog once (the app half needs the
    /// packet tunnel); turning off costs the PIN, like every other weakening on this screen.
    private func setSubject(_ id: CategoryId, group: AppGroups.Group?, on: Bool, label: String) {
        let apply = {
            store.update { $0.categories[id] = on }
            if let group { apps.setGroup(group, blocked: on) }
        }
        if on {
            withConsent(needed: group != nil, apply)
        } else {
            gate.requiringPin(
                title: L("category_off_pin_title", L("category_\(id.rawValue)_subject")),
                message: L("category_off_pin_message"),
                action: apply
            )
        }
    }

    /// Shown once, before the first thing that needs the packet tunnel.
    private func withConsent(needed: Bool, _ apply: @escaping () -> Void) {
        if !needed || apps.fullTunnelAcknowledged {
            apply()
        } else {
            pendingConsent = apply
            showingConsent = true
        }
    }

    // MARK: Going further

    /// What the three rows above don't cover: a specific app, or a site nobody has listed.
    ///
    /// The block list lives here, not in Settings — it is the same `customBlock`
    /// either way, and two editors for one list is how they end up disagreeing.
    private var blockFurtherSection: some View {
        Section {
            PickedAppsEditor(apps: apps, gate: gate, withConsent: { withConsent(needed: true, $0) })
                .disabled(!store.settings.enabled)

            // Labelled in the row rather than by the section header, which now covers both this
            // and the apps editor above — two unlabelled text boxes one under the other is not a
            // form anyone can read.
            VStack(alignment: .leading, spacing: 6) {
                Text(L("add_websites_label"))

                TextEditor(text: $blockText)
                    .frame(minHeight: 80)
                    .font(.system(.body, design: .monospaced))

                Text(L("add_websites_help"))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text(Language.plural("add_websites_count", store.settings.customBlock.count))
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Button(L("add_websites_save"), action: saveBlockList)
            }
        } header: {
            Text(L("block_further_title"))
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
