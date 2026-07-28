import Foundation

/// Mirrors `packages/core/src/matcher.ts`. This is the tested spec of blocking
/// behavior — the Safari Content Blocker rule list must stay consistent with it.
public enum Matcher {
    /// Normalize a hostname or URL into a bare, lowercased registrable-ish host:
    /// strips scheme, path, port, userinfo, a leading "www.", and trailing dot.
    /// Returns "" for input that has no host.
    public static func normalizeHost(_ input: String) -> String {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if s.isEmpty { return "" }

        // Strip scheme.
        if let schemeRange = s.range(of: #"^[a-z][a-z0-9+.-]*://"#, options: .regularExpression) {
            s.removeSubrange(schemeRange)
        }
        // Strip userinfo.
        if let at = s.firstIndex(of: "@") {
            s = String(s[s.index(after: at)...])
        }
        // Strip path/query/fragment.
        if let cut = s.firstIndex(where: { "/?#".contains($0) }) {
            s = String(s[..<cut])
        }
        // Strip port.
        if let colon = s.firstIndex(of: ":") {
            s = String(s[..<colon])
        }
        // Strip trailing dot and leading www.
        if s.hasSuffix(".") { s.removeLast() }
        if s.hasPrefix("www.") { s.removeFirst(4) }
        return s
    }

    /// True if `host` equals `domain` or is a subdomain of it.
    /// Both are expected to be normalized (see `normalizeHost`).
    public static func hostMatchesDomain(_ host: String, _ domain: String) -> Bool {
        if host == domain { return true }
        return host.hasSuffix("." + domain)
    }

    /// True if `host` matches any domain in the list (exact or subdomain).
    public static func hostInList<S: Sequence>(_ host: String, _ domains: S) -> Bool where S.Element == String {
        for d in domains {
            if !d.isEmpty && hostMatchesDomain(host, d) { return true }
        }
        return false
    }

    public struct BlockDecision: Equatable {
        public let blocked: Bool
        /// Which category caused the block, "custom" for the user block list, or nil.
        public let reason: String?

        public init(blocked: Bool, reason: String?) {
            self.blocked = blocked
            self.reason = reason
        }
    }

    /// Decide whether a host should be blocked given the user's settings and the
    /// per-category blocklists. Precedence: master off -> allow; custom allow ->
    /// allow; custom block -> block; otherwise first enabled category that lists it.
    public static func decide(
        host: String,
        settings: Settings,
        blocklists: [CategoryId: Set<String>]
    ) -> BlockDecision {
        let h = normalizeHost(host)
        if h.isEmpty || !settings.enabled { return BlockDecision(blocked: false, reason: nil) }

        if hostInList(h, settings.customAllow.map(normalizeHost)) {
            return BlockDecision(blocked: false, reason: nil)
        }
        if hostInList(h, settings.customBlock.map(normalizeHost)) {
            return BlockDecision(blocked: true, reason: "custom")
        }

        for c in Categories.all {
            guard settings.categories[c.id] == true else { continue }
            if hostInList(h, blocklists[c.id] ?? []) {
                return BlockDecision(blocked: true, reason: c.id.rawValue)
            }
        }
        return BlockDecision(blocked: false, reason: nil)
    }
}
