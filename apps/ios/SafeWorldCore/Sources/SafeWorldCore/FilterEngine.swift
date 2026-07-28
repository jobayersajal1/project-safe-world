import Foundation

/// Decides whether a hostname is blocked, using memory-mapped filters rather
/// than in-memory domain sets.
///
/// This is `Matcher.decide`'s precedence expressed over filters — master off →
/// allow; custom allow → allow; custom block → block; otherwise the first
/// enabled category whose filter matches. `decide` itself takes
/// `[CategoryId: Set<String>]`, which cannot represent 4.5M domains inside a
/// Network Extension, so the rules are restated here rather than the data being
/// forced into a shape it doesn't fit.
///
/// Keeping the precedence identical matters: `decide` is the tested
/// cross-platform spec of blocking behaviour, and this must not become a second,
/// quietly different one. `FilterEngineTests` checks the two agree.
public final class FilterEngine {

    public enum Failure: Error, CustomStringConvertible {
        case noContainer
        case noFilters

        public var description: String {
            switch self {
            case .noContainer: return "App Group container unavailable"
            case .noFilters: return "no category filters could be loaded"
            }
        }
    }

    private let filters: [CategoryId: FuseFilter]
    private var settings: Settings

    /// Load every category filter from the App Group container.
    ///
    /// Throws when none load. That is deliberate: an engine with no filters
    /// answers "not blocked" to everything, which looks exactly like a healthy
    /// app that happens to be blocking nothing.
    public convenience init() throws {
        guard let container = AppGroup.containerURL() else { throw Failure.noContainer }
        try self.init(directory: container, settings: AppGroup.loadSharedSettings())
    }

    public init(directory: URL, settings: Settings) throws {
        var loaded: [CategoryId: FuseFilter] = [:]
        for id in CategoryId.allCases {
            let url = directory.appendingPathComponent("\(id.rawValue).filter")
            guard FileManager.default.fileExists(atPath: url.path) else { continue }
            // One bad file shouldn't take the others down with it.
            if let filter = try? FuseFilter(path: url.path) {
                loaded[id] = filter
            }
        }
        guard !loaded.isEmpty else { throw Failure.noFilters }
        self.filters = loaded
        self.settings = settings
    }

    /// Re-read settings without reloading the filters, which are immutable and
    /// expensive to map.
    public func update(settings: Settings) {
        self.settings = settings
    }

    /// How many domains are covered by the enabled categories.
    public var blockedDomainCount: Int {
        filters
            .filter { settings.categories[$0.key] == true }
            .reduce(0) { $0 + $1.value.size }
    }

    /// True if `host` should be blocked. Mirrors `Matcher.decide` exactly.
    public func isBlocked(_ host: String) -> Bool {
        let h = Matcher.normalizeHost(host)
        guard !h.isEmpty, settings.enabled else { return false }

        // The user's own lists are plaintext and always win, in this order.
        // They are small, local, and never published, so there is no reason to
        // put them behind a filter.
        if settings.customAllow.map(Matcher.normalizeHost).contains(where: {
            !$0.isEmpty && Matcher.hostMatchesDomain(h, $0)
        }) {
            return false
        }
        if settings.customBlock.map(Matcher.normalizeHost).contains(where: {
            !$0.isEmpty && Matcher.hostMatchesDomain(h, $0)
        }) {
            return true
        }

        // Hashing destroys the suffix relationship, so subdomain matching is
        // recovered by testing the host and each parent.
        let candidates = DomainHasher.candidates(h).map(DomainHasher.key)
        for category in Categories.all {
            guard settings.categories[category.id] == true,
                  let filter = filters[category.id] else { continue }
            if candidates.contains(where: { filter.contains($0) }) { return true }
        }
        return false
    }
}
