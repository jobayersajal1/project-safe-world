import Foundation

/// Whether a settings change makes the user less protected.
///
/// **Strengthening is free, weakening costs the PIN.** That one rule decides
/// every PIN gate in the app — Android states it at the top of `HomeScreen.kt`
/// and then re-derives it per switch, which works there because each switch
/// knows its own direction. This says it once instead, so a new setting cannot
/// quietly arrive without a gate, and so the rule itself can be tested without
/// a simulator.
///
/// Each of the four ways to weaken protection corresponds to a branch of
/// `Matcher.decide`, in its precedence order.
public enum ProtectionChange {

    public static func weakens(from previous: Settings, to next: Settings) -> Bool {
        // Master switch. `decide` returns "allow" for everything when this is
        // off, so it outranks every other consideration.
        if previous.enabled && !next.enabled { return true }

        // A category going off stops blocking everything it listed.
        for category in Categories.all where previous.categories[category.id] == true {
            if next.categories[category.id] != true { return true }
        }

        // Losing a custom-block entry unblocks that domain. Compared as sets, so
        // swapping one domain for another still counts — the count alone would
        // call that unchanged.
        if !Set(previous.customBlock).isSubset(of: Set(next.customBlock)) { return true }

        // Custom-allow is the inverted case: *gaining* an entry is what weakens,
        // because allow wins over every category. This is the one a
        // "did anything get removed?" check would miss entirely.
        if !Set(next.customAllow).isSubset(of: Set(previous.customAllow)) { return true }

        return false
    }
}
