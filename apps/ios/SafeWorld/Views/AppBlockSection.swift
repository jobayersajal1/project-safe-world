import SafeWorldCore
import SwiftUI

/// Picking individual apps, by name.
///
/// **The subject rows already block apps in groups**; this is for blocking one without the rest —
/// Instagram but not all of social media.
///
/// **Why a fixed list rather than the phone's real one.** iOS gives an app no way to enumerate
/// what's installed, by design; there is no equivalent of Android's `QUERY_ALL_PACKAGES`, so the
/// Android build's picker over real installed apps cannot be built here. What *can* be shown is the
/// catalogue Safe World already ships, by label. That misses the long tail Android covers, which is
/// a genuine loss — but the alternative this replaced asked people to type `com.burbn.instagram`
/// into a text box, and nobody outside this repo knows an app by its bundle identifier.
struct PickedAppsEditor: View {

    let apps: BlockedAppStore
    @ObservedObject var gate: PinGate
    let withConsent: (@escaping () -> Void) -> Void

    @State private var picked: Set<String> = []

    var body: some View {
        NavigationLink {
            AppPickerList(picked: $picked, onCommit: commit)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("appblock_choose_label"))
                Text(L("appblock_count", "\(apps.blockedBundleIds.count)"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .onAppear { picked = apps.pickedBundleIds }
    }

    /// Anything dropped weakens protection, so it costs the PIN; adding is free beyond the one-time
    /// consent that every route to the packet tunnel goes through.
    private func commit(_ next: Set<String>) {
        let removed = !apps.pickedBundleIds.isSubset(of: next)
        let apply = {
            apps.pickedBundleIds = next
            picked = next
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
}

/// The catalogue, tickable, grouped the way the home screen groups it.
///
/// Applied when the screen is left rather than per tap, so removing three apps asks for the PIN
/// once instead of three times — the same bargain `AppPickerDialog` strikes on Android.
private struct AppPickerList: View {

    @Binding var picked: Set<String>
    let onCommit: (Set<String>) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var working: Set<String> = []

    var body: some View {
        List {
            ForEach(AppGroups.Group.allCases, id: \.rawValue) { group in
                Section(L("category_\(group.category.rawValue)_subject")) {
                    ForEach(AppGroups.all.filter { $0.group == group }, id: \.bundleId) { entry in
                        Button {
                            if working.contains(entry.bundleId) {
                                working.remove(entry.bundleId)
                            } else {
                                working.insert(entry.bundleId)
                            }
                        } label: {
                            HStack {
                                // The app's own name, never its bundle identifier — that string is
                                // for the tunnel to match on, not for anyone to read.
                                Text(entry.label).foregroundStyle(.primary)
                                Spacer()
                                if working.contains(entry.bundleId) {
                                    Image(systemName: "checkmark").foregroundStyle(.tint)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(L("appblock_choose_label"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("appblock_save")) {
                    onCommit(working)
                    dismiss()
                }
            }
        }
        .onAppear { working = picked }
    }
}
