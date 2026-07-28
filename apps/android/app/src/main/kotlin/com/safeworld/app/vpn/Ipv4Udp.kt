package com.safeworld.app.vpn

/**
 * A UDP datagram carried over IPv4, as read off the tun interface.
 *
 * Not a `data class`: the address/payload fields are [ByteArray]s, whose
 * identity-based `equals` would make a generated `equals` quietly wrong.
 */
class UdpDatagram(
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)

/**
 * Minimal IPv4 + UDP parsing and synthesis — just enough to pull DNS queries
 * off the tun interface and write answers back into it.
 *
 * Deliberately no TCP, no IPv6, no fragment reassembly: [SafeWorldVpnService]
 * only routes the system's IPv4 DNS servers into the tunnel, so anything else
 * arriving here is unexpected and gets dropped rather than mishandled.
 */
object Ipv4Udp {
    private const val MIN_IPV4_HEADER = 20
    private const val UDP_HEADER = 8
    private const val PROTOCOL_UDP = 17

    /** Parsed datagram, or null if the packet isn't an unfragmented IPv4/UDP one. */
    fun parse(packet: ByteArray, length: Int = packet.size): UdpDatagram? {
        if (length < MIN_IPV4_HEADER) return null
        if ((packet[0].toInt() ushr 4) and 0x0F != 4) return null

        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < MIN_IPV4_HEADER || length < headerLength) return null

        // A non-zero fragment offset means we'd only be seeing part of the UDP
        // payload; MF set means more is coming. Neither is worth reassembling
        // for DNS, which fits in one datagram in practice.
        val flagsAndOffset = read16(packet, 6)
        if (flagsAndOffset and 0x1FFF != 0) return null
        if (flagsAndOffset and 0x2000 != 0) return null

        if (packet[9].toInt() and 0xFF != PROTOCOL_UDP) return null

        // totalLength is authoritative over the read size, which may include
        // trailing padding, but never trust it past what we actually read.
        val totalLength = read16(packet, 2).coerceAtMost(length)
        if (totalLength < headerLength + UDP_HEADER) return null

        val udpLength = read16(packet, headerLength + 4)
        if (udpLength < UDP_HEADER) return null
        val payloadEnd = (headerLength + udpLength).coerceAtMost(totalLength)
        val payloadStart = headerLength + UDP_HEADER
        if (payloadEnd < payloadStart) return null

        return UdpDatagram(
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            sourcePort = read16(packet, headerLength),
            destinationPort = read16(packet, headerLength + 2),
            payload = packet.copyOfRange(payloadStart, payloadEnd),
        )
    }

    /**
     * Build the IPv4/UDP packet carrying [payload] back to whoever sent
     * [request] — addresses and ports swapped, so the app that made the query
     * sees an answer from the resolver it asked.
     */
    fun buildResponse(request: UdpDatagram, payload: ByteArray): ByteArray {
        val total = MIN_IPV4_HEADER + UDP_HEADER + payload.size
        val packet = ByteArray(total)

        packet[0] = 0x45              // IPv4, 5 * 4 = 20-byte header
        packet[1] = 0                 // DSCP / ECN
        write16(packet, 2, total)
        write16(packet, 4, 0)         // identification (only matters for fragments)
        write16(packet, 6, 0x4000)    // don't fragment
        packet[8] = 64                // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        write16(packet, 10, 0)        // checksum placeholder
        request.destinationAddress.copyInto(packet, 12)
        request.sourceAddress.copyInto(packet, 16)
        write16(packet, 10, headerChecksum(packet))

        write16(packet, 20, request.destinationPort)
        write16(packet, 22, request.sourcePort)
        write16(packet, 24, UDP_HEADER + payload.size)
        // A zero UDP checksum means "not computed", which is legal over IPv4
        // (RFC 768) and saves computing the pseudo-header for every answer.
        write16(packet, 26, 0)

        payload.copyInto(packet, 28)
        return packet
    }

    /** One's-complement checksum over the 20-byte IPv4 header. */
    private fun headerChecksum(packet: ByteArray): Int {
        var sum = 0
        for (i in 0 until MIN_IPV4_HEADER step 2) sum += read16(packet, i)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun read16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun write16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}
