import Foundation

/// Mirrors `packages/core/src/types.ts` (`Settings`) and `settings.ts`.
public struct Settings: Codable, Equatable, Sendable {
    /// Master switch. When false, nothing is blocked.
    public var enabled: Bool
    /// Per-category enable flags.
    public var categories: [CategoryId: Bool]
    /// Domains the user always allows, even if a category would block them.
    public var customAllow: [String]
    /// Domains the user always blocks, regardless of category.
    public var customBlock: [String]
    /// URL to fetch remote list updates from. Empty string disables remote updates.
    public var remoteUpdateUrl: String
    /// How often (in hours) to fetch remote updates.
    public var remoteUpdateIntervalHours: Int
    /// Timestamp (ms since epoch) of the last successful remote update, or 0 if never.
    public var lastRemoteUpdate: Double

    public init(
        enabled: Bool,
        categories: [CategoryId: Bool],
        customAllow: [String],
        customBlock: [String],
        remoteUpdateUrl: String,
        remoteUpdateIntervalHours: Int,
        lastRemoteUpdate: Double
    ) {
        self.enabled = enabled
        self.categories = categories
        self.customAllow = customAllow
        self.customBlock = customBlock
        self.remoteUpdateUrl = remoteUpdateUrl
        self.remoteUpdateIntervalHours = remoteUpdateIntervalHours
        self.lastRemoteUpdate = lastRemoteUpdate
    }

    public static func defaults() -> Settings {
        var categories: [CategoryId: Bool] = [:]
        for c in Categories.all { categories[c.id] = c.defaultEnabled }
        return Settings(
            enabled: true,
            categories: categories,
            customAllow: [],
            customBlock: [],
            remoteUpdateUrl: "",
            remoteUpdateIntervalHours: 24,
            lastRemoteUpdate: 0
        )
    }

    /// Merge stored (possibly partial/older) settings over the current defaults,
    /// the same precedence as `withDefaults` in packages/core/src/settings.ts.
    public static func withDefaults(_ stored: Settings?) -> Settings {
        guard var merged = stored else { return .defaults() }
        var categories = Settings.defaults().categories
        for (id, on) in merged.categories { categories[id] = on }
        merged.categories = categories
        return merged
    }
}

/// Mirrors `packages/core/src/types.ts` (`DailyStats`).
public struct DailyStats: Codable, Equatable, Sendable {
    /// Local date key, e.g. "2026-07-26".
    public var date: String
    /// Number of navigations blocked on that date.
    public var blocked: Int

    public init(date: String, blocked: Int) {
        self.date = date
        self.blocked = blocked
    }
}
