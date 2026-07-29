import Foundation
import SafeWorldCore

/// UserDefaults-backed store for `Settings`, the macOS analogue of `storage.ts`
/// in the Chrome extension. Whenever settings change it recomputes the
/// managed block in /etc/hosts and applies it via `HostsManager`.
@MainActor
final class SettingsStore: ObservableObject {
    @Published private(set) var settings: Settings
    @Published var lastSyncError: String?
    /// Domains fetched from `RemoteConfig.updateURL`, merged into the bundled
    /// blocklists in `syncHosts()`. Empty until the first successful fetch.
    @Published private(set) var remoteDomains: [CategoryId: [String]] = [:]

    private let defaults = UserDefaults.standard
    private static let settingsKey = "settings"
    private static let remoteDomainsKey = "remoteDomains"
    /// Which published payload was last applied — see `runRemoteUpdate`.
    private static let lastUpdateIdKey = "lastAppliedUpdateId"
    private var remoteUpdateTimer: Timer?

    init() {
        self.settings = Self.loadSettings(from: defaults)
        self.remoteDomains = Self.loadRemoteDomains(from: defaults)
        syncHosts()
        refreshRemoteIfDue()
        // RefreshRemoteIfDue no-ops until 24h since the last fetch, so this just needs to run
        // often enough to catch that boundary. Unlike iOS (which hooks `scenePhase == .active`),
        // this menu-bar app has no foreground/background transition to key off, so it needs its
        // own periodic timer instead.
        remoteUpdateTimer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in
            self?.refreshRemoteIfDue()
        }
    }

    private static func loadRemoteDomains(from defaults: UserDefaults) -> [CategoryId: [String]] {
        guard
            let data = defaults.data(forKey: remoteDomainsKey),
            let stored = try? JSONDecoder().decode([String: [String]].self, from: data)
        else { return [:] }
        var result: [CategoryId: [String]] = [:]
        for (key, value) in stored {
            guard let id = CategoryId(rawValue: key) else { continue }
            result[id] = value
        }
        return result
    }

    private static func loadSettings(from defaults: UserDefaults) -> Settings {
        guard
            let data = defaults.data(forKey: settingsKey),
            let stored = try? JSONDecoder().decode(Settings.self, from: data)
        else { return .defaults() }
        return Settings.withDefaults(stored)
    }

    func update(_ mutate: (inout Settings) -> Void) {
        var next = settings
        mutate(&next)
        settings = next
        if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: Self.settingsKey)
            publishForDaemon(data)
        }
        syncHosts()
    }

    /// Where `safeworld-dnsd` reads settings from.
    ///
    /// The daemon runs as root in a separate process and cannot see this app's UserDefaults, so
    /// the same encoded blob is also written to a file both can reach. The installer leaves that
    /// file owned by the installing user, which is what lets an unprivileged app update it.
    private static let daemonSettingsPath =
        "/Library/Application Support/SafeWorld/settings.json"

    /// Best-effort: the daemon may not be installed, and the app must never fail a settings change
    /// because of it. When the file cannot be written the daemon keeps its previous settings —
    /// which errs toward still blocking rather than silently stopping.
    private func publishForDaemon(_ data: Data) {
        let url = URL(fileURLWithPath: Self.daemonSettingsPath)
        guard FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path) else {
            return
        }
        try? data.write(to: url, options: .atomic)
    }

    func syncHosts() {
        var blocklists: [CategoryId: [String]] = [:]
        for id in CategoryId.allCases {
            blocklists[id] = Blocklists.domains(for: id) + (remoteDomains[id] ?? [])
        }
        let block = HostsFileBuilder.renderBlock(settings: settings, blocklists: blocklists)

        do {
            let existing = try HostsManager.currentContents()
            let updated = HostsFileBuilder.apply(managedBlock: block, toExistingContents: existing)
            try HostsManager.apply(newContents: updated)
            lastSyncError = nil
        } catch {
            lastSyncError = error.localizedDescription
        }
    }

    // MARK: Remote update
    //
    // Runs silently in the background against `RemoteConfig.updateURL` —
    // there is no user-facing control for this and no UI reflects its
    // outcome, by design (matches iOS). See RemoteConfig.swift.

    /// Fetches a fresh blocklist from `RemoteConfig.updateURL` if one is
    /// configured and the last fetch is older than `updateIntervalHours`.
    /// Safe to call repeatedly — it no-ops when not due.
    func refreshRemoteIfDue() {
        guard !RemoteConfig.updateURL.isEmpty else { return }
        let dueAt = settings.lastRemoteUpdate + RemoteConfig.updateIntervalHours * 3_600_000
        guard Date().timeIntervalSince1970 * 1000 >= dueAt else { return }
        Task { await runRemoteUpdate() }
    }

    private func runRemoteUpdate() async {
        do {
            let payload = try await RemoteUpdateService.fetch(from: RemoteConfig.updateURL)
            // Mark the check done either way, so an unchanged list isn't
            // re-fetched on every launch.
            update { $0.lastRemoteUpdate = Date().timeIntervalSince1970 * 1000 }

            // Already applied this exact set — rebuilding the content blocker
            // list for identical domains is pure work. A payload with no
            // updateId always applies, which is how older feeds behaved.
            if let id = payload.updateId, id == defaults.string(forKey: Self.lastUpdateIdKey) {
                return
            }

            // Throws on a list this app can't decode (e.g. Android's hashed
            // one), which the catch below reports rather than installing
            // entries that would match nothing.
            remoteDomains = try payload.domainsByCategory()
            if let data = try? JSONEncoder().encode(payload.domains) {
                defaults.set(data, forKey: Self.remoteDomainsKey)
            }
            if let id = payload.updateId { defaults.set(id, forKey: Self.lastUpdateIdKey) }
        } catch {
            // Deliberately silent: nothing in the UI reports remote update
            // state, so failures just mean we retry next time it's due.
            #if DEBUG
            print("Silent remote blocklist update failed: \(error.localizedDescription)")
            #endif
        }
    }
}
