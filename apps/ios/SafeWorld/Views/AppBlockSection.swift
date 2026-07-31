import SafeWorldCore
import SwiftUI

/// Blocking whole apps, rather than the sites they load.
///
/// **Why this is a separate section from "Block more websites".** Everything above it is a Safari
/// content-blocker rule and costs nothing. This needs the packet tunnel running, because the only
/// place an app's identity is visible is on the packet itself
/// (`NEPacket.metadata.sourceAppSigningIdentifier`). It is a different bargain, and the user is
/// told before they take it rather than after their battery goes.
///
/// **Each group is its own switch, independent of the category above with the same name.** Blocking
/// instagram.com in Safari and stopping the Instagram app from reaching the network are different
/// decisions, and someone may want either one without the other.
struct AppBlockSection: View {

    @ObservedObject var store: SettingsStore
    @ObservedObject var gate: PinGate

    private let apps = BlockedAppStore()

    @State private var enabled: Set<AppGroups.Group> = []
    @State private var picked: [String] = []
    @State private var pickedText = ""
    @State private var showingConsent = false
    @State private var pendingChange: (() -> Void)?

    var body: some View {
        Section {
            ForEach(AppGroups.Group.allCases, id: \.rawValue) { group in
                Toggle(isOn: binding(for: group)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("\(group.rawValue)_label"))
                        Text(L("\(group.rawValue)_description"))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(!store.settings.enabled)
            }

            // iOS gives an app no way to list what else is installed — there is no equivalent of
            // Android's QUERY_ALL_PACKAGES, by design — so where Android shows a picker over real
            // installed apps, here the user supplies the identifier and the footer says where to
            // find it.
            TextEditor(text: $pickedText)
                .frame(minHeight: 60)
                .font(.system(.body, design: .monospaced))
                .disabled(!store.settings.enabled)

            Button(L("add_websites_save"), action: savePicked)
                .disabled(!store.settings.enabled)
        } header: {
            Text(L("appblock_title"))
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("appblock_intro"))
                Text(L("appblock_picked_help"))
                Text(L("appblock_count", "\(apps.blockedBundleIds.count)"))
            }
        }
        .onAppear {
            enabled = apps.enabledGroups
            picked = apps.pickedBundleIds.sorted()
            pickedText = picked.joined(separator: "\n")
        }
        .alert(L("appblock_consent_title"), isPresented: $showingConsent) {
            Button(L("appblock_consent_cancel"), role: .cancel) { pendingChange = nil }
            Button(L("appblock_consent_accept")) {
                apps.fullTunnelAcknowledged = true
                pendingChange?()
                pendingChange = nil
            }
        } message: {
            Text(L("appblock_consent_body"))
        }
    }

    private func binding(for group: AppGroups.Group) -> Binding<Bool> {
        Binding(
            get: { enabled.contains(group) },
            set: { on in
                let apply = {
                    apps.setGroup(group, blocked: on)
                    enabled = apps.enabledGroups
                }
                if on {
                    withConsent(apply)
                } else {
                    // Turning a group off unblocks apps, so it costs the PIN — the same rule the
                    // category switches and the custom block list follow.
                    gate.requiringPin(
                        title: L("\(group.rawValue)_off_pin_title"),
                        message: L("appblock_off_pin_message"),
                        action: apply
                    )
                }
            }
        )
    }

    /// Anything dropped from the list weakens protection, so it costs the PIN; adding is free.
    private func savePicked() {
        let next = parseDomainList(pickedText)
        let removed = !Set(picked).isSubset(of: Set(next))
        let apply = {
            apps.pickedBundleIds = Set(next)
            picked = next.sorted()
        }
        if removed {
            gate.requiringPin(
                title: L("appblock_remove_pin_title"),
                message: L("appblock_off_pin_message"),
                action: apply
            )
        } else {
            withConsent(apply)
        }
    }

    /// Turning any of this on is where the tunnel becomes necessary, so the warning goes here
    /// rather than on each switch. Shown once — after that the trade has been made knowingly.
    private func withConsent(_ apply: @escaping () -> Void) {
        if apps.fullTunnelAcknowledged {
            apply()
        } else {
            pendingChange = apply
            showingConsent = true
        }
    }
}
