package com.safeworld.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4UdpTest {
    private val client = byteArrayOf(10, 111, 222.toByte(), 2)
    private val resolver = byteArrayOf(8, 8, 8, 8)

    private fun packet(
        payload: ByteArray = byteArrayOf(1, 2, 3, 4),
        protocol: Int = 17,
        flagsAndOffset: Int = 0x4000,
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total ushr 8).toByte(); p[3] = total.toByte()
        p[6] = (flagsAndOffset ushr 8).toByte(); p[7] = flagsAndOffset.toByte()
        p[8] = 64
        p[9] = protocol.toByte()
        client.copyInto(p, 12)
        resolver.copyInto(p, 16)
        p[20] = 0xC0.toByte(); p[21] = 0x01 // source port 49153
        p[22] = 0x00; p[23] = 0x35          // destination port 53
        val udpLength = 8 + payload.size
        p[24] = (udpLength ushr 8).toByte(); p[25] = udpLength.toByte()
        payload.copyInto(p, 28)
        return p
    }

    @Test
    fun `parse reads addresses, ports and payload`() {
        val d = Ipv4Udp.parse(packet())!!
        assertArrayEquals(client, d.sourceAddress)
        assertArrayEquals(resolver, d.destinationAddress)
        assertEquals(49153, d.sourcePort)
        assertEquals(53, d.destinationPort)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), d.payload)
    }

    @Test
    fun `parse ignores trailing bytes beyond the IP total length`() {
        val padded = packet() + byteArrayOf(9, 9, 9)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), Ipv4Udp.parse(padded)!!.payload)
    }

    @Test
    fun `parse rejects non-UDP, fragments and short packets`() {
        assertNull(Ipv4Udp.parse(packet(protocol = 6)))            // TCP
        assertNull(Ipv4Udp.parse(packet(flagsAndOffset = 0x0001))) // later fragment
        assertNull(Ipv4Udp.parse(packet(flagsAndOffset = 0x2000))) // more fragments
        assertNull(Ipv4Udp.parse(ByteArray(8)))
    }

    @Test
    fun `buildResponse swaps endpoints and round-trips through parse`() {
        val request = Ipv4Udp.parse(packet())!!
        val answer = byteArrayOf(7, 7, 7)
        val response = Ipv4Udp.parse(Ipv4Udp.buildResponse(request, answer))!!

        assertArrayEquals(resolver, response.sourceAddress)
        assertArrayEquals(client, response.destinationAddress)
        assertEquals(53, response.sourcePort)
        assertEquals(49153, response.destinationPort)
        assertArrayEquals(answer, response.payload)
    }

    @Test
    fun `buildResponse writes a valid IPv4 header checksum`() {
        val request = Ipv4Udp.parse(packet())!!
        val raw = Ipv4Udp.buildResponse(request, byteArrayOf(7, 7, 7))

        // Summing a correct header (checksum field included) yields 0xFFFF.
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
        assertTrue(raw.size == 20 + 8 + 3)
    }
}
