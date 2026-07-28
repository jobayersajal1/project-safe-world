import XCTest
@testable import SafeWorldCore

/// Pins the filter format against the TypeScript generator and the Kotlin
/// reader, using the *same* vector all three share.
///
/// This is the test that matters most here. A drift between the three does not
/// crash — it produces a filter that matches nothing, so the app installs,
/// runs, reports a healthy count, and blocks nothing at all.
final class FuseFilterTests: XCTestCase {

    /// Built by `scripts/binary-fuse.ts` over exactly `inSet`, byte-identical
    /// to the vector in `FuseFilterTest.kt`.
    private let vector =
        "U1dGMQIDAAByayudQ4udTQAAAAUAAAABAAAAGAAAAAAAAAAA3DngrQAAAADitA3cAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFAQi1kAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAXZa79wAAAACjFf1+AAAAAA=="

    private let inSet = [
        "bet365.com",
        "pornhub.com",
        "facebook.com",
        "example.org",
        "a.b.c.example.net",
    ]

    private func makeFilter() throws -> FuseFilter {
        try FuseFilter(data: Data(base64Encoded: vector)!)
    }

    func testGeneratorsFilterParsesHere() throws {
        let filter = try makeFilter()
        XCTAssertEqual(filter.size, inSet.count)
    }

    func testEveryDomainThatWentInIsFound() throws {
        let filter = try makeFilter()
        // False negatives are the failure this structure must never have: a
        // blocked site slipping through. They are impossible unless the three
        // implementations disagree.
        for domain in inSet {
            XCTAssertTrue(filter.contains(DomainHasher.key(domain)), "\(domain) must be found")
        }
    }

    func testDomainsNeverAddedAreNotFound() throws {
        let filter = try makeFilter()
        for domain in ["wikipedia.org", "github.com", "google.com"] {
            XCTAssertFalse(filter.contains(DomainHasher.key(domain)), "\(domain) must not match")
        }
    }

    func testKeyDerivationAgreesWithTheGenerator() {
        // Pinned from the generator. If this drifts, every shipped filter stops
        // matching with no error raised anywhere.
        XCTAssertEqual(DomainHasher.key("bet365.com"), 0xd658_ebf1_a97d_66b1)
        XCTAssertEqual(DomainHasher.key("pornhub.com"), 0x0291_a62d_8d87_66ec)
    }

    func testCorruptOrForeignFiltersAreRejected() {
        var bytes = Data(base64Encoded: vector)!

        var wrongMagic = bytes
        wrongMagic[0] = 0
        XCTAssertThrowsError(try FuseFilter(data: wrongMagic))

        let truncated = bytes.prefix(bytes.count - 4)
        XCTAssertThrowsError(try FuseFilter(data: Data(truncated)))

        XCTAssertThrowsError(try FuseFilter(data: Data(count: 4)))

        // A version this build cannot parse must fail, not match nothing.
        bytes[4] = 99
        XCTAssertThrowsError(try FuseFilter(data: bytes))
    }

    func testCandidatesStopBeforeTheBareTLD() {
        XCTAssertEqual(
            DomainHasher.candidates("a.b.example.com"),
            ["a.b.example.com", "b.example.com", "example.com"]
        )
        XCTAssertEqual(DomainHasher.candidates("example.com"), ["example.com"])
        XCTAssertEqual(DomainHasher.candidates("www.bet365.com"), ["bet365.com"])
        XCTAssertTrue(DomainHasher.candidates("").isEmpty)

        for host in ["example.com", "a.b.c.co.uk", "deep.sub.domain.org"] {
            XCTAssertTrue(
                DomainHasher.candidates(host).allSatisfy { $0.contains(".") },
                "no candidate for \(host) may be a single label"
            )
        }
    }

    func testSubdomainsMatchThroughTheFilter() throws {
        let filter = try makeFilter()
        func blocked(_ host: String) -> Bool {
            DomainHasher.candidates(host).contains { filter.contains(DomainHasher.key($0)) }
        }
        XCTAssertTrue(blocked("bet365.com"))
        XCTAssertTrue(blocked("www.bet365.com"))
        XCTAssertTrue(blocked("sports.live.bet365.com"))
        XCTAssertFalse(blocked("wikipedia.org"))
    }

    func testMappingAFileOnDiskGivesTheSameAnswers() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("fuse-\(UUID().uuidString).filter")
        try Data(base64Encoded: vector)!.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        // The tunnel maps the file rather than copying it, because a Network
        // Extension cannot afford 19 MB of heap. Both paths must agree.
        let mapped = try FuseFilter(path: url.path)
        XCTAssertEqual(mapped.size, inSet.count)
        for domain in inSet {
            XCTAssertTrue(mapped.contains(DomainHasher.key(domain)))
        }
        XCTAssertFalse(mapped.contains(DomainHasher.key("wikipedia.org")))
    }
}
