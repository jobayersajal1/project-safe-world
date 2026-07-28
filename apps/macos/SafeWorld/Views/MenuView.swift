import SwiftUI
import AppKit
import SafeWorldCore

struct MenuView: View {
    @EnvironmentObject private var store: SettingsStore
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

            Button("Quit Safe World") {
                NSApp.terminate(nil)
            }
        }
        .padding(16)
        .frame(width: 300)
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
}
