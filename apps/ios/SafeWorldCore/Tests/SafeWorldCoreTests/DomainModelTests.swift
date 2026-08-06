import XCTest
@testable import SafeWorldCore

/// The cross-port contract for the advisory feature map.
///
/// These numbers were produced by `scripts/domain_features.py`, which is the
/// definition; `packages/core/test/advisory.test.ts`, `DomainModelTest.kt` and
/// `DomainModelTests.cs` pin the same table. A port that hashes differently,
/// normalises differently, or forgets the L2 step fails here rather than
/// shipping a platform that quietly disagrees about the same domain.
///
/// The feature vector is pinned rather than the score, deliberately: the
/// hashing, the normalisation, the structural tokens and the L2 step are the
/// fragile parts and this catches all of them, while the score would also drag
/// 341 KB of weights into a target that has nothing to score for yet.
///
/// Every made-up name here is absent from all seven blocklists and the real ones
/// are famous sites or platform hosts, so this table publishes nothing.
final class DomainModelTests: XCTestCase {

    /// host, non-zero count, sum of indexes, count of negative values.
    ///
    /// The negative count is what pins the sign bit. An earlier version of this
    /// table used the sum of absolute values instead, which is worth nothing:
    /// with no collisions every value is ±1/‖v‖, so that sum is just √nnz and
    /// agrees no matter how the hash behaves.
    private let pinned: [(String, Int, Int, Int)] = [
        ("best-casino-slots-bonus.com", 85, 12_369_141, 47),
        ("adult-xxx-tube-videos.com", 79, 10_123_884, 36),
        ("github.com", 34, 4_750_261, 20),
        ("wikipedia.org", 43, 5_594_899, 17),
        ("nhs.uk", 22, 2_508_037, 10),
        ("acme-plumbing-services.com", 82, 11_115_176, 37),
        ("xn--test-punycode-9za.net", 79, 9_862_878, 41),
        ("a.b.c.example.co.uk", 61, 7_332_332, 25),
        ("3.bp.blogspot.com", 55, 7_031_982, 21),
        ("www.Example.COM", 37, 4_971_527, 16),
        ("", 0, 0, 0),
    ]

    func testFeaturesMatchTheOtherPorts() {
        for (host, nnz, indexSum, negatives) in pinned {
            let f = DomainModel.features(host)
            XCTAssertEqual(f.count, nnz, "non-zero count for \(host)")
            XCTAssertEqual(f.keys.reduce(0, +), indexSum, "index sum for \(host)")
            XCTAssertEqual(f.values.filter { $0 < 0 }.count, negatives, "negative count for \(host)")
        }
    }

    func testFeaturesAreL2Normalised() {
        for host in ["bet365.com", "a.b.c.example.co.uk", "x.io"] {
            let sum = DomainModel.features(host).values.reduce(0) { $0 + $1 * $1 }
            XCTAssertEqual(sum, 1.0, accuracy: 1e-10)
        }
    }

    func testIndexesStayInsideTheTable() {
        for (idx, _) in DomainModel.features("some-long-hyphenated-name-99.example.co.uk") {
            XCTAssertGreaterThanOrEqual(idx, 0)
            XCTAssertLessThan(idx, DomainModel.tableSize)
        }
    }

    func testLeadingWwwAndCaseAreIgnored() {
        // Matches `normalizeHost` in Matcher.swift, which the model must not
        // diverge from — the same host reaching two different verdicts by way
        // of a prefix would be indefensible.
        XCTAssertEqual(DomainModel.features("Example.COM").count, DomainModel.features("www.example.com").count)
        XCTAssertEqual(DomainModel.normalize("  WWW.Example.com.  "), "example.com")
    }

    func testEmptyHostHasNoFeatures() {
        XCTAssertTrue(DomainModel.features("").isEmpty)
        XCTAssertTrue(DomainModel.features("   ").isEmpty)
    }

    func testSharedPlatformsAreNeverScored() {
        // The guard exists because the adult model learned *.blogspot.com and
        // then flagged the image host serving every Blogger blog there is.
        XCTAssertTrue(DomainModel.isSharedPlatformHost("1.bp.blogspot.com"))
        XCTAssertTrue(DomainModel.isSharedPlatformHost("3.bp.blogspot.com"))
        XCTAssertTrue(DomainModel.isSharedPlatformHost("videoseriesbiblicas.blogspot.com"))
        XCTAssertTrue(DomainModel.isSharedPlatformHost("someone.github.io"))
        XCTAssertFalse(DomainModel.isSharedPlatformHost("github.com"))
        XCTAssertFalse(DomainModel.isSharedPlatformHost("blogspot.com.evil.example"))
    }

    func testAdviseHonoursTheGuardAndTheThreshold() {
        // A weight table of zeroes scores every host at the bias, so the
        // threshold alone decides — enough to check the plumbing without
        // depending on a generated model being present.
        let always = DomainModel.Weights(
            category: .gambling, scale: 1, bias: 1, threshold: 0,
            values: [Int8](repeating: 0, count: DomainModel.tableSize))
        XCTAssertEqual(DomainModel.advise("anything.example", models: [always]), .gambling)
        XCTAssertNil(DomainModel.advise("anyone.blogspot.com", models: [always]))
        XCTAssertNil(DomainModel.advise("", models: [always]))

        let never = DomainModel.Weights(
            category: .gambling, scale: 1, bias: 0, threshold: 1,
            values: [Int8](repeating: 0, count: DomainModel.tableSize))
        XCTAssertNil(DomainModel.advise("anything.example", models: [never]))
    }
}
