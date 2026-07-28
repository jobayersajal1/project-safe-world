package com.safeworld.core

import java.security.MessageDigest

/**
 * One-way hashing of blocklist domains, so the list Android ships and fetches
 * isn't a readable directory of the sites it blocks.
 *
 * Unlike scrambling, this is not reversible: the app never turns a hash back
 * into a domain. Matching works the other way round — hash the host the user is
 * visiting, and its parent domains, then look those digests up.
 *
 * Honest about what it does and doesn't buy: someone with a large corpus of
 * domain names can hash them all and recover which ones are on the list. The
 * [SALT] makes that a targeted job rather than a lookup in a precomputed table,
 * but it ships in the app and is not a secret. This defeats casual reading of
 * the published file, not a determined analyst.
 *
 * Must stay byte-identical to `scripts/build-android-blocklists.ts`, which
 * generates the hashes — see `DomainHasherTest` for the shared vector.
 */
object DomainHasher {
    /**
     * Prefixed before the domain so digests can't be looked up in a generic
     * precomputed SHA-256 table. Changing it invalidates every published hash,
     * hence the version marker.
     */
    const val SALT = "safe-world:v1:"

    /**
     * 128 bits of the digest, hex encoded. Full SHA-256 would double the list
     * size for no practical gain: at 128 bits a collision across a list of this
     * size is not a real concern, and a false positive would only mean one
     * wrongly blocked domain, not a security failure.
     */
    const val DIGEST_BYTES = 16

    private val HEX = "0123456789abcdef".toCharArray()

    /** Digest of a single domain. Empty string for input with no host. */
    fun hash(domain: String): String {
        val normalized = Matcher.normalizeHost(domain)
        if (normalized.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((SALT + normalized).toByteArray(Charsets.UTF_8))

        val out = CharArray(DIGEST_BYTES * 2)
        for (i in 0 until DIGEST_BYTES) {
            val b = digest[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    /**
     * The first 8 bytes of the same digest as a big-endian `Long` — the key a
     * [FuseFilter] stores.
     *
     * Deliberately derived from the identical digest [hash] produces, so the
     * filter and the hashed remote deltas describe the same domains and the
     * generator can build one from the other. 64 bits is far more than the
     * filter's own ~16-bit fingerprint resolution, so this truncation adds no
     * meaningful collision risk on top of what the filter already has.
     */
    fun key(domain: String): Long {
        val normalized = Matcher.normalizeHost(domain)
        if (normalized.isEmpty()) return 0L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((SALT + normalized).toByteArray(Charsets.UTF_8))

        var key = 0L
        for (i in 0 until 8) key = (key shl 8) or (digest[i].toLong() and 0xFF)
        return key
    }

    /**
     * The host plus every parent domain at a label boundary, e.g.
     * `a.b.example.com` -> `[a.b.example.com, b.example.com, example.com, com]`.
     *
     * Hashing destroys the suffix relationship that `hostMatchesDomain` relies
     * on, so subdomain matching is recovered by enumerating the candidates and
     * hashing each. Bounded by the label count, so a handful of digests per
     * lookup.
     */
    fun candidates(host: String): List<String> {
        val normalized = Matcher.normalizeHost(host)
        if (normalized.isEmpty()) return emptyList()

        val out = mutableListOf(normalized)
        var from = 0
        while (true) {
            val dot = normalized.indexOf('.', from)
            if (dot == -1) break
            val parent = normalized.substring(dot + 1)
            if (parent.isEmpty()) break
            // Stop before bare single-label names like "com".
            //
            // A blocklist can never contain one — `fetch-upstream-lists.ts`
            // drops any entry without a dot, precisely because "com" would
            // block a colossal amount of the web. Looking one up was harmless
            // against an exact hash set, since it could never be present. It is
            // not harmless against a probabilistic filter: a 1-in-65,536
            // collision on "com" would block *every* .com domain. Skipping it
            // removes that failure mode entirely and costs one lookup less.
            if (!parent.contains('.')) break
            out.add(parent)
            from = dot + 1
        }
        return out
    }
}
