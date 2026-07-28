import Foundation
import SafeWorldCore

/// App Group-backed store for `Settings` and `DailyStats`, the iOS analogue
/// of `storage.ts` in the Chrome extension. Whenever settings change it
/// rebuilds and re-publishes the Safari Content Blocker rule list.
@MainActor
final class SettingsStore: ObservableObject {
    @Published private(set) var settings: Settings
    @Published private(set) var stats: DailyStats
    @Published private(set) var remoteDomains: [CategoryId: [String]]
    @Published var lastSyncError: String?

    private let defaults: UserDefaults
    private static let settingsKey = "settings"
    private static let statsKey = "stats"
    private static let remoteDomainsKey = "remoteDomains"
    /// Which published payload was last applied — see `runRemoteUpdate`.
    private static let lastUpdateIdKey = "lastAppliedUpdateId"

    init() {
        let defaults = UserDefaults(suiteName: AppGroup.identifier) ?? .standard
        self.defaults = defaults
        self.settings = Self.loadSettings(from: defaults)
        self.stats = Self.loadStats(from: defaults)
        self.remoteDomains = Self.loadRemoteDomains(from: defaults)
        syncContentBlocker()
        refreshRemoteIfDue()
    }

    // MARK: Loading

    private static func todayKey() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = .current
        return f.string(from: Date())
    }

    private static func loadSettings(from defaults: UserDefaults) -> Settings {
        guard
            let data = defaults.data(forKey: settingsKey),
            let stored = try? JSONDecoder().decode(Settings.self, from: data)
        else { return .defaults() }
        return Settings.withDefaults(stored)
    }

    private static func loadStats(from defaults: UserDefaults) -> DailyStats {
        guard
            let data = defaults.data(forKey: statsKey),
            let stored = try? JSONDecoder().decode(DailyStats.self, from: data),
            stored.date == todayKey()
        else { return DailyStats(date: todayKey(), blocked: 0) }
        return stored
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

    // MARK: Mutating

    func update(_ mutate: (inout Settings) -> Void) {
        var next = settings
        mutate(&next)
        settings = next
        if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: Self.settingsKey)
        }
        syncContentBlocker()
    }

    func incrementBlockedToday() {
        let today = Self.todayKey()
        if stats.date != today { stats = DailyStats(date: today, blocked: 0) }
        stats.blocked += 1
        if let data = try? JSONEncoder().encode(stats) {
            defaults.set(data, forKey: Self.statsKey)
        }
    }

    func syncContentBlocker() {
        ContentBlockerSync.sync(settings: settings, remoteDomains: remoteDomains) { [weak self] error in
            self?.lastSyncError = error
        }
    }

    // MARK: Remote update
    //
    // Runs silently in the background against `RemoteConfig.updateURL` —
    // there is no user-facing control for this and no UI reflects its
    // outcome, by design. See RemoteConfig.swift.

    /// Fetches a fresh blocklist from `RemoteConfig.updateURL` if one is
    /// configured and the last fetch is older than `updateIntervalHours`.
    /// Safe to call repeatedly (e.g. on every foreground) — it no-ops when
    /// not due.
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
