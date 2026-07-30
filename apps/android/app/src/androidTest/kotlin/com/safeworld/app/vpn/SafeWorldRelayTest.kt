package com.safeworld.app.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device, because the point is the native library: whether it loads for this ABI and
 * whether its packet parsing agrees with what the JVM believes.
 *
 * The malformed cases are the ones that matter. Under full-tunnel mode this code sees every packet
 * on the device, including whatever a hostile or broken app emits, and a header length that points
 * past the end of the buffer must be refused rather than read — otherwise it classifies traffic
 * based on whatever memory happened to follow.
 */
@RunWith(AndroidJUnit4::class)
class SafeWorldRelayTest {

    /** Minimal IPv4 packet with a transport header. */
    private fun ipv4(
        protocol: Int,
        sourcePort: Int = 1234,
        destinationPort: Int = 53,
        headerWords: Int = 5,
        totalLength: Int = 28,
        destination: IntArray = intArrayOf(8, 8, 8, 8),
    ): ByteArray {
        val packet = ByteArray(totalLength)
        packet[0] = ((4 shl 4) or headerWords).toByte()
        packet[9] = protocol.toByte()
        for (i in 0..3) packet[12 + i] = 10  // source 10.10.10.10
        for (i in 0..3) packet[16 + i] = destination[i].toByte()
        val header = headerWords * 4
        if (totalLength >= header + 4) {
            packet[header] = (sourcePort shr 8).toByte()
            packet[header + 1] = (sourcePort and 0xFF).toByte()
            packet[header + 2] = (destinationPort shr 8).toByte()
            packet[header + 3] = (destinationPort and 0xFF).toByte()
        }
        return packet
    }

    @Test
    fun theNativeLibraryLoadsOnThisDevice() {
        assertTrue("libsafeworld_relay.so did not load", SafeWorldRelay.available)
    }

    @Test
    fun udpToPort53IsRecognisedAsDns() {
        val packet = ipv4(protocol = 17, destinationPort = 53)
        assertEquals(SafeWorldRelay.Verdict.DNS, SafeWorldRelay.classify(packet, packet.size))
    }

    @Test
    fun udpToAnotherPortIsNotDns() {
        val packet = ipv4(protocol = 17, destinationPort = 443)
        assertEquals(SafeWorldRelay.Verdict.PASS, SafeWorldRelay.classify(packet, packet.size))
    }

    @Test
    fun tcpIsNeverDnsEvenOnPort53() {
        // Port 53 over TCP is a real thing (zone transfers, large answers), but the existing filter
        // only understands datagrams, so it must not be handed one.
        val packet = ipv4(protocol = 6, destinationPort = 53)
        assertEquals(SafeWorldRelay.Verdict.PASS, SafeWorldRelay.classify(packet, packet.size))
    }

    @Test
    fun theDestinationAddressMatchesWhatTheJvmWouldRead() {
        val packet = ipv4(protocol = 6, destination = intArrayOf(157, 240, 1, 35))
        val expected = (157 shl 24) or (240 shl 16) or (1 shl 8) or 35
        assertEquals(expected, SafeWorldRelay.destination(packet, packet.size))
    }

    @Test
    fun highAddressesSurviveTheSignBoundary() {
        // Anything above 127.x sets the top bit. Getting this wrong makes half the address space
        // compare incorrectly against the drop set.
        val packet = ipv4(protocol = 6, destination = intArrayOf(204, 15, 20, 9))
        val expected = (204 shl 24) or (15 shl 16) or (20 shl 8) or 9
        assertEquals(expected, SafeWorldRelay.destination(packet, packet.size))
    }

    @Test
    fun aHeaderLengthPastTheEndIsRefused() {
        // Claims a 60-byte header inside a 28-byte packet. Reading the ports at that offset would
        // run off the buffer.
        val packet = ipv4(protocol = 17, headerWords = 15, totalLength = 28)
        assertEquals(SafeWorldRelay.Verdict.MALFORMED, SafeWorldRelay.classify(packet, packet.size))
        assertEquals(0, SafeWorldRelay.destination(packet, packet.size))
    }

    @Test
    fun aHeaderLengthBelowTheMinimumIsRefused() {
        val packet = ipv4(protocol = 17, headerWords = 3, totalLength = 28)
        assertEquals(SafeWorldRelay.Verdict.MALFORMED, SafeWorldRelay.classify(packet, packet.size))
    }

    @Test
    fun aTruncatedTransportHeaderIsRefused() {
        // Full IP header, but only two bytes of ports rather than four.
        val packet = ipv4(protocol = 17, totalLength = 22)
        assertEquals(SafeWorldRelay.Verdict.MALFORMED, SafeWorldRelay.classify(packet, packet.size))
    }

    @Test
    fun emptyAndUndersizedPacketsAreRefused() {
        assertEquals(SafeWorldRelay.Verdict.MALFORMED, SafeWorldRelay.classify(ByteArray(0), 0))
        assertEquals(SafeWorldRelay.Verdict.MALFORMED, SafeWorldRelay.classify(ByteArray(8), 8))
        assertEquals(0, SafeWorldRelay.destination(ByteArray(0), 0))
    }

    /**
     * IPv6 must read as "not IPv4", not as "broken". Full-tunnel mode routing `::/0` while this
     * reported malformed would drop every v6 packet on the device silently.
     */
    @Test
    fun ipv6IsRecognisedRatherThanTreatedAsMalformed() {
        val packet = ByteArray(40)
        packet[0] = (6 shl 4).toByte()
        packet[6] = 6 // next header: TCP
        assertEquals(SafeWorldRelay.Verdict.PASS, SafeWorldRelay.classify(packet, packet.size))
    }

    @Test
    fun aTruncatedIpv6HeaderIsRefused() {
        val packet = ByteArray(20)
        packet[0] = (6 shl 4).toByte()
        assertEquals(SafeWorldRelay.Verdict.MALFORMED, SafeWorldRelay.classify(packet, packet.size))
    }
}
