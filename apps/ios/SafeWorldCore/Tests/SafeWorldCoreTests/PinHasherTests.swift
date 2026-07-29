import XCTest
@testable import SafeWorldCore

/// Mirrors `apps/android/app/src/test/kotlin/com/safeworld/app/security/PinHasherTest.kt`.
///
/// Every test uses a low iteration count. The point here is the behaviour, not
/// the cost, and 120,000 iterations per hash would make the suite take minutes.
/// `testTheDefaultCostIsActuallyPaid` covers the real number once.
final class PinHasherTests: XCTestCase {

    private let fast = 1_000

    func testTheRightPinVerifies() {
        let record = PinHasher.hash("1234", iterations: fast)
        XCTAssertTrue(PinHasher.verify("1234", record))
    }

    func testTheWrongPinDoesNot() {
        let record = PinHasher.hash("1234", iterations: fast)
        XCTAssertFalse(PinHasher.verify("1235", record))
        XCTAssertFalse(PinHasher.verify("", record))
        XCTAssertFalse(PinHasher.verify("12345", record))
    }

    /// The whole reason for storing a hash rather than the PIN.
    func testThePinCannotBeReadBackOut() {
        let record = PinHasher.hash("1234", iterations: fast)
        XCTAssertFalse(record.hash.contains("1234"))
        XCTAssertNotEqual(record.hash, "1234")
    }

    /// Without a per-record salt, two users with the same PIN would store the
    /// same hash, and one leaked rainbow table would cover both.
    func testTheSamePinHashesDifferentlyEachTime() {
        let first = PinHasher.hash("1234", iterations: fast)
        let second = PinHasher.hash("1234", iterations: fast)

        XCTAssertNotEqual(first.salt, second.salt)
        XCTAssertNotEqual(first.hash, second.hash)
        XCTAssertTrue(PinHasher.verify("1234", first))
        XCTAssertTrue(PinHasher.verify("1234", second))
    }

    /// The iteration count travels with the record, so raising the default later
    /// must not invalidate PINs already stored at the old cost.
    func testAnOldRecordStillVerifiesAtItsOwnCost() {
        let record = PinHasher.hash("1234", iterations: 500)
        XCTAssertEqual(500, record.iterations)
        XCTAssertTrue(PinHasher.verify("1234", record))
    }

    /// A corrupt or truncated record must read as "wrong PIN", never crash and
    /// never accidentally verify.
    func testAMalformedRecordFailsClosed() {
        let good = PinHasher.hash("1234", iterations: fast)

        XCTAssertFalse(PinHasher.verify("1234", .init(salt: "!!!", hash: good.hash, iterations: fast)))
        XCTAssertFalse(PinHasher.verify("1234", .init(salt: good.salt, hash: "!!!", iterations: fast)))
        XCTAssertFalse(PinHasher.verify("1234", .init(salt: "", hash: "", iterations: fast)))
    }

    /// Guards the parameter this file exists to get right: a default cheap
    /// enough to be imperceptible is also cheap enough to brute-force.
    func testTheDefaultCostIsActuallyPaid() {
        XCTAssertEqual(120_000, PinHasher.defaultIterations)

        let started = Date()
        let record = PinHasher.hash("1234")
        XCTAssertTrue(PinHasher.verify("1234", record))
        let elapsed = Date().timeIntervalSince(started)

        // Two derivations at the default cost. Loose bounds on purpose — this is
        // checking the work happens at all, not benchmarking the machine.
        XCTAssertGreaterThan(elapsed, 0.01, "the default cost is not being paid")
        XCTAssertLessThan(elapsed, 10, "a single verify should stay imperceptible")
    }
}
