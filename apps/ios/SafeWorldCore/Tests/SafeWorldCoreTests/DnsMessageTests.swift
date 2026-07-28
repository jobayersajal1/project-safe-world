import XCTest
@testable import SafeWorldCore

/// Mirrors `DnsMessageTest.kt`. The tunnel that uses this cannot be tested
/// without a device, so the wire-format handling underneath it is kept pure and
/// tested here — the same split the Android app makes.
final class DnsMessageTests: XCTestCase {

    /// A standard recursive A query for `name`, as a client would send it.
    private func query(_ name: String, id: Int = 0x1234) -> [UInt8] {
        var bytes: [UInt8] = [
            UInt8(id >> 8), UInt8(id & 0xFF), // transaction id
            0x01, 0x00,                       // QR=0, opcode=0, RD=1
            0x00, 0x01,                       // QDCOUNT = 1
            0x00, 0x00,                       // ANCOUNT
            0x00, 0x00,                       // NSCOUNT
            0x00, 0x00,                       // ARCOUNT
        ]
        for label in name.split(separator: ".") {
            bytes.append(UInt8(label.count))
            bytes.append(contentsOf: Array(label.utf8))
        }
        bytes.append(contentsOf: [
            0x00,       // root label
            0x00, 0x01, // QTYPE = A
            0x00, 0x01, // QCLASS = IN
        ])
        return bytes
    }

    func testReadsAMultiLabelName() {
        XCTAssertEqual(DnsMessage.questionName(query("sports.bet365.com")), "sports.bet365.com")
    }

    func testRejectsATruncatedPacket() {
        let q = query("bet365.com")
        XCTAssertNil(DnsMessage.questionName(Array(q.dropLast(6))))
    }

    func testRejectsAResponse() {
        var q = query("bet365.com")
        q[2] = 0x81 // QR=1
        XCTAssertNil(DnsMessage.questionName(q))
    }

    func testRejectsACompressionPointerInTheQuestion() {
        var q = query("bet365.com")
        q[12] = 0xC0 // pointer where a label length belongs
        XCTAssertNil(DnsMessage.questionName(q))
    }

    func testNxDomainEchoesTheIdAndQuestion() throws {
        let q = query("bet365.com", id: 0xBEEF)
        let response = try XCTUnwrap(DnsMessage.nxDomainResponse(q))

        XCTAssertEqual(response[0], 0xBE)
        XCTAssertEqual(response[1], 0xEF)
        // QR=1, RD preserved from the query.
        XCTAssertEqual(response[2] & 0x80, 0x80)
        XCTAssertEqual(response[2] & 0x01, 0x01)
        // RCODE = 3 (NXDOMAIN), RA=1.
        XCTAssertEqual(response[3] & 0x0F, 3)

        // One question echoed, no answers.
        XCTAssertEqual(Int(response[4]) << 8 | Int(response[5]), 1)
        XCTAssertEqual(Int(response[6]) << 8 | Int(response[7]), 0)

        // The question section is copied verbatim, so the resolver's client
        // matches the reply to its outstanding query.
        XCTAssertEqual(Array(response[12...]), Array(q[12...]))
        XCTAssertEqual(DnsMessage.questionName(q), "bet365.com")
    }

    func testNxDomainRefusesWhatItCannotParse() {
        XCTAssertNil(DnsMessage.nxDomainResponse([0x00, 0x01]))
    }
}
