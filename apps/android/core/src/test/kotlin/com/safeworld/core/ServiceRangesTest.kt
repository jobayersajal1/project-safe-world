package com.safeworld.core

import com.safeworld.core.ServiceRanges.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRangesTest {

    private fun ip(dotted: String): Int {
        val parts = dotted.split('.').map { it.toInt() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    @Test
    fun `an address inside a prefix matches`() {
        val matcher = Matcher(listOf("157.240.0.0/16"))
        assertTrue(matcher.contains(ip("157.240.1.35")))
        assertTrue(matcher.contains(ip("157.240.255.255")))
        assertTrue(matcher.contains(ip("157.240.0.0")))
    }

    @Test
    fun `an address just outside a prefix does not`() {
        val matcher = Matcher(listOf("157.240.0.0/16"))
        assertFalse(matcher.contains(ip("157.239.255.255")))
        assertFalse(matcher.contains(ip("157.241.0.0")))
        assertFalse(matcher.contains(ip("8.8.8.8")))
    }

    @Test
    fun `prefix boundaries are exact, not approximate`() {
        val matcher = Matcher(listOf("31.13.24.0/21"))
        assertTrue(matcher.contains(ip("31.13.24.0")))
        assertTrue(matcher.contains(ip("31.13.31.255")))
        assertFalse(matcher.contains(ip("31.13.23.255")))
        assertFalse(matcher.contains(ip("31.13.32.0")))
    }

    @Test
    fun `an empty matcher matches nothing`() {
        assertFalse(Matcher.EMPTY.contains(ip("157.240.1.35")))
        assertEquals(0, Matcher.EMPTY.size)
    }

    /**
     * `-1 shl 32` is a no-op on Int rather than zero, so a naive mask computation makes a /0 match
     * *nothing* — the exact inverse of what it means.
     */
    @Test
    fun `a slash-zero prefix matches every address`() {
        val matcher = Matcher(listOf("0.0.0.0/0"))
        assertTrue(matcher.contains(ip("8.8.8.8")))
        assertTrue(matcher.contains(ip("157.240.1.35")))
        assertTrue(matcher.contains(0))
        assertTrue(matcher.contains(-1))
    }

    @Test
    fun `a slash-32 prefix matches exactly one address`() {
        val matcher = Matcher(listOf("1.2.3.4/32"))
        assertTrue(matcher.contains(ip("1.2.3.4")))
        assertFalse(matcher.contains(ip("1.2.3.5")))
        assertFalse(matcher.contains(ip("1.2.3.3")))
    }

    @Test
    fun `high addresses do not break on sign`() {
        // Anything above 127.x sets the top bit, making the Int negative. Comparison has to be
        // bitwise for those to work at all, and most of Meta's space is up there.
        val matcher = Matcher(listOf("204.15.20.0/22"))
        assertTrue(matcher.contains(ip("204.15.21.9")))
        assertFalse(matcher.contains(ip("204.15.24.0")))
    }

    @Test
    fun `malformed prefixes are dropped rather than throwing`() {
        assertNull(Matcher.parse("nonsense"))
        assertNull(Matcher.parse("157.240.0.0"))
        assertNull(Matcher.parse("157.240.0.0/33"))
        assertNull(Matcher.parse("157.240.0/16"))
        assertNull(Matcher.parse("157.240.0.256/16"))
        assertNotNull(Matcher.parse("157.240.0.0/16"))

        // One bad entry must not take the good ones with it.
        val matcher = Matcher(listOf("nonsense", "157.240.0.0/16"))
        assertEquals(1, matcher.size)
        assertTrue(matcher.contains(ip("157.240.1.1")))
    }

    @Test
    fun `addressAt reads an IPv4 destination out of a header`() {
        // Minimal IPv4 header; destination sits at offset 16.
        val packet = ByteArray(20)
        packet[16] = 157.toByte()
        packet[17] = 240.toByte()
        packet[18] = 1
        packet[19] = 35
        assertEquals(ip("157.240.1.35"), Matcher.addressAt(packet, 16))
    }

    @Test
    fun `every shipped prefix parses`() {
        for (service in ServiceRanges.ALL) {
            val matcher = Matcher(service.cidrs)
            assertEquals(
                "${service.id} has a malformed prefix",
                service.cidrs.size,
                matcher.size,
            )
        }
    }

    /**
     * The addresses are the point, so a typo that silently stops matching Facebook is worth a test.
     */
    @Test
    fun `known service addresses match their service`() {
        val meta = Matcher(ServiceRanges.byId("meta")!!.cidrs)
        assertTrue(meta.contains(ip("157.240.1.35")))   // facebook.com edge
        assertTrue(meta.contains(ip("31.13.71.36")))    // instagram/graph edge
        assertFalse(meta.contains(ip("8.8.8.8")))
        assertFalse(meta.contains(ip("140.82.121.4")))  // github

        val netflix = Matcher(ServiceRanges.byId("netflix")!!.cidrs)
        assertTrue(netflix.contains(ip("45.57.40.1")))
        assertFalse(netflix.contains(ip("157.240.1.35")))
    }

    /**
     * Anything that shares infrastructure with essential services must not be here — blocking
     * Google's or AWS's prefixes would take the phone off the internet.
     */
    @Test
    fun `no shipped range covers Google or AWS infrastructure`() {
        val everything = Matcher(ServiceRanges.ALL.flatMap { it.cidrs })
        for (address in listOf(
            "8.8.8.8",          // Google DNS
            "142.250.190.78",   // Google edge (Search, YouTube, Gmail)
            "172.217.16.14",    // Google edge
            "52.94.236.248",    // AWS
            "13.107.42.14",     // Microsoft
        )) {
            assertFalse("shipped ranges must not cover $address", everything.contains(ip(address)))
        }
    }

    @Test
    fun `services are grouped under the category that gates them`() {
        assertTrue(ServiceRanges.forCategory(CategoryId.SOCIAL).any { it.id == "meta" })
        assertTrue(ServiceRanges.forCategory(CategoryId.ENTERTAINMENT).any { it.id == "netflix" })
        assertTrue(ServiceRanges.forCategory(CategoryId.SCAM).isEmpty())
    }

    /** Meta's addresses carry WhatsApp too, and the UI has to be able to say so. */
    @Test
    fun `meta declares the collateral it takes with it`() {
        val meta = ServiceRanges.byId("meta")!!
        assertTrue(meta.alsoBlocks.contains("WhatsApp"))
        assertTrue(meta.alsoBlocks.contains("Messenger"))
    }
}
