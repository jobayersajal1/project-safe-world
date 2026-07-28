import SwiftUI
import UIKit
import SafeWorldCore

struct HomeView: View {
    @EnvironmentObject private var store: SettingsStore

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Protection", isOn: Binding(
                        get: { store.settings.enabled },
                        set: { on in store.update { $0.enabled = on } }
                    ))
                } footer: {
                    Text("Self-control model — you can turn protection off any time. There's no PIN lock.")
                }

                Section("Categories") {
                    ForEach(Categories.all, id: \.id) { category in
                        Toggle(isOn: Binding(
                            get: { store.settings.categories[category.id] ?? false },
                            set: { on in store.update { $0.categories[category.id] = on } }
                        )) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(category.label)
                                Text(category.description)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .disabled(!store.settings.enabled)
                    }
                }

                Section("Blocked today") {
                    Text("\(store.stats.blocked)")
                        .font(.system(size: 34, weight: .semibold, design: .rounded))
                }

                Section {
                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        Label("Enable Safari Extension", systemImage: "safari")
                    }
                } footer: {
                    Text("In Settings, go to Apps ▸ Safari ▸ Extensions and turn on Safe World.")
                }

                if let error = store.lastSyncError {
                    Section {
                        Text(error)
                            .foregroundStyle(.red)
                            .font(.caption)
                    }
                }
            }
            .navigationTitle("Safe World")
        }
    }
}
