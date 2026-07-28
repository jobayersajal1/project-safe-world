package com.safeworld.core

/**
 * Mirrors `packages/core/src/matcher.ts`. This is the tested spec of blocking
 * behavior — the VPN service's DNS sinkhole must stay consistent with it.
 */
object Matcher {
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://")

    /**
     * Normalize a hostname or URL into a bare, lowercased registrable-ish host:
     * strips scheme, path, port, userinfo, a leading "www.", and trailing dot.
     * Returns "" for input that has no host.
     */
    fun normalizeHost(input: String): String {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return ""

        // Strip scheme.
        s = s.replaceFirst(SCHEME, "")
        // Strip userinfo.
        val at = s.indexOf('@')
        if (at != -1) s = s.substring(at + 1)
        // Strip path/query/fragment.
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut != -1) s = s.substring(0, cut)
        // Strip port.
        val colon = s.indexOf(':')
        if (colon != -1) s = s.substring(0, colon)
        // Strip trailing dot and leading www.
        s = s.removeSuffix(".").removePrefix("www.")
        return s
    }

    /**
     * True if [host] equals [domain] or is a subdomain of it.
     * Both are expected to be normalized (see [normalizeHost]).
     */
    fun hostMatchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /** True if [host] matches any domain in the list (exact or subdomain). */
    fun hostInList(host: String, domains: Iterable<String>): Boolean =
        domains.any { it.isNotEmpty() && hostMatchesDomain(host, it) }

    data class BlockDecision(
        val blocked: Boolean,
        /** Which category caused the block, "custom" for the user block list, or null. */
        val reason: String?,
    )

    private val ALLOWED = BlockDecision(blocked = false, reason = null)

    /**
     * Decide whether a host should be blocked given the user's settings and the
     * per-category blocklists. Precedence: master off -> allow; custom allow ->
     * allow; custom block -> block; otherwise first enabled category that lists it.
     *
     * The custom allow/block lists are always matched as plaintext: they are the
     * user's own entries, stored locally, and never published — only the bundled
     * and fetched category lists are hashed.
     */
    @JvmName("decideWithDomainSets")
    fun decide(
        host: String,
        settings: Settings,
        blocklists: Map<CategoryId, DomainSet>,
    ): BlockDecision {
        val h = normalizeHost(host)
        if (h.isEmpty() || !settings.enabled) return ALLOWED

        if (hostInList(h, settings.customAllow.map(::normalizeHost))) return ALLOWED
        if (hostInList(h, settings.customBlock.map(::normalizeHost))) {
            return BlockDecision(blocked = true, reason = "custom")
        }

        for (category in Categories.all) {
            if (settings.categories[category.id] != true) continue
            if (blocklists[category.id]?.matches(h) == true) {
                return BlockDecision(blocked = true, reason = category.id.id)
            }
        }
        return ALLOWED
    }

    /**
     * Convenience overload for plaintext lists — the shape the other platforms
     * use, and what `packages/core/test/matcher.test.ts` exercises.
     */
    fun decide(
        host: String,
        settings: Settings,
        blocklists: Map<CategoryId, Set<String>>,
    ): BlockDecision {
        // Explicitly typed: `mapValues { PlainDomainSet(...) }` infers
        // Map<CategoryId, PlainDomainSet>, which leaves the call to the other
        // `decide` overload ambiguous.
        val sets: Map<CategoryId, DomainSet> = blocklists.mapValues { PlainDomainSet(it.value) }
        return decide(host, settings, sets)
    }
}
