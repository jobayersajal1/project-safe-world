import XCTest
@testable import SafeWorldCore

/// Mirrors `Ipv4UdpTest.kt`. Packet arithmetic is the kind of code that is
/// wrong in ways nothing reports — a bad checksum or length is silently dropped
/// by the OS — so it is tested away from the tunnel, which needs a device.
final class Ipv4UdpTests: XCTestCase {

    /// A well-formed IPv4/UDP packet carrying `payload`.
    private func packet(
        payload: [UInt8],
        source: [UInt8] = [10, 0, 0, 2],
        destination: [UInt8] = [1, 1, 1, 1],
        sourcePort: Int = 5555,
        destinationPort: Int = 53
    ) -> [UInt8] {
        let total = 20 + 8 + payload.count
        var p = [UInt8](repeating: 0, count: total)
        p[0] = 0x45
        p[2] = UInt8(total >> 8); p[3] = UInt8(total & 0xFF)
        p[6] = 0x40 // don't fragment
        p[8] = 64
        p[9] = 17 // UDP
        p.replaceSubrange(12..<16, with: source)
        p.replaceSubrange(16..<20, with: destination)
        p[20] = UInt8(sourcePort >> 8); p[21] = UInt8(sourcePort & 0xFF)
        p[22] = UInt8(destinationPort >> 8); p[23] = UInt8(destinationPort & 0xFF)
        let udpLen = 8 + payload.count
        p[24] = UInt8(udpLen >> 8); p[25] = UInt8(udpLen & 0xFF)
        p.replaceSubrange(28..<total, with: payload)
        return p
    }

    func testParsesAWellFormedDatagram() throws {
        let d = try XCTUnwrap(Ipv4Udp.parse(packet(payload: [1, 2, 3, 4])))
        XCTAssertEqual(d.sourceAddress, [10, 0, 0, 2])
        XCTAssertEqual(d.destinationAddress, [1, 1, 1, 1])
        XCTAssertEqual(d.sourcePort, 5555)
        XCTAssertEqual(d.destinationPort, 53)
        XCTAssertEqual(d.payload, [1, 2, 3, 4])
    }

    func testRejectsNonUdpAndNonIpv4() {
        var tcp = packet(payload: [0])
        tcp[9] = 6 // TCP
        XCTAssertNil(Ipv4Udp.parse(tcp))

        var v6 = packet(payload: [0])
        v6[0] = 0x65 // version 6
        XCTAssertNil(Ipv4Udp.parse(v6))
    }

    func testRejectsFragments() {
        var fragment = packet(payload: [0])
        fragment[6] = 0x20 // more-fragments
        XCTAssertNil(Ipv4Udp.parse(fragment))

        var offset = packet(payload: [0])
        offset[7] = 0x10 // non-zero fragment offset
        XCTAssertNil(Ipv4Udp.parse(offset))
    }

    func testRejectsTruncatedPackets() {
        XCTAssertNil(Ipv4Udp.parse([0x45, 0, 0]))
        XCTAssertNil(Ipv4Udp.parse(Array(packet(payload: [1, 2, 3, 4]).prefix(24))))
    }

    func testResponseSwapsAddressesAndPorts() throws {
        let request = try XCTUnwrap(Ipv4Udp.parse(packet(payload: [9, 9])))
        let reply = Ipv4Udp.buildResponse(to: request, payload: [7, 7, 7])
        let parsed = try XCTUnwrap(Ipv4Udp.parse(reply))

        // The answer must look like it came from the resolver the app asked.
        XCTAssertEqual(parsed.sourceAddress, request.destinationAddress)
        XCTAssertEqual(parsed.destinationAddress, request.sourceAddress)
        XCTAssertEqual(parsed.sourcePort, request.destinationPort)
        XCTAssertEqual(parsed.destinationPort, request.sourcePort)
        XCTAssertEqual(parsed.payload, [7, 7, 7])
    }

    func testHeaderChecksumIsValid() throws {
        let request = try XCTUnwrap(Ipv4Udp.parse(packet(payload: [1])))
        let reply = Ipv4Udp.buildResponse(to: request, payload: [1, 2, 3])

        // A receiver sums the header words; a correct checksum makes it 0xFFFF.
        var sum = 0
        for i in stride(from: 0, to: 20, by: 2) {
            sum += (Int(reply[i]) << 8) | Int(reply[i + 1])
        }
        while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16) }
        XCTAssertEqual(sum, 0xFFFF, "IPv4 header checksum must verify")
    }
}
