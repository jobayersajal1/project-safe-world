import CryptoKit
import Foundation

/// Salted digests of blocklist domains, mirroring `DomainHasher.kt`.
///
/// Used only by the system-wide tunnel, which matches against a `FuseFilter`.
/// The Safari content blocker takes a different path entirely — it needs the
/// literal domains, so it reads the scrambled lists via `Blocklists`.
///
/// Must stay byte-identical to the Kotlin object and to
/// `scripts/build-android-blocklists.ts`, which builds the filters both read.
/// `FuseFilterTests` pins the shared vector.
public enum DomainHasher {
    /// Prefixed before the domain so digests can't be looked up in a generic
    /// precomputed SHA-256 table. Ships in the app; not a secret.
    public static let salt = "safe-world:v1:"

    /// The first 8 bytes of the salted digest as a big-endian `UInt64` — the
    /// key a `FuseFilter` stores.
    public static func key(_ domain: String) -> UInt64 {
        let normalized = Matcher.normalizeHost(domain)
        guard !normalized.isEmpty else { return 0 }

        let digest = SHA256.hash(data: Data((salt + normalized).utf8))
        var key: UInt64 = 0
        for byte in digest.prefix(8) {
            key = (key << 8) | UInt64(byte)
        }
        return key
    }

    /// The host plus every parent domain at a label boundary, stopping before a
    /// bare single-label name.
    ///
    /// Hashing destroys the suffix relationship `hostMatchesDomain` relies on,
    /// so subdomain matching is recovered by enumerating candidates.
    ///
    /// The stop matters: no blocklist can contain "com" — the fetch script
    /// drops any entry without a dot — so looking one up can only ever be
    /// wrong. Against an exact set that was merely pointless; against a
    /// probabilistic filter a single collision on "com" would block every .com
    /// domain at once.
    public static func candidates(_ host: String) -> [String] {
        let normalized = Matcher.normalizeHost(host)
        guard !normalized.isEmpty else { return [] }

        var out = [normalized]
        var rest = Substring(normalized)
        while let dot = rest.firstIndex(of: ".") {
            let parent = rest[rest.index(after: dot)...]
            guard parent.contains(".") else { break }
            out.append(String(parent))
            rest = parent
        }
        return out
    }
}
