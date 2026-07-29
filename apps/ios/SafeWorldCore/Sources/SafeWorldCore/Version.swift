import Foundation

/// Ordering for the app's own version strings, used to decide whether a
/// published release is actually newer than what's installed.
///
/// Mirrors `apps/android/core/.../Version.kt`; both pin the same vectors in
/// their tests, because the two sides answering differently would mean one
/// platform nagging about an update that isn't one — or worse, staying quiet
/// about one that is.
///
/// Deliberately tolerant rather than strict semver: the input is a git tag
/// (`v0.2.0`) on one side and a `CFBundleShortVersionString` on the other, and
/// neither is guaranteed to be well-formed. Anything unparseable reads as 0
/// rather than trapping, so a malformed tag can't crash the update check — it
/// just fails to look newer.
public enum Version {
    /// Standard three-way compare: `.orderedAscending` when `a` is older than
    /// `b`, `.orderedSame` when they are the same release, `.orderedDescending`
    /// when `a` is newer.
    public static func compare(_ a: String, _ b: String) -> ComparisonResult {
        let (coreA, preA) = split(a)
        let (coreB, preB) = split(b)

        for i in 0..<max(coreA.count, coreB.count) {
            // Missing trailing components are 0, so "1.2" == "1.2.0".
            let lhs = i < coreA.count ? coreA[i] : 0
            let rhs = i < coreB.count ? coreB[i] : 0
            if lhs != rhs { return lhs < rhs ? .orderedAscending : .orderedDescending }
        }

        // Same numeric core: a pre-release precedes the release it leads up to,
        // so 1.0.0-beta < 1.0.0. Without this an installed 1.0.0 would be
        // offered 1.0.0-beta as an "update".
        switch (preA, preB) {
        case (nil, nil): return .orderedSame
        case (nil, _): return .orderedDescending
        case (_, nil): return .orderedAscending
        case let (lhs?, rhs?):
            // Two pre-releases of the same core compare lexically. A
            // simplification — real semver compares dot-separated identifiers,
            // numeric ones numerically — but this project has never shipped
            // one, and the fallback only ever mis-orders two pre-releases
            // against each other.
            if lhs == rhs { return .orderedSame }
            return lhs < rhs ? .orderedAscending : .orderedDescending
        }
    }

    /// True when `candidate` is a release worth offering to someone on `current`.
    public static func isNewer(_ candidate: String, than current: String) -> Bool {
        compare(candidate, current) == .orderedDescending
    }

    /// Splits "v1.2.3-beta.1+build5" into ([1, 2, 3], "beta.1").
    /// Build metadata is dropped: semver says it takes no part in precedence.
    private static func split(_ raw: String) -> ([Int], String?) {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v") || s.hasPrefix("V") { s.removeFirst() }
        if let plus = s.firstIndex(of: "+") { s = String(s[..<plus]) }

        var pre: String?
        if let hyphen = s.firstIndex(of: "-") {
            let tail = String(s[s.index(after: hyphen)...])
            pre = tail.isEmpty ? nil : tail
            s = String(s[..<hyphen])
        }

        let core = s.split(separator: ".", omittingEmptySubsequences: false).map { part -> Int in
            // prefix(while:) so "3rc" reads as 3 rather than collapsing to 0 and
            // silently losing an ordering the string plainly implies.
            Int(part.prefix(while: \.isNumber)) ?? 0
        }
        return (core, pre)
    }
}
