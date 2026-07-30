package com.safeworld.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongKeyDomainSetTest {

    @Test
    fun `an exact domain matches`() {
        val set = LongKeyDomainSet.of(listOf("bet365.com", "example.org"))
        assertTrue(set.matches("bet365.com"))
        assertTrue(set.matches("example.org"))
    }

    @Test
    fun `subdomains match, mirroring PlainDomainSet`() {
        val set = LongKeyDomainSet.of(listOf("bet365.com"))
        val plain = PlainDomainSet(setOf("bet365.com"))

        for (host in listOf("bet365.com", "www.bet365.com", "a.b.bet365.com", "sports.bet365.com")) {
            assertEquals("disagreed on $host", plain.matches(host), set.matches(host))
            assertTrue(set.matches(host))
        }
    }

    @Test
    fun `unrelated and lookalike hosts do not match`() {
        val set = LongKeyDomainSet.of(listOf("bet365.com"))
        for (host in listOf("wikipedia.org", "notbet365.com", "bet365.com.evil.net", "bet365.org")) {
            assertFalse("should not match $host", set.matches(host))
        }
    }

    @Test
    fun `an empty set matches nothing`() {
        assertFalse(LongKeyDomainSet.EMPTY.matches("bet365.com"))
        assertEquals(0, LongKeyDomainSet.EMPTY.size)
        assertFalse(LongKeyDomainSet.of(emptyList()).matches("anything.com"))
    }

    @Test
    fun `duplicates and unhashable input are dropped from the size`() {
        val set = LongKeyDomainSet.of(listOf("bet365.com", "bet365.com", "", "   "))
        assertEquals(1, set.size)
    }

    /**
     * `binarySearch` on an unsorted array misses without erroring, which would
     * read as "this subscription blocks nothing" with nothing logged anywhere. So
     * [LongKeyDomainSet.ofKeys] sorts defensively rather than trusting its caller.
     */
    @Test
    fun `keys restored in the wrong order still match`() {
        val domains = (1..200).map { "site$it.example" }
        val scrambled = LongKeyDomainSet.of(domains).toKeyArray().reversedArray()

        val restored = LongKeyDomainSet.ofKeys(scrambled)
        for (domain in domains) {
            assertTrue("$domain lost after an unsorted restore", restored.matches(domain))
        }
    }

    @Test
    fun `a round trip through keys preserves every match`() {
        val domains = listOf("bet365.com", "pornhub.com", "deep.sub.example.org")
        val original = LongKeyDomainSet.of(domains)
        val restored = LongKeyDomainSet.ofKeys(original.toKeyArray())

        assertEquals(original.size, restored.size)
        for (domain in domains) assertTrue(restored.matches(domain))
        assertTrue(restored.matches("a.b.bet365.com"))
        assertFalse(restored.matches("wikipedia.org"))
    }

    /**
     * The bare-TLD entries the subscription feeds publish have to work here too,
     * since this is the set that actually holds them. See [BlockableTlds].
     */
    @Test
    fun `a whole-TLD entry blocks every host under it`() {
        val set = LongKeyDomainSet.of(listOf("xxx", "porn"))
        assertTrue(set.matches("anything.xxx"))
        assertTrue(set.matches("deep.sub.porn"))
        assertTrue(set.matches("xxx"))
        assertFalse(set.matches("example.com"))
    }

    /**
     * Agrees with the plaintext set over a large corpus, which is the property
     * that lets it stand in for one. Any disagreement is a false positive from a
     * 64-bit collision; at this size there should be none at all.
     */
    @Test
    fun `agrees with PlainDomainSet across many domains`() {
        val blocked = (1..20_000).map { "blocked$it.example" }
        val allowed = (1..20_000).map { "allowed$it.example" }

        val set = LongKeyDomainSet.of(blocked)
        val plain = PlainDomainSet(blocked.toSet())

        for (host in blocked + allowed) {
            assertEquals("disagreed on $host", plain.matches(host), set.matches(host))
        }
    }

    /**
     * The reason this class exists is size, so the size is what gets asserted:
     * eight bytes per entry, not the ~120 the string form costs.
     */
    @Test
    fun `storage is eight bytes per entry`() {
        val set = LongKeyDomainSet.of((1..50_000).map { "site$it.example" })
        assertEquals(50_000, set.size)
        assertEquals(50_000, set.toKeyArray().size)
    }

    @Test
    fun `unions with a bundled set the way subscriptions are wired`() {
        val bundled = PlainDomainSet(setOf("bet365.com"))
        val subscribed = LongKeyDomainSet.of(listOf("newcasino.example"))
        val union = UnionDomainSet(bundled, subscribed)

        assertTrue(union.matches("bet365.com"))
        assertTrue(union.matches("promo.newcasino.example"))
        assertFalse(union.matches("wikipedia.org"))
        assertEquals(2, union.size)
    }
}
