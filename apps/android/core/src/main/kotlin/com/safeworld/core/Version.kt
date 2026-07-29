package com.safeworld.core

/**
 * Ordering for the app's own version strings, used to decide whether a
 * published release is actually newer than what's installed.
 *
 * Mirrored in `SafeWorldCore/Version.swift` for iOS/macOS; both pin the same
 * vectors in their tests, because the two sides answering differently would
 * mean one platform nagging about an update that isn't one — or worse, staying
 * quiet about one that is.
 *
 * Deliberately tolerant rather than strict semver: the input is a git tag
 * (`v0.2.0`) on one side and a `versionName` on the other, and neither is
 * guaranteed to be well-formed. Anything unparseable reads as 0 rather than
 * throwing, so a malformed tag can't crash the update check — it just fails to
 * look newer.
 */
object Version {
    /**
     * Standard three-way compare: negative when [a] is older than [b], 0 when
     * they are the same release, positive when [a] is newer.
     */
    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)

        val size = maxOf(coreA.size, coreB.size)
        for (i in 0 until size) {
            // Missing trailing components are 0, so "1.2" == "1.2.0".
            val diff = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }

        // Same numeric core: a pre-release precedes the release it leads up to,
        // so 1.0.0-beta < 1.0.0. Without this an installed 1.0.0 would be
        // offered 1.0.0-beta as an "update".
        if (preA == null && preB == null) return 0
        if (preA == null) return 1
        if (preB == null) return -1
        // Two pre-releases of the same core compare lexically. A simplification
        // — real semver compares dot-separated identifiers, numeric ones
        // numerically — but this project has never shipped one, and the
        // fallback only ever mis-orders two pre-releases against each other.
        return preA.compareTo(preB)
    }

    /** True when [candidate] is a release worth offering to someone on [current]. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /**
     * Splits "v1.2.3-beta.1+build5" into ([1, 2, 3], "beta.1").
     * Build metadata is dropped: semver says it takes no part in precedence.
     */
    private fun split(raw: String): Pair<List<Int>, String?> {
        var s = raw.trim().removePrefix("v").removePrefix("V")
        s = s.substringBefore('+')
        val hyphen = s.indexOf('-')
        val pre = if (hyphen >= 0) s.substring(hyphen + 1).ifEmpty { null } else null
        if (hyphen >= 0) s = s.substring(0, hyphen)

        val core = s.split('.').map { part ->
            // takeWhile so "3rc" reads as 3 rather than collapsing to 0 and
            // silently losing an ordering the string plainly implies.
            part.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return core to pre
    }
}
