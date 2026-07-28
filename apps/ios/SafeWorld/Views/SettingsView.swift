import SwiftUI
import SafeWorldCore

struct SettingsView: View {
    @EnvironmentObject private var store: SettingsStore

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
