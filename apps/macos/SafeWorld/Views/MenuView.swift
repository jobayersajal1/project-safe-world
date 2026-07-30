import SwiftUI
import AppKit
import SafeWorldCore

struct MenuView: View {
    @EnvironmentObject private var store: SettingsStore
    @StateObject private var updates = UpdateService()
    @StateObject private var daemon = DaemonController()
    @State private var allowText = ""
    @State private var blockText = ""
    @State private var listsExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Safe World")
                .font(.headline)

            Toggle("Protection", isOn: Binding(
                get: { store.settings.enabled },
                set: { on in store.update { $0.enabled = on } }
            ))

            Text("Self-control model — you can turn protection off any time. There's no PIN lock.")
                .font(.caption)
                .foregroundStyle(.secondary)

            deviceWideSection

            Divider()

            ForEach(Categories.all, id: \.id) { category in
                Toggle(isOn: Binding(
                    get: { store.settings.categories[category.id] ?? false },
                    set: { on in store.update { $0.categories[category.id] = on } }
                )) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(category.label)
                        Text(category.description)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .disabled(!store.settings.enabled)
            }

            Divider()

            DisclosureGroup("Custom lists", isExpanded: $listsExpanded) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Always allow (one per line)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextEditor(text: $allowText)
                        .font(.system(.caption, design: .monospaced))
                        .frame(height: 60)
                        .border(Color.secondary.opacity(0.3))

                    Text("Always block (one per line)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextEditor(text: $blockText)
                        .font(.system(.caption, design: .monospaced))
                        .frame(height: 60)
                        .border(Color.secondary.opacity(0.3))

                    Button("Save lists", action: saveLists)
                }
                .padding(.top, 4)
            }
            .onAppear(perform: loadLists)

            if let error = store.lastSyncError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Divider()

            updateSection

            Divider()

            Button("Quit Safe World") {
                NSApp.terminate(nil)
            }
        }
        .padding(16)
        .frame(width: 300)
    }

    /// Installed version, and whatever the update check has found.
    @ViewBuilder
    private var updateSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Version \(updates.installedVersion)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()

                switch updates.state {
                case .idle, .upToDate, .failed:
                    Button("Check", action: { updates.check(force: true) })
                        .buttonStyle(.link)
                        .font(.caption)
                case .checking, .downloading, .downloaded, .available:
                    EmptyView()
                }
            }

            switch updates.state {
            case .idle:
                EmptyView()

            case .checking:
                Text("Checking…").font(.caption).foregroundStyle(.secondary)

            case .upToDate:
                Text("This is the latest version.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

            case .available(let update):
                Text("Version \(update.version) is available\(sizeSuffix(update)).")
                    .font(.caption)
                    .foregroundStyle(.tint)
                Button(update.assetURL != nil ? "Download and install" : "View the release") {
                    updates.download(update)
                }

            case .downloading(_, let fraction):
                Text("Downloading…").font(.caption).foregroundStyle(.secondary)
                // Indeterminate until the size is known, rather than a bar
                // frozen at zero.
                if let fraction {
                    ProgressView(value: fraction)
                } else {
                    ProgressView().progressViewStyle(.linear)
                }

            case .downloaded:
                Text("Downloaded. Drag Safe World to Applications to finish, then reopen it.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

            case .failed(let message):
                Text(message).font(.caption).foregroundStyle(.red)
            }
        }
        // Throttled inside the service, so reopening the menu doesn't re-ask.
        .task { updates.check() }
    }

    private func sizeSuffix(_ update: AvailableUpdate) -> String {
        guard update.sizeBytes > 0 else { return "" }
        return " (\(ByteCountFormatter.string(fromByteCount: update.sizeBytes, countStyle: .file)))"
    }

    private func loadLists() {
        allowText = store.settings.customAllow.joined(separator: "\n")
        blockText = store.settings.customBlock.joined(separator: "\n")
    }

    private func parseList(_ text: String) -> [String] {
        text
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    private func saveLists() {
        store.update {
            $0.customAllow = parseList(allowText)
            $0.customBlock = parseList(blockText)
        }
    }

    /// Turning the hosts-file sinkhole into a real device-wide resolver.
    ///
    /// The distinction is worth stating in the UI rather than hiding, because the two differ by more
    /// than a factor of thirty: the hosts file carries the capped 150,000 domains and can only match
    /// exact names, while the daemon matches memory-mapped fuse filters holding 4,482,470. Windows
    /// already does this by default via `ProxyController`; macOS could not, because installing the
    /// daemon needs root and the app had no way to ask.
    @ViewBuilder
    private var deviceWideSection: some View {
        Divider()

        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Device-wide blocking").font(.subheadline.weight(.medium))
                Spacer()
                if daemon.isBusy { ProgressView().controlSize(.small) }
            }

            if daemon.isRunning {
                Text("Blocking \(daemon.blockedDomainCount.formatted()) sites in every app.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                // Deleting the app does not stop the daemon — it is a root LaunchDaemon with
                // KeepAlive and does not depend on the app existing. That is deliberate, because a
                // one-step bypass would defeat the point, but it must not be a trap: say so, and say
                // where the way out lives.
                Text("Deleting Safe World won't stop this. Turn it off here first, or run:")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("sudo '/Library/Application Support/SafeWorld/uninstall.sh'")
                    .font(.system(.caption2, design: .monospaced))
                    .textSelection(.enabled)
                    .foregroundStyle(.secondary)
                Button("Turn off") { Task { await daemon.uninstall() } }
                    .disabled(daemon.isBusy)
            } else {
                // States the trade plainly: what it costs (an admin password) and what it buys.
                Text("Blocking is limited to a smaller list right now, and only where the hosts file is read. Turning this on filters every app on this Mac.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Turn on (asks for your password)") {
                    Task { await daemon.install(settings: store.settings) }
                }
                .disabled(daemon.isBusy)
            }

            if let error = daemon.lastError, error != "Cancelled." {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
        // A menu-bar popover is rebuilt each time it opens, so this is enough to keep the state and
        // the count current without observing settings.
        .onAppear { daemon.refresh() }
    }
}
