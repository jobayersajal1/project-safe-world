import SwiftUI

struct RootView: View {
    @State private var tab = 1
    var body: some View {
        TabView(selection: $tab) {
            HomeView()
                .tabItem { Label("Home", systemImage: "shield.fill") }
                .tag(0)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(1)
        }
    }
}
