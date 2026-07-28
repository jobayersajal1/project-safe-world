package com.safeworld.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainHasherTest {
    /**
     * Pinned digests, computed by `scripts/build-android-blocklists.ts`.
     *
     * This is the contract between the TS generator and the Kotlin matcher: if
     * either side changes its salt, digest length, encoding, or normalization,
     * these fail. Without them a drift would be silent — the app would simply
     * stop matching anything and block nothing, which is the worst way for this
     * to break.
     */
    @Test
    fun `digests match the ones the generator produces`() {
        assertEquals("d658ebf1a97d66b19cc925ece45b6255", DomainHasher.hash("bet365.com"))
        assertEquals("0291a62d8d8766ec5bfe74d5c723f9af", DomainHasher.hash("pornhub.com"))
        assertEquals("f12640369f0f6d623fb2d6dd60727279", DomainHasher.hash("example.com"))
    }

    @Test
    fun `hashing normalizes first, so URL forms agree`() {
        val expected = DomainHasher.hash("bet365.com")
        assertEquals(expected, DomainHasher.hash("WWW.Bet365.com"))
        assertEquals(expected, DomainHasher.hash("https://www.bet365.com:443/path?x=1"))
    }

    @Test
    fun `a digest does not reveal the domain`() {
        val digest = DomainHasher.hash("bet365.com")
        assertFalse(digest.contains("bet365"))
        assertEquals(DomainHasher.DIGEST_BYTES * 2, digest.length)
    }

    @Test
    fun `empty input hashes to empty rather than to a digest of nothing`() {
        assertEquals("", DomainHasher.hash(""))
        assertEquals("", DomainHasher.hash("   "))
    }

    @Test
    fun `candidates walk up the label boundaries but stop before the bare TLD`() {
        assertEquals(
            listOf("a.b.example.com", "b.example.com", "example.com"),
            DomainHasher.candidates("a.b.example.com"),
        )
        assertEquals(listOf("example.com"), DomainHasher.candidates("example.com"))
        assertEquals(emptyList<String>(), DomainHasher.candidates(""))
    }

    @Test
    fun `a bare TLD is never a candidate`() {
        // No blocklist can contain "com" — fetch-upstream-lists drops any entry
        // without a dot — so looking one up can only ever produce a wrong
        // answer. Against the exact hash set that was merely pointless; against
        // the probabilistic filter a single collision on "com" would block
        // every .com domain at once, which is why this is asserted rather than
        // left to the implementation.
        for (host in listOf("example.com", "a.b.c.co.uk", "deep.sub.domain.org")) {
            assertTrue(
                "no candidate for $host may be a single label",
                DomainHasher.candidates(host).all { it.contains('.') },
            )
        }
    }

    @Test
    fun `candidates strip www so a subdomain match is not doubled up`() {
        assertEquals(listOf("bet365.com"), DomainHasher.candidates("www.bet365.com"))
    }

    // The point of the whole exercise: hashed matching must behave exactly like
    // the plaintext matching the other platforms do.
    @Test
    fun `hashed matching agrees with plaintext matching`() {
        val domains = setOf("bet365.com", "pornhub.com")
        val plain = PlainDomainSet(domains)
        val hashed = HashedDomainSet(domains.map(DomainHasher::hash).toSet())

        val hosts = listOf(
            "bet365.com",
            "www.bet365.com",
            "sports.bet365.com",
            "a.b.bet365.com",
            "notbet365.com",
            "bet365.com.evil.example",
            "wikipedia.org",
            "pornhub.com",
            "",
        )
        for (host in hosts) {
            assertEquals("disagreement on '$host'", plain.matches(host), hashed.matches(host))
        }
    }

    @Test
    fun `an empty hashed set matches nothing`() {
        assertFalse(HashedDomainSet(emptySet()).matches("bet365.com"))
    }

    @Test
    fun `subdomains of a listed domain are blocked through decide`() {
        val lists = mapOf<CategoryId, DomainSet>(
            CategoryId.GAMBLING to HashedDomainSet(setOf(DomainHasher.hash("bet365.com"))),
        )
        assertTrue(Matcher.decide("sports.bet365.com", Settings.defaults(), lists).blocked)
        assertFalse(Matcher.decide("wikipedia.org", Settings.defaults(), lists).blocked)
    }
}
