import Network
import XCTest
@testable import SafeWorldCore

/// End-to-end test of the DNS proxy: a real UDP query, through the real filter, over the real
/// socket.
///
/// This is the check the Windows and iOS equivalents never got — both ship code that has never
/// answered a packet. It runs on a high port so it needs no root and changes nothing about the
/// system, which is the whole reason it can exist.
final class DnsProxyTests: XCTestCase {

    /// The same vector every other filter test uses, over five known domains.
    private let vector =
        "U1dGMQIDAAByayudQ4udTQAAAAUAAAABAAAAGAAAAAAAAAAA3DngrQAAAADitA3cAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAQi1kAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXZa79wAAAACjFf1+AAAAAA=="

    private var proxy: DnsProxy!
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("proxy-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let bytes = Data(base64Encoded: vector)!
        for id in CategoryId.allCases {
            try bytes.write(to: directory.appendingPathComponent("\(id.rawValue).filter"))
        }

        let engine = try FilterEngine(directory: directory, settings: .defaults())
        proxy = DnsProxy(engine: engine)
        // Port 0 lets the OS pick a free high port — no root, no conflicts with anything.
        try proxy.start(port: 0)
        XCTAssertNotEqual(proxy.boundPort, 0, "proxy should have bound a port")
    }

    override func tearDownWithError() throws {
        proxy?.stop()
        proxy = nil
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    /// A standard recursive A query, as a resolver client would send it.
    private func query(_ name: String, id: UInt16 = 0x2A2A) -> Data {
        var bytes: [UInt8] = [
            UInt8(id >> 8), UInt8(id & 0xFF),
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
        ]
        for label in name.split(separator: ".") {
            bytes.append(UInt8(label.count))
            bytes.append(contentsOf: Array(label.utf8))
        }
        bytes.append(contentsOf: [0x00, 0x00, 0x01, 0x00, 0x01])
        return Data(bytes)
    }

    /// Send one query to the proxy and wait for the answer.
    private func ask(_ name: String, timeout: TimeInterval = 5) throws -> [UInt8]? {
        let connection = NWConnection(
            to: .hostPort(host: "127.0.0.1", port: .init(rawValue: proxy.boundPort)!),
            using: .udp
        )
        defer { connection.cancel() }
        connection.start(queue: .global())

        var answer: [UInt8]?
        let received = expectation(description: "answer for \(name)")
        connection.send(content: query(name), completion: .contentProcessed { _ in })
        connection.receiveMessage { data, _, _, _ in
            if let data { answer = [UInt8](data) }
            received.fulfill()
        }
        wait(for: [received], timeout: timeout)
        return answer
    }

    func testBlockedNameGetsNxDomain() throws {
        let answer = try XCTUnwrap(try ask("bet365.com"))

        // QR=1, and RCODE=3 (NXDOMAIN) — the client sees "host not found" and fails fast
        // rather than hanging on a connection to a dead address.
        XCTAssertEqual(answer[2] & 0x80, 0x80, "must be a response")
        XCTAssertEqual(answer[3] & 0x0F, 3, "must be NXDOMAIN")
        XCTAssertEqual(answer[6], 0, "no answer records")
        XCTAssertEqual(answer[7], 0)

        // The id is echoed, or the client would discard the reply as unsolicited.
        XCTAssertEqual(answer[0], 0x2A)
        XCTAssertEqual(answer[1], 0x2A)
    }

    func testSubdomainOfABlockedNameIsAlsoBlocked() throws {
        let answer = try XCTUnwrap(try ask("sports.live.bet365.com"))
        XCTAssertEqual(answer[3] & 0x0F, 3, "subdomains must be blocked too")
    }

    func testAllowedNameIsForwardedAndAnswered() throws {
        // Goes to a real resolver, so this needs network. A blocked name would come back NXDOMAIN
        // with zero answers; a forwarded one must not.
        guard let answer = try ask("wikipedia.org", timeout: 10) else {
            throw XCTSkip("no network available to reach the upstream resolver")
        }
        XCTAssertEqual(answer[2] & 0x80, 0x80, "must be a response")
        XCTAssertNotEqual(answer[3] & 0x0F, 3, "an allowed name must not be NXDOMAIN")
    }

    func testGarbageIsNotAnswered() throws {
        // Malformed input must not crash the proxy or produce a wrong answer; the next query
        // still has to work.
        let connection = NWConnection(
            to: .hostPort(host: "127.0.0.1", port: .init(rawValue: proxy.boundPort)!),
            using: .udp
        )
        connection.start(queue: .global())
        connection.send(content: Data([0x00, 0x01, 0x02]), completion: .contentProcessed { _ in })
        connection.cancel()

        let answer = try XCTUnwrap(try ask("bet365.com"), "proxy must survive malformed input")
        XCTAssertEqual(answer[3] & 0x0F, 3)
    }
}

/// Runs the proxy against the **real** generated filters — 4.48M domains — rather than the
/// five-domain vector. Skipped when they haven't been built, so a fresh clone still passes.
final class DnsProxyRealFilterTests: XCTestCase {

    private var proxy: DnsProxy!

    /// The filters `npm run build:ios` writes into the package's resources.
    private var filterDirectory: URL? {
        let here = URL(fileURLWithPath: #filePath)
        let resources = here
            .deletingLastPathComponent()   // SafeWorldCoreTests
            .deletingLastPathComponent()   // Tests
            .deletingLastPathComponent()   // SafeWorldCore
            .appendingPathComponent("Sources/SafeWorldCore/Resources")
        let probe = resources.appendingPathComponent("list2.filter")
        return FileManager.default.fileExists(atPath: probe.path) ? resources : nil
    }

    override func setUpWithError() throws {
        guard let directory = filterDirectory else {
            throw XCTSkip("filters not built — run `npm run build:ios`")
        }
        let engine = try FilterEngine(directory: directory, settings: .defaults())
        // Sanity: the real lists, not a stub.
        XCTAssertGreaterThan(engine.blockedDomainCount, 1_000_000, "expected the uncapped lists")
        proxy = DnsProxy(engine: engine)
        try proxy.start(port: 0)
    }

    override func tearDown() {
        proxy?.stop()
        proxy = nil
    }

    private func rcode(_ name: String) throws -> Int? {
        var bytes: [UInt8] = [0x33, 0x33, 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]
        for label in name.split(separator: ".") {
            bytes.append(UInt8(label.count))
            bytes.append(contentsOf: Array(label.utf8))
        }
        bytes.append(contentsOf: [0x00, 0x00, 0x01, 0x00, 0x01])

        let connection = NWConnection(
            to: .hostPort(host: "127.0.0.1", port: .init(rawValue: proxy.boundPort)!),
            using: .udp
        )
        defer { connection.cancel() }
        connection.start(queue: .global())

        var result: Int?
        let done = expectation(description: name)
        connection.send(content: Data(bytes), completion: .contentProcessed { _ in })
        connection.receiveMessage { data, _, _, _ in
            if let data, data.count > 3 { result = Int(data[3] & 0x0F) }
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
        return result
    }

    func testTheRealListsBlockAndAllowCorrectly() throws {
        for blocked in ["bet365.com", "pornhub.com", "www.bet365.com"] {
            XCTAssertEqual(try rcode(blocked), 3, "\(blocked) should be NXDOMAIN")
        }
        // These go upstream, so they need network; a wrong filter would return 3 regardless.
        for allowed in ["wikipedia.org", "github.com", "kernel.org"] {
            if let code = try rcode(allowed) {
                XCTAssertNotEqual(code, 3, "\(allowed) must not be blocked")
            }
        }
    }
}
