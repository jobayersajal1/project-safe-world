import Foundation

/// Rounded counts for the home-screen headline: "4.4 million", "৪৪ লাখ".
///
/// Port of `ui/CountFormat.kt`, but **not** a port of how it does the job.
/// Android calls ICU's `CompactDecimalFormat` with `CompactStyle.LONG`, which
/// spells the scale word out. Foundation has no equivalent: `.notation(.compactName)`
/// is CLDR's *short* form and is the only compact notation on Apple platforms.
///
/// Short is not merely terser, it is wrong here. CLDR's Bengali abbreviation of
/// লাখ is "লা", and the headline string appends the classifier টি directly to the
/// number — so `.compactName` produced "44.3 লাটি", a truncated word rather than
/// "44.3 লাখ". The same defect as the "৮৫ হাটি" bug this file's Kotlin twin was
/// written to fix, arriving by a different route.
///
/// So the scale word comes from the string catalog instead. That also handles a
/// thing a single divisor cannot: Bengali counts in the Indian tradition, where
/// the tiers are হাজার (10³), লাখ (10⁵) and কোটি (10⁷) — 4.4 million is "44 লাখ",
/// not "4.4 something". The tier *list* differs by language, not just the words.
enum CountFormat {

    private struct Tier {
        let divisor: Double
        let key: String
    }

    /// Largest first; the first tier the value reaches wins.
    private static func tiers(for tag: String) -> [Tier] {
        switch tag {
        case "bn":
            return [
                Tier(divisor: 1e7, key: "count_scale_crore"),
                Tier(divisor: 1e5, key: "count_scale_lakh"),
                Tier(divisor: 1e3, key: "count_scale_thousand"),
            ]
        default:
            return [
                Tier(divisor: 1e9, key: "count_scale_billion"),
                Tier(divisor: 1e6, key: "count_scale_million"),
                Tier(divisor: 1e3, key: "count_scale_thousand"),
            ]
        }
    }

    static func compact(_ count: Int, locale: Locale = Language.effectiveLocale) -> String {
        let value = Double(count)

        guard let tier = tiers(for: locale.language.languageCode?.identifier ?? "en")
            .first(where: { value >= $0.divisor })
        else {
            // Below every tier there is no scale word to add, and the plain
            // number is already the shortest true form of it.
            return exact(count, locale: locale)
        }

        let scaled = value / tier.divisor
        // One decimal only when it says something: "44 লাখ", not "44.0 লাখ".
        let number = scaled.formatted(
            .number
                .precision(.fractionLength(scaled < 10 ? 0...1 : 0...0))
                .locale(locale)
        )
        return String(format: Language.string(tier.key), locale: locale, number)
    }

    /// The exact number, grouped for the locale — which for Bengali means the
    /// Indian grouping "44,30,965", not "4,430,965".
    static func exact(_ count: Int, locale: Locale = Language.effectiveLocale) -> String {
        count.formatted(.number.grouping(.automatic).locale(locale))
    }
}
