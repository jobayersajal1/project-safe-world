package com.safeworld.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for the binary fuse32 filters produced by `scripts/binary-fuse.ts`.
 *
 * The blocklist only ever answers "is this host listed?", never "what is on the
 * list", so it can be stored as an approximate membership filter rather than as
 * the domains themselves. That is 4.5M domains in ~10 MB instead of ~82 MB of
 * salted digests — and the same ~10 MB resident, rather than hundreds of MB of
 * strings.
 *
 * **The error is one-directional.** A domain that went in is *always* found:
 * false negatives are impossible, so a blocked site can never slip through. The
 * cost is a false positive — a site blocked that isn't on the list — at roughly
 * 1 lookup in 4.3 billion, or 1 browsed domain in ~477 million once the several
 * lookups per host are counted. Measured against a real corpus, 16-bit
 * fingerprints gave 1 in ~5,000, which was too many.
 *
 * Must agree exactly with the TypeScript generator; `FuseFilterTest` pins shared
 * vectors because a drift here fails silently, matching nothing while the app
 * looks perfectly healthy.
 */
class FuseFilter private constructor(
    private val seed: Long,
    private val segmentLength: Int,
    private val segmentCountLength: Int,
    private val fingerprints: IntArray,
    /** How many domains went in — the filter itself cannot be counted. */
    val size: Int,
) {
    private val segmentLengthMask = segmentLength - 1

    fun contains(key: Long): Boolean {
        val hash = mix64(key + seed)
        var fp = fingerprintOf(hash)

        val hi = unsignedMultiplyHigh(hash, segmentCountLength.toLong()).toInt()
        val h0 = hi
        val h1 = h0 + segmentLength xor ((hash ushr 18).toInt() and segmentLengthMask)
        val h2 = h0 + 2 * segmentLength xor (hash.toInt() and segmentLengthMask)

        fp = fp xor fingerprints[h0] xor fingerprints[h1] xor fingerprints[h2]
        return fp == 0
    }

    companion object {
        /** "SWF1". A wrong or truncated file must fail loudly, not match nothing. */
        private const val MAGIC = 0x53574631
        private const val FORMAT_VERSION = 2
        private const val HEADER_BYTES = 28

        /** splitmix64 finalizer — the same mix the generator uses. */
        private fun mix64(value: Long): Long {
            var z = value + -0x61c8864680b583ebL // 0x9e3779b97f4a7c15
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xbf58476d1ce4e5b9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94d049bb133111eb
            return z xor (z ushr 31)
        }

        private fun fingerprintOf(hash: Long): Int =
            (hash xor (hash ushr 32)).toInt()

        /**
         * High 64 bits of an *unsigned* 64x64 product. `Math.multiplyHigh` is
         * signed; the correction term is only needed when [x] has its top bit
         * set, since [y] here is always a positive array length.
         */
        private fun unsignedMultiplyHigh(x: Long, y: Long): Long {
            val hi = Math.multiplyHigh(x, y)
            return hi + ((x shr 63) and y)
        }

        /**
         * Parse a serialized filter. Throws rather than degrading, for the same
         * reason [Blocklists] fails closed on an unknown algorithm.
         */
        fun parse(bytes: ByteArray): FuseFilter {
            require(bytes.size >= HEADER_BYTES) { "filter truncated: ${bytes.size} bytes" }
            val header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)

            val magic = header.int
            require(magic == MAGIC) { "not a Safe World filter (magic 0x${magic.toString(16)})" }
            val version = header.get().toInt()
            require(version == FORMAT_VERSION) { "unsupported filter version $version" }
            header.get() // segmentLengthLog, derivable from segmentCount/arrayLength
            header.short // reserved

            val seed = header.long
            val size = header.int
            val segmentCount = header.int
            val arrayLength = header.int

            val expected = HEADER_BYTES + arrayLength * 4
            require(bytes.size == expected) {
                "filter length ${bytes.size} does not match header ($expected)"
            }

            val fingerprints = IntArray(arrayLength)
            val body = ByteBuffer.wrap(bytes, HEADER_BYTES, arrayLength * 4)
                .order(ByteOrder.BIG_ENDIAN)
            body.asIntBuffer().get(fingerprints)

            val segmentLength = arrayLength / (segmentCount + 2)
            return FuseFilter(
                seed = seed,
                segmentLength = segmentLength,
                segmentCountLength = segmentCount * segmentLength,
                fingerprints = fingerprints,
                size = size,
            )
        }

        fun parse(stream: InputStream): FuseFilter = stream.use { parse(it.readBytes()) }
    }
}

/**
 * A category's domains as a fuse filter over their salted digests.
 *
 * Same contract as [HashedDomainSet] — [DomainHasher.candidates] still yields
 * the host and each parent domain, and each is one filter lookup — but stores
 * the set approximately, which is what makes an uncapped list shippable.
 */
class FuseDomainSet(private val filter: FuseFilter) : DomainSet {
    override fun matches(host: String): Boolean =
        DomainHasher.candidates(host).any { filter.contains(DomainHasher.key(it)) }

    override val size: Int get() = filter.size
}
