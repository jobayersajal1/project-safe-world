package com.safeworld.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pins the filter format against the TypeScript generator.
 *
 * This is the test that matters most in the module. A drift between
 * `scripts/binary-fuse.ts` and [FuseFilter] does not crash — it produces a
 * filter that matches nothing, so the app installs, runs, shows a healthy
 * blocked-domain count, and silently blocks no sites at all. The vector below
 * was produced by the generator and must decode identically here forever.
 */
class FuseFilterTest {

    /** Built by `scripts/binary-fuse.ts` over exactly [IN_SET]. */
    private val vector =
        "U1dGMQIDAAByayudQ4udTQAAAAUAAAABAAAAGAAAAAAAAAAA3DngrQAAAADitA3cAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAQi1kAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXZa79wAAAACjFf1+AAAAAA=="

    private val filter = FuseFilter.parse(Base64.getDecoder().decode(vector))

    private companion object {
        val IN_SET = listOf(
            "bet365.com",
            "pornhub.com",
            "facebook.com",
            "example.org",
            "a.b.c.example.net",
        )
    }

    @Test
    fun `the generator's filter parses here`() {
        assertEquals(IN_SET.size, filter.size)
    }

    @Test
    fun `every domain the generator put in is found`() {
        // False negatives are the failure this structure must never have: a
        // blocked site slipping through. Unlike false positives, they are not a
        // probability — they are impossible unless the two sides disagree.
        for (domain in IN_SET) {
            assertTrue("$domain must be found", filter.contains(DomainHasher.key(domain)))
        }
    }

    @Test
    fun `domains that were never added are not found`() {
        // Not a guarantee for any single domain — ~1 in 65,536 will collide —
        // but these specific ones were checked against the generator.
        for (domain in listOf("wikipedia.org", "github.com", "google.com")) {
            assertFalse("$domain must not match", filter.contains(DomainHasher.key(domain)))
        }
    }

    @Test
    fun `key derivation agrees with the generator`() {
        // Pinned from the generator's output. `key` is what goes into the
        // filter, so if this derivation drifts, every shipped filter stops
        // matching without any error being raised.
        assertEquals(
            java.lang.Long.parseUnsignedLong("d658ebf1a97d66b1", 16),
            DomainHasher.key("bet365.com"),
        )
        assertEquals(
            java.lang.Long.parseUnsignedLong("0291a62d8d8766ec", 16),
            DomainHasher.key("pornhub.com"),
        )
        // Same digest the hashed lists use, just truncated — the two must agree
        // so a filter and a hashed delta describe the same domains.
        assertTrue(DomainHasher.hash("bet365.com").startsWith("d658ebf1a97d66b1"))
    }

    @Test
    fun `a corrupt or foreign filter is rejected rather than matching nothing`() {
        val bytes = Base64.getDecoder().decode(vector)

        val wrongMagic = bytes.copyOf().also { it[0] = 0 }
        assertThrows { FuseFilter.parse(wrongMagic) }

        val truncated = bytes.copyOf(bytes.size - 2)
        assertThrows { FuseFilter.parse(truncated) }

        assertThrows { FuseFilter.parse(ByteArray(4)) }
    }

    @Test
    fun `FuseDomainSet matches subdomains like the plaintext set does`() {
        val set = FuseDomainSet(filter)
        assertTrue(set.matches("bet365.com"))
        assertTrue(set.matches("www.bet365.com"))
        assertTrue(set.matches("sports.live.bet365.com"))
        assertFalse(set.matches("wikipedia.org"))
        assertEquals(IN_SET.size, set.size)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected an exception, but none was thrown")
        } catch (e: AssertionError) {
            throw e
        } catch (_: Exception) {
            // expected
        }
    }
}
