package com.safeworld.core

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-port contract for the advisory feature map.
 *
 * These numbers were produced by `scripts/domain_features.py`, which is the
 * definition; `packages/core/test/advisory.test.ts`, `DomainModelTests.swift`
 * and `DomainModelTests.cs` pin the same table. A port that hashes differently,
 * normalises differently, or forgets the L2 step fails here rather than shipping
 * a platform that quietly disagrees about the same domain.
 *
 * The feature vector is pinned rather than the score, deliberately: the hashing,
 * the normalisation, the structural tokens and the L2 step are the fragile
 * parts and this catches all of them, while the score would also drag 341 KB of
 * weights into a module that has nothing to score for yet.
 *
 * Every made-up name here is absent from all seven blocklists and the real ones
 * are famous sites or platform hosts, so this table publishes nothing.
 */
class DomainModelTest {

    /**
     * host, non-zero count, sum of indexes, count of negative values.
     *
     * The negative count is what pins the sign bit. An earlier version of this
     * table used the sum of absolute values instead, which is worth nothing:
     * with no collisions every value is ±1/‖v‖, so that sum is just √nnz and
     * agrees no matter how the hash behaves.
     */
    private val pinned = listOf(
        Quad("best-casino-slots-bonus.com", 85, 12_369_141, 47),
        Quad("adult-xxx-tube-videos.com", 79, 10_123_884, 36),
        Quad("github.com", 34, 4_750_261, 20),
        Quad("wikipedia.org", 43, 5_594_899, 17),
        Quad("nhs.uk", 22, 2_508_037, 10),
        Quad("acme-plumbing-services.com", 82, 11_115_176, 37),
        Quad("xn--test-punycode-9za.net", 79, 9_862_878, 41),
        Quad("a.b.c.example.co.uk", 61, 7_332_332, 25),
        Quad("3.bp.blogspot.com", 55, 7_031_982, 21),
        Quad("www.Example.COM", 37, 4_971_527, 16),
        Quad("", 0, 0, 0),
    )

    private data class Quad(val host: String, val nnz: Int, val indexSum: Int, val negatives: Int)

    @Test
    fun `features match the other ports`() {
        for ((host, nnz, indexSum, negatives) in pinned) {
            val f = DomainModel.features(host)
            assertEquals("non-zero count for $host", nnz, f.size)
            assertEquals("index sum for $host", indexSum, f.keys.sum())
            assertEquals("negative count for $host", negatives, f.values.count { it < 0 })
        }
    }

    @Test
    fun `features are L2 normalised`() {
        for (host in listOf("bet365.com", "a.b.c.example.co.uk", "x.io")) {
            val sum = DomainModel.features(host).values.sumOf { it * it }
            assertTrue("‖v‖² was $sum for $host", abs(sum - 1.0) < 1e-10)
        }
    }

    @Test
    fun `indexes stay inside the table`() {
        for (idx in DomainModel.features("some-long-hyphenated-name-99.example.co.uk").keys) {
            assertTrue(idx in 0 until DomainModel.TABLE_SIZE)
        }
    }

    @Test
    fun `leading www and case are ignored`() {
        // Matches normalizeHost in Matcher, which the model must not diverge
        // from — the same host reaching two different verdicts by way of a
        // prefix would be indefensible.
        assertEquals(DomainModel.features("Example.COM"), DomainModel.features("www.example.com"))
        assertEquals("example.com", DomainModel.normalize("  WWW.Example.com.  "))
    }

    @Test
    fun `empty host has no features`() {
        assertTrue(DomainModel.features("").isEmpty())
        assertTrue(DomainModel.features("   ").isEmpty())
    }

    @Test
    fun `shared platforms are never scored`() {
        // The guard exists because the adult model learned *.blogspot.com and
        // then flagged the image host serving every Blogger blog there is.
        assertTrue(DomainModel.isSharedPlatformHost("1.bp.blogspot.com"))
        assertTrue(DomainModel.isSharedPlatformHost("3.bp.blogspot.com"))
        assertTrue(DomainModel.isSharedPlatformHost("videoseriesbiblicas.blogspot.com"))
        assertTrue(DomainModel.isSharedPlatformHost("someone.github.io"))
        assertFalse(DomainModel.isSharedPlatformHost("github.com"))
        assertFalse(DomainModel.isSharedPlatformHost("blogspot.com.evil.example"))
    }

    @Test
    fun `advise honours the guard and the threshold`() {
        // A weight table of zeroes scores every host at the bias, so the
        // threshold alone decides — enough to check the plumbing without
        // depending on a generated model being present.
        val always = DomainModel.Weights(
            CategoryId.GAMBLING, scale = 1.0, bias = 1.0, threshold = 0.0,
            blockThreshold = Double.POSITIVE_INFINITY, values = ByteArray(DomainModel.TABLE_SIZE),
        )
        assertEquals(CategoryId.GAMBLING, DomainModel.advise("anything.example", listOf(always))?.category)
        assertNull(DomainModel.advise("anyone.blogspot.com", listOf(always)))
        assertNull(DomainModel.advise("", listOf(always)))

        val never = DomainModel.Weights(
            CategoryId.GAMBLING, scale = 1.0, bias = 0.0, threshold = 1.0,
            blockThreshold = Double.POSITIVE_INFINITY, values = ByteArray(DomainModel.TABLE_SIZE),
        )
        assertNull(DomainModel.advise("anything.example", listOf(never)))
    }

    @Test
    fun `only warns until blocking is asked for`() {
        // The stricter tier must stay inert unless the caller opts in: taking a
        // site away on a guess is the thing this model was not built for.
        val model = DomainModel.Weights(
            CategoryId.GAMBLING, scale = 1.0, bias = 1.0, threshold = 0.0,
            blockThreshold = 0.5, values = ByteArray(DomainModel.TABLE_SIZE),
        )
        assertEquals(DomainModel.Action.WARN, DomainModel.advise("x.example", listOf(model))?.action)
        assertEquals(
            DomainModel.Action.BLOCK,
            DomainModel.advise("x.example", listOf(model), allowBlocking = true)?.action,
        )
    }

    @Test
    fun `warns rather than blocks between the two thresholds`() {
        val model = DomainModel.Weights(
            CategoryId.GAMBLING, scale = 1.0, bias = 0.0, threshold = -1.0,
            blockThreshold = 1.0, values = ByteArray(DomainModel.TABLE_SIZE),
        )
        assertEquals(
            DomainModel.Action.WARN,
            DomainModel.advise("x.example", listOf(model), allowBlocking = true)?.action,
        )
    }

    @Test
    fun `bundled models agree with the other ports where they are present`() {
        // Generated artifact: absent on a fresh clone, so this asserts only when
        // `npm run build:model` has run.
        val models = DomainModel.bundled
        if (models.isEmpty()) return

        assertEquals(2, models.size)
        for (m in models) {
            assertEquals(DomainModel.TABLE_SIZE, m.values.size)
            assertTrue("block tier must be stricter for ${m.category}", m.blockThreshold > m.threshold)
        }

        // The names pinned across all four ports, at the operating point that
        // actually ships.
        assertEquals(
            DomainModel.Action.BLOCK,
            DomainModel.advise("best-casino-slots-bonus.com", models, allowBlocking = true)?.action,
        )
        for (host in listOf("github.com", "wikipedia.org", "nhs.uk", "acme-plumbing-services.com")) {
            assertNull(host, DomainModel.advise(host, models, allowBlocking = true))
        }
    }
}
