import Foundation

private struct BlocklistFile: Decodable {
    let category: String
    /// Absent on a file predating scrambling; see the note in `domains(for:)`.
    let format: String?
    let domains: [String]
}

/// Mirrors `packages/core/src/blocklists.ts`. Domains are bundled as JSON
/// resources written by `npm run build:ios`, **scrambled** — an unpacked `.ipa`
/// yields no readable list of blocked sites. They are reversed here, in memory,
/// on first access.
public enum Blocklists {
    /// Cached because unscrambling ~85k entries is not free, and the content
    /// blocker rebuilds its rule list on every settings change.
    private static var cache: [CategoryId: [String]] = [:]
    private static let lock = NSLock()

    /// Bundled domains for a category (as authored, not normalized).
    public static func domains(for category: CategoryId) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        if let cached = cache[category] { return cached }

        guard
            // No `subdirectory:` — Package.swift uses `.process`, which flattens
            // these to the bundle root. See the note there for why.
            let url = Bundle.module.url(forResource: category.rawValue, withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let file = try? JSONDecoder().decode(BlocklistFile.self, from: data)
        else {
            return []
        }

        // A file with no `format` predates scrambling and is already plaintext;
        // anything else this build can't reverse yields nothing rather than
        // garbage domains that would silently block the wrong sites.
        let domains: [String]
        switch file.format {
        case .none:
            domains = file.domains
        case Scramble.format:
            domains = file.domains.map(Scramble.unscramble).filter { !$0.isEmpty }
        default:
            domains = []
        }

        cache[category] = domains
        return domains
    }

    /// Bundled domains for every category as Sets, for use with `Matcher.decide`.
    public static func all() -> [CategoryId: Set<String>] {
        var result: [CategoryId: Set<String>] = [:]
        for id in CategoryId.allCases { result[id] = Set(domains(for: id)) }
        return result
    }
}
