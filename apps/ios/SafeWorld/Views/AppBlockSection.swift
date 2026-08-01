import SafeWorldCore
import SwiftUI

/// Naming individual apps by hand — what the three subject rows can't reach.
///
/// **The subject rows already block apps**; this is the long tail. The built-in catalogue only
/// knows the apps we thought of, and the one someone actually loses hours to is often not on it —
/// a new game, something regional, something released last week.
///
/// **iOS gives an app no way to list what else is installed**, by design — there is no equivalent
/// of Android's `QUERY_ALL_PACKAGES`. So where Android shows a picker over real installed apps,
/// here the user supplies the identifier and the help text says where to find it.
///
/// Blocking by app rather than by domain is the only rule an app cannot get around with
/// DNS-over-HTTPS or an address it already knows, and it costs the packet tunnel. That price is
/// stated once, by the consent alert `HomeView` owns — hence `withConsent` arriving as a parameter
/// rather than being decided here.
struct PickedAppsEditor: View {

    let apps: BlockedAppStore
    @ObservedObject var gate: PinGate
    let withConsent: (@escaping () -> Void) -> Void

    @State private var picked: [String] = []
    @State private var pickedText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L("appblock_choose_label"))

            TextEditor(text: $pickedText)
                .frame(minHeight: 60)
                .font(.system(.body, design: .monospaced))

            Text(L("appblock_picked_help"))
                .font(.caption)
                .foregroundStyle(.secondary)

            Text(L("appblock_count", "\(apps.blockedBundleIds.count)"))
                .font(.caption)
                .foregroundStyle(.secondary)

            Button(L("appblock_save"), action: save)
        }
        .onAppear {
            picked = apps.pickedBundleIds.sorted()
            pickedText = picked.joined(separator: "\n")
        }
    }

    /// Anything dropped from the list weakens protection, so it costs the PIN; adding is free.
    private func save() {
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
}
