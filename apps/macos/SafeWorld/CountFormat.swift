import Foundation

/// Rounded counts for the blocked-count headline: "4.5 million".
///
/// A reduced port of `apps/ios/SafeWorld/CountFormat.swift`. The iOS version pulls its scale words
/// ("million", "লাখ") from the string catalog, because it ships in four languages and Bengali
/// counts in the Indian tradition — হাজার/লাখ/কোটি — where the *tiers* differ, not just the words.
///
/// **This app is English-only**, so the tier list is fixed and the words are literals. If macOS
/// ever gets the four languages the phones have, this file should be replaced by the iOS one
/// rather than grown — the tier-by-language logic is the part worth sharing, and duplicating it
/// half-done here is how the two would drift.
///
/// Foundation's `.notation(.compactName)` is not used, for the reason spelled out in the iOS file:
/// it is CLDR's *short* form, which truncates the scale word.
enum CountFormat {

    private struct Tier {
        let divisor: Double
        let word: String
    }

    /// Largest first; the first tier the value reaches wins.
    private static let tiers = [
        Tier(divisor: 1e9, word: "billion"),
        Tier(divisor: 1e6, word: "million"),
        Tier(divisor: 1e3, word: "thousand"),
    ]

    static func compact(_ count: Int) -> String {
        let value = Double(count)

        guard let tier = tiers.first(where: { value >= $0.divisor }) else {
            // Below every tier there is no scale word to add, and the plain number is already the
            // shortest true form of it.
            return exact(count)
        }

        let scaled = value / tier.divisor
        // One decimal only when it says something: "4 million", not "4.0 million".
        let number = scaled.formatted(
            .number.precision(.fractionLength(scaled < 10 ? 0...1 : 0...0))
        )
        return "\(number) \(tier.word)"
    }

    /// The exact number, grouped: "4,536,263".
    static func exact(_ count: Int) -> String {
        count.formatted(.number.grouping(.automatic))
    }
}
