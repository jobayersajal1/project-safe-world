import Foundation

/// Mirrors `packages/core/src/scramble.ts`. Keep the key, algorithm, and
/// format string in sync by hand — `ScrambleTests` pins shared vectors so a
/// drift fails loudly instead of silently matching nothing.
///
/// **Obfuscation, not encryption.** The key ships inside the app, so anyone who
/// unpacks it can reverse this, and the unscrambled domains are written into
/// the content blocker rule list on the device regardless. What it buys: the
/// file at the public URL isn't a readable list of gambling and adult sites.
///
/// iOS needs the reversible form (unlike Android, which matches one-way
/// digests) because Safari's content blocker matches from a rule list
/// containing literal domains.
public enum Scramble {
    /// Payload `format` for scrambled domains.
    public static let format = "xor-b64-v1"

    /// Payload `format` for Android's one-way digests, which are not domains.
    public static let hashedFormat = "sha256-128-hex"

    private static let key = Array("safe-world-list-v1".utf8)

    private static func xor(_ bytes: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(bytes.count)
        for (i, b) in bytes.enumerated() {
            out.append(b ^ key[i % key.count])
        }
        return out
    }

    public static func scramble(_ domain: String) -> String {
        let trimmed = domain.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if trimmed.isEmpty { return "" }
        return Data(xor(Array(trimmed.utf8))).base64EncodedString()
    }

    /// Returns "" for input that isn't valid base64, so one bad entry can't
    /// take down a whole fetched list.
    public static func unscramble(_ scrambled: String) -> String {
        if scrambled.isEmpty { return "" }
        guard let data = Data(base64Encoded: scrambled) else { return "" }
        return String(decoding: xor([UInt8](data)), as: UTF8.self)
    }
}
