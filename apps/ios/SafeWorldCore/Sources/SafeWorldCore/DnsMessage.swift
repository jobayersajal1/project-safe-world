import Foundation

/// Just enough DNS wire-format handling for the sinkhole: read the queried name
/// out of a request, and synthesize an NXDOMAIN answer for names we block.
///
/// A direct port of `DnsMessage.kt`. Kept as pure byte handling with no
/// networking types so it can be unit-tested on macOS, exactly like the Kotlin
/// original — the tunnel itself cannot be tested without a device.
///
/// NXDOMAIN is used rather than answering `0.0.0.0` so the client fails fast
/// with "host not found" instead of hanging on a connection to a dead address.
public enum DnsMessage {
    private static let headerSize = 12
    private static let questionTail = 4 // QTYPE + QCLASS
    private static let maxLabelLength = 63
    private static let maxNameLength = 255

    private struct Question {
        let name: String
        let endOffset: Int
    }

    /// The queried name (no trailing dot), or nil if `payload` isn't a
    /// single-question standard query we understand.
    public static func questionName(_ payload: [UInt8]) -> String? {
        parseQuestion(payload)?.name
    }

    /// An NXDOMAIN response echoing `query`'s id and question section, or nil
    /// if the query couldn't be parsed.
    public static func nxDomainResponse(_ query: [UInt8]) -> [UInt8]? {
        guard let question = parseQuestion(query) else { return nil }
        var response = Array(query[0..<question.endOffset])

        // QR=1 (response), preserve OPCODE and RD from the query.
        let flags0 = Int(query[2])
        response[2] = UInt8(0x80 | (flags0 & 0x78) | (flags0 & 0x01))
        // RA=1 (recursion available), RCODE=3 (NXDOMAIN).
        response[3] = 0x83

        write16(&response, 4, 1) // QDCOUNT
        write16(&response, 6, 0) // ANCOUNT
        write16(&response, 8, 0) // NSCOUNT
        write16(&response, 10, 0) // ARCOUNT
        return response
    }

    private static func parseQuestion(_ payload: [UInt8]) -> Question? {
        guard payload.count >= headerSize else { return nil }
        // Must be a query (QR=0), standard opcode, with exactly one question.
        guard Int(payload[2]) & 0x80 == 0 else { return nil }
        guard (Int(payload[2]) >> 3) & 0x0F == 0 else { return nil }
        guard read16(payload, 4) == 1 else { return nil }

        var name = ""
        var offset = headerSize
        var nameLength = 0

        while true {
            guard offset < payload.count else { return nil }
            let length = Int(payload[offset])
            // A compression pointer is not legal in the question section, and
            // following one would risk a parsing loop.
            guard length & 0xC0 == 0 else { return nil }
            offset += 1
            if length == 0 { break }
            guard length <= maxLabelLength else { return nil }
            guard offset + length <= payload.count else { return nil }

            nameLength += length + 1
            guard nameLength <= maxNameLength else { return nil }

            let label = payload[offset..<(offset + length)]
            guard let text = String(bytes: label, encoding: .ascii) else { return nil }
            if !name.isEmpty { name += "." }
            name += text
            offset += length
        }

        let end = offset + questionTail
        guard end <= payload.count else { return nil }
        return Question(name: name, endOffset: end)
    }

    private static func read16(_ buffer: [UInt8], _ offset: Int) -> Int {
        (Int(buffer[offset]) << 8) | Int(buffer[offset + 1])
    }

    private static func write16(_ buffer: inout [UInt8], _ offset: Int, _ value: Int) {
        buffer[offset] = UInt8((value >> 8) & 0xFF)
        buffer[offset + 1] = UInt8(value & 0xFF)
    }
}
