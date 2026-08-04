import Foundation
import SafeWorldCore

/// UserDefaults-backed store for `Settings`, the macOS analogue of `storage.ts`
/// in the Chrome extension. Whenever settings change it recomputes the
/// managed block in /etc/hosts and applies it via `HostsManager`.
@MainActor
final class SettingsStore: ObservableObject {
    /// The one instance.
    ///
    /// Both the `App` scene and the `NSApplicationDelegate` need this store — the scene to draw
    /// the menu, the delegate to decide at launch whether to put up the setup window — and two
    /// instances would mean setup writing its answers into a store the menu never reads. Same
    /// reason `SettingsStore.get(context)` is a singleton on Android.
    static let shared = SettingsStore()

    @Published private(set) var settings: Settings
    @Published var lastSyncError: String?
    /// Domains fetched from `RemoteConfig.updateURL`, merged into the bundled
    /// blocklists in `syncHosts()`. Empty until the first successful fetch.
    @Published private(set) var remoteDomains: [CategoryId: [String]] = [:]

    /// Whether to show the dark theme. **Off by default, and the system is not consulted.**
    ///
    /// Port of `SettingsStore.darkTheme` on iOS and Android, down to the default. SwiftUI would
    /// follow the Mac's appearance for free, and following it reads badly here for the same reason
    /// it does on a phone: two people opening the same app would see different things with no way
    /// to know which, and a parent checking a child's Mac would find an app that does not look like
    /// the one on theirs. Light is what it is; dark is what you ask for.
    @Published var darkTheme: Bool = false {
        didSet {
            guard darkTheme != oldValue else { return }
            defaults.set(darkTheme, forKey: Self.darkThemeKey)
        }
    }

    /// How far through first-run setup the user has got.
    ///
    /// Persisted rather than held in view state, exactly as on iOS and Android: the setup window
    /// is rebuilt whenever the theme flips, and a step in `@State` would reset to the first
    /// question.
    @Published var onboardingStep: Int = 0 {
        didSet {
            guard onboardingStep != oldValue else { return }
            defaults.set(onboardingStep, forKey: Self.onboardingStepKey)
        }
    }

    /// Whether setup has been finished.
    ///
    /// The default carries installs that predate the wizard: they have settings stored and no
    /// step, so they are treated as done rather than dragged back through setup. **The step half
    /// of that test is the half that matters** — keying only on "has been used before" meant
    /// someone who answered the first question and quit came back with setup considered finished,
    /// silently skipping the rest. Same bug, same fix, as the other two platforms.
    @Published private(set) var onboardingComplete: Bool = false

    func completeOnboarding() {
        defaults.set(true, forKey: Self.onboardingDoneKey)
        onboardingComplete = true
        // The first hosts write of this install's life. Everything before now was suppressed —
        // see `syncHosts()`.
        syncHosts()
    }

    private let defaults = UserDefaults.standard
    private static let settingsKey = "settings"
    private static let darkThemeKey = "darkTheme"
    private static let onboardingStepKey = "onboardingStep"
    private static let onboardingDoneKey = "onboardingComplete"
    private static let remoteDomainsKey = "remoteDomains"
    /// Which published payload was last applied — see `runRemoteUpdate`.
    private static let lastUpdateIdKey = "lastAppliedUpdateId"
    private var remoteUpdateTimer: Timer?

    init() {
        self.settings = Self.loadSettings(from: defaults)
        self.remoteDomains = Self.loadRemoteDomains(from: defaults)
        // `bool(forKey:)` is false for a key that was never written, which is the default we want
        // anyway — a fresh install opens light.
        self.darkTheme = defaults.bool(forKey: Self.darkThemeKey)
        self.onboardingStep = defaults.integer(forKey: Self.onboardingStepKey)
        self.onboardingComplete = defaults.object(forKey: Self.onboardingDoneKey) as? Bool
            ?? (defaults.data(forKey: Self.settingsKey) != nil
                && defaults.object(forKey: Self.onboardingStepKey) == nil)
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
        else { return enforceMandatoryCategories(.defaults()) }
        return enforceMandatoryCategories(Settings.withDefaults(stored))
    }

    /// Forces every non-optional category on.
    ///
    /// Port of `enforceMandatoryCategories` on iOS and Android. Scam, gambling, and adult are the
    /// protection promise — installing the app is the decision to block them — so they are not a
    /// thing the UI offers to turn off, and a stored value that says otherwise (an older build of
    /// this app, which did offer the switch, or an edited plist) must not be able to leave one off.
    ///
    /// Applied on load *and* on every mutation, so there is no window where a write puts settings
    /// into a state the loader would have corrected.
    private static func enforceMandatoryCategories(_ settings: Settings) -> Settings {
        var result = settings
        for category in Categories.all where !category.optional {
            result.categories[category.id] = true
        }
        return result
    }

    // MARK: How much is blocked

    /// Domains covered by the currently enabled categories, plus the user's own.
    ///
    /// Read from the bundled filter headers rather than by counting the JSON lists, for the same
    /// reason iOS does it: the filters carry the full uncapped corpus while the JSON is the capped
    /// subset, and the headline should state what the app knows about.
    ///
    /// **This is the app's own figure, not the daemon's.** `DaemonController.blockedDomainCount`
    /// counts the filters staged under `/Library/Application Support`, which only exist once the
    /// daemon is installed; this one is the bundled corpus and is answerable before that.
    ///
    /// Computed once and cached — the bundle cannot change under a running app, so only the
    /// enabled set has to be re-read.
    private static let bundledCounts: [CategoryId: Int] = {
        var counts: [CategoryId: Int] = [:]
        for id in CategoryId.allCases {
            guard let url = Blocklists.bundledFilterURL(for: id) else { continue }
            counts[id] = FuseFilter.entryCount(atPath: url.path) ?? 0
        }
        return counts
    }()

    var blockedDomainCount: Int { blockedDomainCount(ifEnabled: settings.enabled) }

    /// The count as it *would* be if protection were on.
    ///
    /// The off-state card needs this: it offers what turning the switch on would buy, and offering
    /// "0 sites" is not an offer. Everything else wants the honest zero, which is what the
    /// property above gives.
    func blockedDomainCount(ifEnabled enabled: Bool) -> Int {
        guard enabled else { return 0 }
        let bundled = Self.bundledCounts
            .filter { settings.categories[$0.key] == true }
            .reduce(0) { $0 + $1.value }
        let remote = remoteDomains
            .filter { settings.categories[$0.key] == true }
            .reduce(0) { $0 + $1.value.count }
        return bundled + remote + settings.customBlock.count
    }

    func update(_ mutate: (inout Settings) -> Void) {
        var next = settings
        mutate(&next)
        settings = Self.enforceMandatoryCategories(next)
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

    /// Recomputes the managed block in /etc/hosts and applies it.
    ///
    /// **Does nothing until setup is finished, and that guard is the whole point.** Writing
    /// /etc/hosts needs an admin password, which this asks for via `osascript` — synchronously.
    /// Called from `init`, that put a password prompt on screen *before the app had drawn
    /// anything*, blocking the main thread so the setup window could not appear behind it either.
    /// A brand-new user's first experience of this app was an unexplained authentication dialog
    /// from an app with no visible window, which is indistinguishable from malware.
    ///
    /// It was also asking for the wrong thing: mid-setup the user has not yet said which optional
    /// categories they want, so the file it wanted a password to write was not the file they were
    /// about to ask for. Setup finishes, then one write — see `completeOnboarding()`.
    func syncHosts() {
        guard onboardingComplete else { return }

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
