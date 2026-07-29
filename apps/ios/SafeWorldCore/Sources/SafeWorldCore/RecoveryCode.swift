import Foundation

/// The escape hatch for a forgotten PIN.
///
/// There is no account and no server, so there is nobody to prove your identity
/// to — the only thing that can authorize a PIN reset is a secret the user
/// already holds. So one is generated at setup, shown once, and stored hashed
/// exactly like the PIN itself.
///
/// It is deliberately long. The PIN is short because it's typed constantly and
/// is protected by an attempt limit; this is typed approximately once, so it can
/// afford ~100 bits and doesn't need a cooldown to stay unguessable — which
/// matters, because the cooldown is usually *why* someone reaches for it.
///
/// Ported from `RecoveryCode.kt`. The alphabet and grouping match so the same
/// code reads the same way whichever platform issued it, and so the Swift and
/// Kotlin tests can pin the same folding vectors.
public enum RecoveryCode {

    /// Crockford-style base32: no I, L, O, or U. The first three are the classic
    /// 1/l/0 confusions and U is dropped so a random code can't spell something
    /// unfortunate. The user is copying this off a screen by hand, so ambiguity
    /// is a real failure mode rather than a theoretical one.
    private static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    /// 20 symbols over a 32-symbol alphabet — 100 bits.
    public static let length = 20

    /// Dashes every this many characters, for readability only.
    private static let group = 5

    /// A fresh code, formatted for display: `XXXXX-XXXXX-XXXXX-XXXXX`.
    public static func generate() -> String {
        var raw = ""
        raw.reserveCapacity(length)
        for _ in 0..<length {
            // Drawn from the system CSPRNG. `randomElement` uses
            // `SystemRandomNumberGenerator`, which is unbiased for any count.
            raw.append(alphabet.randomElement()!)
        }
        return format(raw)
    }

    public static func format(_ raw: String) -> String {
        stride(from: 0, to: raw.count, by: group).map { start in
            let from = raw.index(raw.startIndex, offsetBy: start)
            let to = raw.index(from, offsetBy: min(group, raw.count - start))
            return String(raw[from..<to])
        }
        .joined(separator: "-")
    }

    /// Strips formatting and folds the characters Crockford excludes onto what
    /// the user meant, so a code read off paper verifies whether or not they
    /// typed the dashes, used lower case, or wrote `O` for zero.
    public static func normalize(_ input: String) -> String {
        var result = ""
        for character in input.uppercased() {
            switch character {
            case "I", "L": result.append("1")
            case "O": result.append("0")
            default:
                // Dashes, spaces, and anything else are formatting noise. `U`
                // lands here too: it's excluded from the alphabet, so it can
                // only be a misreading, and dropping it makes the code come out
                // the wrong length rather than silently wrong.
                if alphabet.contains(character) { result.append(character) }
            }
        }
        return result
    }

    /// True if `input` could be a complete code, before spending PBKDF2 on it.
    public static func isWellFormed(_ input: String) -> Bool {
        normalize(input).count == length
    }
}
