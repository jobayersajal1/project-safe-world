package com.safeworld.core

/**
 * A category's domains, however they happen to be stored.
 *
 * This exists so `Matcher.decide` keeps being the single tested spec of
 * blocking behaviour regardless of representation: the precedence rules are
 * identical whether the list is plaintext (as on the other platforms) or
 * hashed (as Android publishes it). Only the "is this host in the list?"
 * question changes.
 */
interface DomainSet {
    /** True if [host] equals, or is a subdomain of, some domain in this set. */
    fun matches(host: String): Boolean

    /** Entry count, for diagnostics — not meaningful for matching. */
    val size: Int
}

/** Plaintext domains, matched by suffix — the shared cross-platform behaviour. */
class PlainDomainSet(private val domains: Set<String>) : DomainSet {
    override fun matches(host: String): Boolean = Matcher.hostInList(host, domains)
    override val size: Int get() = domains.size
}

/**
 * Hashed domains. Reproduces [PlainDomainSet]'s semantics exactly by hashing
 * the host and each of its parent domains and looking each digest up — see
 * [DomainHasher.candidates].
 */
class HashedDomainSet(private val hashes: Set<String>) : DomainSet {
    override fun matches(host: String): Boolean {
        if (hashes.isEmpty()) return false
        return DomainHasher.candidates(host).any { DomainHasher.hash(it) in hashes }
    }

    override val size: Int get() = hashes.size
}

/** An empty set, for a category with no list loaded. */
object EmptyDomainSet : DomainSet {
    override fun matches(host: String): Boolean = false
    override val size: Int get() = 0
}

/**
 * Two sets treated as one: the bundled filter plus whatever a remote update has
 * added since.
 *
 * They cannot simply be merged. A fuse filter is immutable once built —
 * construction peels every key exactly once — so fetched domains live beside it
 * in a plain [HashedDomainSet] rather than being inserted. Deltas are small by
 * design (only what was added since the release baseline), so the second lookup
 * costs almost nothing.
 */
class UnionDomainSet(private val a: DomainSet, private val b: DomainSet) : DomainSet {
    override fun matches(host: String): Boolean = a.matches(host) || b.matches(host)

    /**
     * A sum, not a true union: the two may overlap, so this can over-count
     * slightly. It drives the home screen's headline, never a blocking
     * decision.
     */
    override val size: Int get() = a.size + b.size
}
