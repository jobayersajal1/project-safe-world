package com.safeworld.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The advisory model's feature map and scoring — the Kotlin half of a spec that
 * exists four times.
 *
 * `packages/core/src/advisory.ts` is the definition; this, `DomainModel.swift`
 * and `DomainModel.cs` reimplement it by hand, and all four pin the same vectors
 * in their tests the way [Scramble] and [DomainHasher] already do. Four
 * platforms disagreeing about the same domain is the failure that discipline
 * exists to prevent, so **nothing here may change without changing all of them.**
 *
 * What it is for: the blocklists are exact-membership sets, so a gambling site
 * registered this morning is not in them and stays reachable until someone
 * upstream adds it, we re-fetch, and a delta arrives. This scores the hostname
 * itself and guesses. It never blocks — [Matcher.decide] remains the whole
 * decision — and the strongest thing it can say is "worth warning about".
 *
 * **The VPN service does not use it yet, and that is a product decision rather
 * than missing work.** A DNS answer is yes or no: there is nowhere in it to put
 * "probably, but have a look", which is the only thing this model has earned the
 * right to say. Chrome has an interstitial and ships the feature. Here the
 * options are a hard block at some higher confidence, or a notification carrying
 * an "allow this site" action, and neither should be picked by whoever happens
 * to be writing the port. The arithmetic is ported and tested so that when the
 * decision is made it is already known to agree with the other platforms.
 */
object DomainModel {

    const val HASH_BITS = 18
    const val TABLE_SIZE = 1 shl HASH_BITS
    private const val TABLE_MASK = TABLE_SIZE - 1
    private val NGRAMS = intArrayOf(3, 4, 5)

    private const val FNV_OFFSET = -0x7EE3623B // 0x811C9DC5 as a signed Int
    private const val FNV_PRIME = 0x01000193

    /**
     * Matches `normalizeHost` in [Matcher] plus stripping a leading `www.`,
     * which appears on blocked and allowed names alike and would otherwise be
     * learned as a signal.
     */
    fun normalize(host: String): String {
        var s = host.trim().lowercase()
        while (s.endsWith(".")) s = s.dropLast(1)
        if (s.startsWith("www.")) s = s.substring(4)
        return s
    }

    private fun fnv1a(bytes: ByteArray, start: Int, end: Int): Int {
        var h = FNV_OFFSET
        for (i in start until end) {
            h = (h xor (bytes[i].toInt() and 0xFF)) * FNV_PRIME
        }
        return h
    }

    private fun bucket(value: Int, edges: IntArray): Int {
        for (i in edges.indices) if (value <= edges[i]) return i
        return edges.size
    }

    /**
     * Whole-name properties no n-gram window can express.
     *
     * A 5-gram sees `.xyz` only together with whatever precedes it, so a cheap
     * TLD costs thousands of separate weights to learn. Same for "forty
     * characters of digits and hyphens over two labels". Hashed into the same
     * table as the n-grams, so they cost nothing in format or porting effort.
     */
    internal fun structuralTokens(host: String): List<String> {
        val labels = host.split(".")
        var digits = 0
        var hyphens = 0
        for (c in host) {
            if (c in '0'..'9') digits++ else if (c == '-') hyphens++
        }
        val longest = labels.maxOf { it.length }
        val length = host.toByteArray(Charsets.UTF_8).size

        // The \u0001 prefix namespaces these away from the n-grams. It was a
        // literal control byte in the TypeScript source once, which worked and
        // was invisible to anyone reading or reviewing it; every port now writes
        // the escape.
        return listOf(
            "\u0001tld=" + if (labels.size > 1) labels[labels.size - 1] else "",
            "\u0001sld=" + if (labels.size > 1) labels[labels.size - 2] else "",
            "\u0001labels=" + min(labels.size, 6),
            "\u0001len=" + bucket(length, intArrayOf(8, 12, 16, 20, 26, 34, 48)),
            "\u0001digits=" + bucket(100 * digits / max(1, length), intArrayOf(0, 5, 15, 30, 50)),
            "\u0001hyphens=" + bucket(hyphens, intArrayOf(0, 1, 2, 4)),
            "\u0001longest=" + bucket(longest, intArrayOf(4, 7, 10, 14, 20, 30)),
        )
    }

    /**
     * The L2-normalised sparse feature vector, as index -> value.
     *
     * Normalisation is not cosmetic: hostnames differ in length by an order of
     * magnitude, and an unnormalised count vector scores a long name highly for
     * being long — precisely the false positive this must not make.
     *
     * The sign comes from bit 31 of the same hash, so two n-grams colliding in
     * the table cancel as often as they reinforce and a collision costs noise
     * rather than a lean toward "blocked".
     */
    fun features(host: String): Map<Int, Double> {
        val clean = normalize(host)
        if (clean.isEmpty()) return emptyMap()

        val acc = HashMap<Int, Double>()
        fun add(h: Int) {
            val idx = h and TABLE_MASK
            // Bit 31 of the hash. In Kotlin an Int is signed, so "bit 31 set"
            // and "negative" are the same test.
            val sign = if (h < 0) -1.0 else 1.0
            acc[idx] = (acc[idx] ?: 0.0) + sign
        }

        val bytes = ("^$clean$").toByteArray(Charsets.UTF_8)
        for (n in NGRAMS) {
            var i = 0
            while (i + n <= bytes.size) {
                add(fnv1a(bytes, i, i + n))
                i++
            }
        }
        for (token in structuralTokens(clean)) {
            val t = token.toByteArray(Charsets.UTF_8)
            add(fnv1a(t, 0, t.size))
        }

        var sum = 0.0
        for (v in acc.values) sum += v * v
        if (sum == 0.0) return emptyMap()
        val inv = 1.0 / sqrt(sum)
        for (k in acc.keys.toList()) acc[k] = acc.getValue(k) * inv
        return acc
    }

    data class Weights(
        val category: CategoryId,
        val scale: Double,
        val bias: Double,
        val threshold: Double,
        val values: ByteArray,
    ) {
        // ByteArray gives identity equals/hashCode, which a data class would
        // otherwise expose as a broken value comparison.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun score(weights: Weights, host: String): Double {
        var sum = 0.0
        for ((idx, value) in features(host)) {
            if (idx < weights.values.size) sum += weights.values[idx].toDouble() * value
        }
        return sum * weights.scale + weights.bias
    }

    /**
     * Hosts on a shared publishing platform are never scored.
     *
     * Not a nicety — the failure that showed up in measurement. Adult Blogger
     * blogs are heavily represented in the adult list, so the model learned that
     * `*.blogspot.com` is adult and then flagged Blogger's own image CDN,
     * `1.bp.blogspot.com` and `2.` and `3.`, which serve the pictures on every
     * Blogger blog there is. A subdomain on shared hosting says something about
     * that one blog and nothing about the platform, and the string cannot tell
     * them apart, so we decline to guess.
     */
    private val SHARED_PLATFORMS = listOf(
        "blogspot.com", "bp.blogspot.com", "wordpress.com", "tumblr.com", "medium.com",
        "github.io", "gitlab.io", "gitbook.io", "weebly.com", "weeblysite.com",
        "wixsite.com", "webflow.io", "pages.dev", "vercel.app", "netlify.app",
        "herokuapp.com", "duckdns.org", "000webhostapp.com", "blogger.com",
        "substack.com", "notion.site", "myshopify.com", "translate.goog",
        "googleusercontent.com", "cloudfront.net", "akamaized.net", "amazonaws.com",
    )

    fun isSharedPlatformHost(host: String): Boolean {
        val clean = normalize(host)
        return SHARED_PLATFORMS.any { clean == it || clean.endsWith(".$it") }
    }

    /** The second stage. Call only for a host [Matcher.decide] returned allowed. */
    fun advise(host: String, models: List<Weights>): CategoryId? {
        val clean = normalize(host)
        if (clean.isEmpty() || isSharedPlatformHost(clean)) return null
        for (model in models) {
            if (score(model, clean) >= model.threshold) return model.category
        }
        return null
    }
}
