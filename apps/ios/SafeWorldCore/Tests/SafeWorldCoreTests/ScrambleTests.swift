import XCTest
@testable import SafeWorldCore

final class ScrambleTests: XCTestCase {
    /// Pinned values produced by `packages/core/src/scramble.ts`.
    ///
    /// This is the contract between the TS generator and this Swift decoder. If
    /// either side changes its key, encoding, or normalization these fail —
    /// without them the drift would be silent, and the app would fetch a list
    /// it decodes to garbage and blocks nothing with.
    private let vectors = [
        ("bet365.com", "EQQSVhtCQREDCQ=="),
        ("pornhub.com", "Aw4UC0UCDVwPC0A="),
        ("example.com", "FhkHCF0bClwPC0A="),
    ]

    func testScrambleMatchesTheGenerator() {
        for (plain, scrambled) in vectors {
            XCTAssertEqual(Scramble.scramble(plain), scrambled)
        }
    }

    func testUnscrambleRecoversTheDomain() {
        for (plain, scrambled) in vectors {
            XCTAssertEqual(Scramble.unscramble(scrambled), plain)
        }
    }

    func testScrambledFormIsNotReadable() {
        XCTAssertFalse(Scramble.scramble("bet365.com").contains("bet365"))
    }

    func testEmptyAndInvalidInputDoNotThrowOrCorrupt() {
        XCTAssertEqual(Scramble.scramble(""), "")
        XCTAssertEqual(Scramble.unscramble(""), "")
        // One malformed entry must not take down a whole fetched list.
        XCTAssertEqual(Scramble.unscramble("!!! not base64 !!!"), "")
    }

    // MARK: Payload decoding

    func testPayloadUnscramblesScrambledDomains() throws {
        let payload = RemoteUpdatePayload(
            version: 1,
            format: Scramble.format,
            domains: ["list2": ["EQQSVhtCQREDCQ=="]]
        )
        XCTAssertEqual(try payload.domainsByCategory(), [.gambling: ["bet365.com"]])
    }

    func testPayloadWithoutFormatIsTreatedAsPlaintext() throws {
        // Payloads published before the `format` field must keep working.
        let payload = RemoteUpdatePayload(version: 1, domains: ["list2": ["bet365.com"]])
        XCTAssertEqual(try payload.domainsByCategory(), [.gambling: ["bet365.com"]])
    }

    func testPayloadRejectsAndroidsHashedList() {
        let payload = RemoteUpdatePayload(
            version: 1,
            format: Scramble.hashedFormat,
            domains: ["list2": ["d658ebf1a97d66b19cc925ece45b6255"]]
        )
        // Must throw rather than quietly install digests as if they were
        // domains, which would block nothing while looking fine.
        XCTAssertThrowsError(try payload.domainsByCategory())
    }

    func testPayloadRejectsAnUnknownFormat() {
        let payload = RemoteUpdatePayload(version: 1, format: "rot13-v9", domains: [:])
        XCTAssertThrowsError(try payload.domainsByCategory())
    }

    func testUnrecognizedCategoryKeysAreDropped() throws {
        let payload = RemoteUpdatePayload(
            version: 1,
            format: Scramble.format,
            domains: ["list2": ["EQQSVhtCQREDCQ=="], "not-a-category": ["FhkHCF0bClwPC0A="]]
        )
        XCTAssertEqual(try payload.domainsByCategory(), [.gambling: ["bet365.com"]])
    }
}
