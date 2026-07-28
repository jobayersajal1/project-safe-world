import Foundation

/// A UDP datagram carried over IPv4, as read off the tunnel.
public struct UdpDatagram {
    public let sourceAddress: [UInt8]
    public let destinationAddress: [UInt8]
    public let sourcePort: Int
    public let destinationPort: Int
    public let payload: [UInt8]
}

/// Minimal IPv4 + UDP parsing and synthesis — just enough to pull DNS queries
/// out of the tunnel and write answers back into it.
///
/// A direct port of `Ipv4Udp.kt`, kept as pure byte handling with no networking
/// types so it can be unit-tested on macOS. Deliberately no TCP, no IPv6, no
/// fragment reassembly: the tunnel only claims DNS traffic, so anything else
/// arriving here is unexpected and gets dropped rather than mishandled.
public enum Ipv4Udp {
    private static let minIpv4Header = 20
    private static let udpHeader = 8
    private static let protocolUdp: UInt8 = 17

    /// Parsed datagram, or nil if the packet isn't an unfragmented IPv4/UDP one.
    public static func parse(_ packet: [UInt8]) -> UdpDatagram? {
        let length = packet.count
        guard length >= minIpv4Header else { return nil }
        guard (Int(packet[0]) >> 4) & 0x0F == 4 else { return nil }

        let headerLength = Int(packet[0] & 0x0F) * 4
        guard headerLength >= minIpv4Header, length >= headerLength else { return nil }

        // A non-zero fragment offset means we'd only be seeing part of the UDP
        // payload; MF set means more is coming. Neither is worth reassembling
        // for DNS, which fits in one datagram in practice.
        let flagsAndOffset = read16(packet, 6)
        guard flagsAndOffset & 0x1FFF == 0 else { return nil }
        guard flagsAndOffset & 0x2000 == 0 else { return nil }

        guard packet[9] == protocolUdp else { return nil }

        // totalLength is authoritative over the read size, which may include
        // trailing padding, but never trust it past what we actually read.
        let totalLength = min(read16(packet, 2), length)
        guard totalLength >= headerLength + udpHeader else { return nil }

        let udpLength = read16(packet, headerLength + 4)
        guard udpLength >= udpHeader else { return nil }
        let payloadEnd = min(headerLength + udpLength, totalLength)
        let payloadStart = headerLength + udpHeader
        guard payloadEnd >= payloadStart else { return nil }

        return UdpDatagram(
            sourceAddress: Array(packet[12..<16]),
            destinationAddress: Array(packet[16..<20]),
            sourcePort: read16(packet, headerLength),
            destinationPort: read16(packet, headerLength + 2),
            payload: Array(packet[payloadStart..<payloadEnd])
        )
    }

    /// Build the IPv4/UDP packet carrying `payload` back to whoever sent
    /// `request` — addresses and ports swapped, so the app that made the query
    /// sees an answer from the resolver it asked.
    public static func buildResponse(to request: UdpDatagram, payload: [UInt8]) -> [UInt8] {
        let total = minIpv4Header + udpHeader + payload.count
        var packet = [UInt8](repeating: 0, count: total)

        packet[0] = 0x45              // IPv4, 5 * 4 = 20-byte header
        packet[1] = 0                 // DSCP / ECN
        write16(&packet, 2, total)
        write16(&packet, 4, 0)        // identification (only matters for fragments)
        write16(&packet, 6, 0x4000)   // don't fragment
        packet[8] = 64                // TTL
        packet[9] = protocolUdp
        write16(&packet, 10, 0)       // checksum placeholder
        packet.replaceSubrange(12..<16, with: request.destinationAddress)
        packet.replaceSubrange(16..<20, with: request.sourceAddress)
        write16(&packet, 10, headerChecksum(packet))

        write16(&packet, 20, request.destinationPort)
        write16(&packet, 22, request.sourcePort)
        write16(&packet, 24, udpHeader + payload.count)
        // A zero UDP checksum means "not computed", which is legal over IPv4
        // (RFC 768) and saves computing the pseudo-header for every answer.
        write16(&packet, 26, 0)

        packet.replaceSubrange(28..<total, with: payload)
        return packet
    }

    /// One's-complement checksum over the 20-byte IPv4 header.
    private static func headerChecksum(_ packet: [UInt8]) -> Int {
        var sum = 0
        for i in stride(from: 0, to: minIpv4Header, by: 2) { sum += read16(packet, i) }
        while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16) }
        return ~sum & 0xFFFF
    }

    private static func read16(_ buffer: [UInt8], _ offset: Int) -> Int {
        (Int(buffer[offset]) << 8) | Int(buffer[offset + 1])
    }

    private static func write16(_ buffer: inout [UInt8], _ offset: Int, _ value: Int) {
        buffer[offset] = UInt8((value >> 8) & 0xFF)
        buffer[offset + 1] = UInt8(value & 0xFF)
    }
}
