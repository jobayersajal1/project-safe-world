package com.safeworld.core

/**
 * A large set of hashed domains held as a sorted `LongArray`, matched by binary
 * search.
 *
 * **Why not [HashedDomainSet].** That stores `Set<String>` of hex digests, which
 * is right for what it was built for — remote deltas, a few thousand entries. A
 * subscription is three orders of magnitude bigger. At 929,000 entries the string
 * form costs roughly 110 MB on a phone: ~72 bytes for each `String` (40 bytes of
 * object header plus 32 Latin-1 chars) and another ~48 bytes per `HashSet` node.
 * The same keys as primitive longs are **8 bytes each — 7.4 MB**, which is the
 * difference between a feature that ships and one that gets the app killed.
 *
 * The keys are [DomainHasher.key] values: the first 8 bytes of the same salted
 * SHA-256 digest [DomainHasher.hash] produces, so a subscription, a remote delta,
 * and a bundled fuse filter all describe domains the same way.
 *
 * **64 bits is exact enough.** Across 929,000 keys the chance of any collision is
 * about 2 in 100,000,000 (n²/2^65). A collision would mean one wrongly blocked
 * domain — the same class of outcome as the fuse filter's own false-positive
 * rate, and the app already has the escape hatch for it in "always allow". It can
 * never cause the reverse, a blocked domain being let through.
 *
 * Subdomain matching comes from [DomainHasher.candidates], exactly as it does for
 * [HashedDomainSet]: hashing destroys the suffix relationship, so the host and
 * each of its parents are looked up in turn.
 */
class LongKeyDomainSet(
    /** **Must be sorted ascending.** Use [of] unless you already guarantee that. */
    private val keys: LongArray,
) : DomainSet {

    override fun matches(host: String): Boolean {
        if (keys.isEmpty()) return false
        for (candidate in DomainHasher.candidates(host)) {
            if (keys.binarySearch(DomainHasher.key(candidate)) >= 0) return true
        }
        return false
    }

    override val size: Int get() = keys.size

    /** The backing keys, for persistence. Copied so the set stays immutable. */
    fun toKeyArray(): LongArray = keys.copyOf()

    companion object {
        val EMPTY = LongKeyDomainSet(LongArray(0))

        /** Hashes, de-duplicates and sorts [domains]. */
        fun of(domains: Iterable<String>): LongKeyDomainSet {
            val keys = domains
                .map { DomainHasher.key(it) }
                .filter { it != 0L } // key() returns 0 for input with no host
                .distinct()
                .toLongArray()
            keys.sort()
            return LongKeyDomainSet(keys)
        }

        /**
         * Wraps keys already hashed and persisted. Sorted defensively rather than
         * trusted: an unsorted array makes `binarySearch` miss silently, which
         * would read as "this subscription blocks nothing" with no error anywhere.
         */
        fun ofKeys(keys: LongArray): LongKeyDomainSet {
            val copy = keys.copyOf()
            copy.sort()
            return LongKeyDomainSet(copy)
        }
    }
}
