import SwiftUI
import SafeWorldCore

struct SettingsView: View {
    @EnvironmentObject private var store: SettingsStore
    @StateObject private var updates = UpdateService()
    @Environment(\.openURL) private var openURL

    @State private var allowText = ""
    @State private var blockText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextEditor(text: $allowText)
                        .frame(minHeight: 100)
                        .font(.system(.body, design: .monospaced))
                } header: {
                    Text("Always allow")
                } footer: {
                    Text("One domain per line. Wins over every category and the block list below.")
                }

                Section {
                    TextEditor(text: $blockText)
                        .frame(minHeight: 100)
                        .font(.system(.body, design: .monospaced))
                } header: {
                    Text("Always block")
                }

                updateSection
            }
            .navigationTitle("Settings")
            .onAppear(perform: load)
            .onDisappear(perform: saveList)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: saveList)
                }
            }
        }
    }

    /// Installed version, and whatever the update check has found.
    ///
    /// The action is always "open the release page", never "install": iOS gives
    /// an app no way to install another build of itself. See `UpdateService`.
    @ViewBuilder
    private var updateSection: some View {
        Section {
            LabeledContent("Version", value: updates.installedVersion)

            switch updates.state {
            case .idle:
                Button("Check for updates") { updates.check(force: true) }

            case .checking:
                HStack {
                    ProgressView()
                    Text("Checking…").foregroundStyle(.secondary)
                }

            case .upToDate:
                Text("This is the latest version.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("Check for updates") { updates.check(force: true) }

            case .available(let update):
                Text("Version \(update.version) is available.")
                    .foregroundStyle(.tint)
                Button("View the release") { openURL(update.pageURL) }

            case .failed(let message):
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
                Button("Try again") { updates.check(force: true) }
            }
        } header: {
            Text("App version")
        } footer: {
            if case .available = updates.state {
                // Being upfront beats a button that looks like it will install
                // and then just opens Safari.
                Text("iOS apps can only be updated through the App Store, so this opens the release page.")
            }
        }
        // Throttled inside the service, so reopening Settings doesn't re-ask.
        .task { updates.check() }
    }

    private func load() {
        allowText = store.settings.customAllow.joined(separator: "\n")
        blockText = store.settings.customBlock.joined(separator: "\n")
    }

    private func parseList(_ text: String) -> [String] {
        text
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    private func saveList() {
        store.update {
            $0.customAllow = parseList(allowText)
            $0.customBlock = parseList(blockText)
        }
    }
}
