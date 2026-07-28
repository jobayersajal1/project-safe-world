import Foundation

/// Builds the managed block of `/etc/hosts` (macOS) or
/// `C:\Windows\System32\drivers\etc\hosts` (Windows) entries for the domains
/// `Matcher.decide` would block, given the user's settings and blocklists.
///
/// Hosts-file blocking is coarser than `Matcher.decide`: a hosts file can only
/// sinkhole exact names it lists, not "this domain and every subdomain" the
/// way DNR/Safari content-blocker rules can. To cover the common case, each
/// blocked domain is emitted both bare and with a `www.` prefix. Precedence
/// (custom-allow beats everything, then custom-block, then categories) is
/// reproduced by running every candidate domain through `Matcher.decide`
/// rather than re-implementing the rules here.
public enum HostsFileBuilder {
    public static let beginMarker = "# BEGIN SAFEWORLD"
    public static let endMarker = "# END SAFEWORLD"
    private static let sinkhole = "0.0.0.0"

    /// Sorted, deduplicated list of hostnames (bare + `www.` variants) that
    /// should resolve to the sinkhole address under `settings`.
    ///
    /// Every candidate here is already a literal member of an enabled
    /// category's list or the custom-block list, so unlike `Matcher.decide`
    /// (built to answer "is this one host blocked?", not "list every blocked
    /// host") there's no need to re-derive category membership by scanning
    /// each category's full domain set per candidate — that turns an
    /// O(candidates) sweep into O(candidates × total bundled domains), which
    /// pegs a CPU core for tens of seconds against the real, ~45k-domain
    /// bundled lists. Only custom-allow (small, user-entered) needs a
    /// membership check per candidate.
    public static func blockedHosts(
        settings: Settings,
        blocklists: [CategoryId: [String]]
    ) -> [String] {
        guard settings.enabled else { return [] }

        var candidates = Set<String>()
        for c in Categories.all {
            guard settings.categories[c.id] == true else { continue }
            candidates.formUnion(blocklists[c.id] ?? [])
        }
        candidates.formUnion(settings.customBlock)

        let allow = settings.customAllow.map(Matcher.normalizeHost).filter { !$0.isEmpty }

        var blocked = Set<String>()
        for domain in candidates {
            let host = Matcher.normalizeHost(domain)
            guard !host.isEmpty, !Matcher.hostInList(host, allow) else { continue }
            blocked.insert(host)
            blocked.insert("www." + host)
        }
        return blocked.sorted()
    }

    /// Renders the managed block (including begin/end markers) to splice into
    /// a hosts file. Empty (marker-only) when nothing is blocked, so removing
    /// all categories cleanly removes all sinkhole entries on next sync.
    public static func renderBlock(
        settings: Settings,
        blocklists: [CategoryId: [String]]
    ) -> String {
        let hosts = blockedHosts(settings: settings, blocklists: blocklists)
        var lines = [beginMarker]
        for host in hosts { lines.append("\(sinkhole) \(host)") }
        lines.append(endMarker)
        return lines.joined(separator: "\n") + "\n"
    }

    /// Replaces the marker-delimited managed block inside existing hosts-file
    /// content, appending it if no markers are present yet. Preserves
    /// everything else in the file untouched.
    public static func apply(
        managedBlock: String,
        toExistingContents existing: String
    ) -> String {
        let lines = existing.components(separatedBy: "\n")
        guard
            let beginIndex = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == beginMarker }),
            let endIndex = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == endMarker }),
            endIndex >= beginIndex
        else {
            let base = existing.hasSuffix("\n") || existing.isEmpty ? existing : existing + "\n"
            return base + managedBlock
        }

        let trimmedBlock = managedBlock.hasSuffix("\n") ? String(managedBlock.dropLast()) : managedBlock

        var result = Array(lines[..<beginIndex])
        result.append(contentsOf: trimmedBlock.split(separator: "\n", omittingEmptySubsequences: false).map(String.init))
        result.append(contentsOf: lines[(endIndex + 1)...])
        return result.joined(separator: "\n")
    }
}
