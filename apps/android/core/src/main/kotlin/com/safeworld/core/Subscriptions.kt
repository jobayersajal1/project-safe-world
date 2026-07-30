package com.safeworld.core

/**
 * Blocklists the user can subscribe to, which Safe World is not allowed to ship.
 *
 * Mirror of `packages/core/src/subscriptions.ts` — see that file for the full
 * reasoning. In short: `scripts/sources.ts` marks fourteen sources
 * `redistributable: false`, because shipping domains inside an app is
 * redistribution and encoding is not a licence workaround. But if the *user's
 * device* fetches the file from the publisher, we never distribute it, so there
 * is nothing to relicense. GPL-3.0 copyleft attaches to distribution; what we
 * distribute is a URL and a licence notice. AdGuard, uBlock, Pi-hole and AdAway
 * all handle these same feeds this way.
 *
 * **Two rules keep it legal, and both are load-bearing:**
 *
 * 1. The device fetches [Subscription.url] directly. The moment the data passes
 *    through anything of ours it becomes redistribution.
 * 2. Subscribed domains never leave the device — not into a published delta, not
 *    into a crash report, not into a backup we control.
 *
 * **This file contains no domains and must never contain any.**
 *
 * Only the subset that can actually work is listed; the other eleven excluded
 * sources need registration, need a commercial licence, publish IP addresses a
 * DNS sinkhole cannot act on, use URL patterns a DNS layer cannot express, are an
 * index rather than a list, or are query-time APIs that would send browsing off
 * the device — which contradicts the premise of the product, licence or no
 * licence.
 */
object Subscriptions {

    /**
     * @param id matches the `id` in `scripts/sources.ts` and in
     *   `subscriptions.ts`, so all three can be reconciled.
     * @param approxBytes download size, measured 2026-07-30, for the UI to warn
     *   about before spending someone's mobile data.
     * @param approxDomains domain count after parsing — measured, not taken from
     *   the feed's own header.
     */
    data class Subscription(
        val id: String,
        val category: CategoryId,
        val url: String,
        val format: FeedParser.Format,
        val publisher: String,
        val license: String,
        val homepage: String,
        val approxBytes: Long,
        val approxDomains: Int,
    )

    val ALL: List<Subscription> = listOf(
        Subscription(
            id = "hagezi-gambling",
            category = CategoryId.GAMBLING,
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/gambling.txt",
            format = FeedParser.Format.ADBLOCK,
            publisher = "HaGeZi",
            license = "GPL-3.0",
            homepage = "https://github.com/hagezi/dns-blocklists",
            approxBytes = 7_200_000,
            approxDomains = 367_000,
        ),
        Subscription(
            id = "hagezi-nsfw",
            category = CategoryId.ADULT,
            url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/nsfw.txt",
            format = FeedParser.Format.ADBLOCK,
            publisher = "HaGeZi",
            license = "GPL-3.0",
            homepage = "https://github.com/hagezi/dns-blocklists",
            approxBytes = 2_100_000,
            approxDomains = 108_000,
        ),
        Subscription(
            id = "oisd-nsfw",
            category = CategoryId.ADULT,
            // Wildcards (`*.example.com`), not plain domains. `sources.ts` had
            // this recorded as "domains" until 2026-07-30, which would have
            // stored the literal "*.example.com" and matched nothing.
            url = "https://nsfw.oisd.nl/domainswild",
            format = FeedParser.Format.DOMAINSWILD,
            publisher = "oisd",
            license = "Free for personal use; redistribution not permitted",
            homepage = "https://oisd.nl/",
            approxBytes = 9_000_000,
            approxDomains = 452_000,
        ),
    )

    fun byId(id: String): Subscription? = ALL.firstOrNull { it.id == id }

    fun forCategory(category: CategoryId): List<Subscription> =
        ALL.filter { it.category == category }
}
