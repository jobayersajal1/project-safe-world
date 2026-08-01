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

    /// The chosen UI language tag, or `""` for "follow the system".
    /// Published so changing it redraws every view that reads a string.
    @Published var language: String = Language.stored {
        didSet {
            guard language != oldValue else { return }
            Language.stored = language
            Language.invalidate()
        }
    }

    /// Whether to show the dark theme. **Off by default, and the system is not
    /// consulted.**
    ///
    /// Port of `SettingsStore.darkTheme` on Android, down to the default, and the
    /// one place iOS deliberately overrides a platform convention: SwiftUI would
    /// follow the phone for free, and following it reads badly here. Two people
    /// opening the same app would see different things with no way to know which,
    /// and a parent checking a child's phone would find an app that does not look
    /// like the one on theirs. Light is what it is; dark is what you ask for, and
    /// having asked, it stays asked.
    @Published var darkTheme: Bool = false {
        didSet {
            guard darkTheme != oldValue else { return }
            defaults.set(darkTheme, forKey: Self.darkThemeKey)
        }
    }

    private let defaults: UserDefaults
    private static let settingsKey = "settings"
    private static let darkThemeKey = "darkTheme"
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
        // `bool(forKey:)` is false for a key that was never written, which is the
        // default we want anyway — a fresh install opens light.
        self.darkTheme = defaults.bool(forKey: Self.darkThemeKey)
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
        else { return enforceMandatoryCategories(.defaults()) }
        return enforceMandatoryCategories(Settings.withDefaults(stored))
    }

    /// Forces every non-optional category on.
    ///
    /// Port of `SettingsStore.enforceMandatoryCategories` on Android. Scam,
    /// gambling, and adult are the protection promise — installing the app is the
    /// decision to block them — so they are not a thing the UI offers to turn
    /// off, and a stored value that says otherwise (an older build, a restored
    /// backup, an edited container) must not be able to leave one off.
    ///
    /// Applied on load *and* on every mutation, so there is no window where a
    /// write puts settings into a state the loader would have corrected.
    private static func enforceMandatoryCategories(_ settings: Settings) -> Settings {
        var result = settings
        for category in Categories.all where !category.optional {
            result.categories[category.id] = true
        }
        return result
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
        settings = Self.enforceMandatoryCategories(next)
        if let data = try? JSONEncoder().encode(settings) {
            defaults.set(data, forKey: Self.settingsKey)
        }
        syncContentBlocker()
    }

    // MARK: How much is blocked

    /// Domains covered by the currently enabled categories, plus the user's own.
    ///
    /// Read from the bundled filter headers rather than by counting the JSON
    /// lists: the filters carry the full uncapped corpus (~4.5M) while the JSON
    /// is the capped subset Safari's rule compiler can take, and the headline is
    /// meant to state what the app knows about, which is the former. Nine bytes
    /// of header per category — see `FuseFilter.entryCount(atPath:)`.
    ///
    /// Computed once and cached; the file contents cannot change under a running
    /// app, so only the enabled set has to be re-read.
    private static let bundledCounts: [CategoryId: Int] = {
        var counts: [CategoryId: Int] = [:]
        for id in CategoryId.allCases {
            guard let url = Blocklists.bundledFilterURL(for: id) else { continue }
            counts[id] = FuseFilter.entryCount(atPath: url.path) ?? 0
        }
        return counts
    }()

    var blockedDomainCount: Int {
        guard settings.enabled else { return 0 }
        let bundled = Self.bundledCounts
            .filter { settings.categories[$0.key] == true }
            .reduce(0) { $0 + $1.value }
        let remote = remoteDomains
            .filter { settings.categories[$0.key] == true }
            .reduce(0) { $0 + $1.value.count }
        return bundled + remote + settings.customBlock.count
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
