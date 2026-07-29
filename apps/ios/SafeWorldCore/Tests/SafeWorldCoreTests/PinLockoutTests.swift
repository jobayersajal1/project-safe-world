import XCTest
@testable import SafeWorldCore

/// Mirrors `apps/android/app/src/test/kotlin/com/safeworld/app/security/PinLockoutTest.kt`.
///
/// Everything takes an explicit `now`, so the cooldown is tested against a fake
/// clock rather than by sleeping.
final class PinLockoutTests: XCTestCase {

    private let t0: Int64 = 1_000_000

    func testAFreshStateIsNotLocked() {
        XCTAssertFalse(PinLockout.isLocked(.init(), now: t0))
        XCTAssertEqual(0, PinLockout.remaining(.init(), now: t0))
        XCTAssertEqual(5, PinLockout.attemptsRemaining(.init()))
    }

    func testWrongEntriesCountDownWithoutLockingUntilTheLimit() {
        var state = PinLockout.State()
        for expected in stride(from: 4, through: 1, by: -1) {
            state = PinLockout.onFailure(state, now: t0)
            XCTAssertEqual(expected, PinLockout.attemptsRemaining(state))
            XCTAssertFalse(PinLockout.isLocked(state, now: t0), "locked after \(state.failures) failures")
        }
    }

    func testTheFifthWrongEntryStartsTheCooldown() {
        var state = PinLockout.State()
        for _ in 0..<5 { state = PinLockout.onFailure(state, now: t0) }

        XCTAssertTrue(PinLockout.isLocked(state, now: t0))
        XCTAssertEqual(PinLockout.lockoutMilliseconds, PinLockout.remaining(state, now: t0))
        XCTAssertEqual(0, PinLockout.attemptsRemaining(state))
    }

    func testTheCooldownCountsDownAndThenExpires() {
        var state = PinLockout.State()
        for _ in 0..<5 { state = PinLockout.onFailure(state, now: t0) }

        let halfway = t0 + PinLockout.lockoutMilliseconds / 2
        XCTAssertEqual(PinLockout.lockoutMilliseconds / 2, PinLockout.remaining(state, now: halfway))

        let after = t0 + PinLockout.lockoutMilliseconds
        XCTAssertFalse(PinLockout.isLocked(state, now: after))
        XCTAssertEqual(0, PinLockout.remaining(state, now: after))
    }

    /// The cooldown having elapsed must return a *fresh* five, not one attempt
    /// that re-locks immediately.
    func testExpiryRestoresTheFullAllowance() {
        var state = PinLockout.State()
        for _ in 0..<5 { state = PinLockout.onFailure(state, now: t0) }

        let after = t0 + PinLockout.lockoutMilliseconds + 1
        let expired = PinLockout.expireIfElapsed(state, now: after)

        XCTAssertEqual(PinLockout.cleared, expired)
        XCTAssertEqual(5, PinLockout.attemptsRemaining(expired))
    }

    func testExpiryLeavesARunningCooldownAlone() {
        var state = PinLockout.State()
        for _ in 0..<5 { state = PinLockout.onFailure(state, now: t0) }

        let halfway = t0 + PinLockout.lockoutMilliseconds / 2
        XCTAssertEqual(state, PinLockout.expireIfElapsed(state, now: halfway))
    }

    func testACorrectPinClearsEverything() {
        var state = PinLockout.State()
        for _ in 0..<3 { state = PinLockout.onFailure(state, now: t0) }

        XCTAssertEqual(PinLockout.cleared, PinLockout.onSuccess())
        XCTAssertEqual(5, PinLockout.attemptsRemaining(PinLockout.onSuccess()))
    }

    /// Winding the clock back is the obvious way to try to skip a cooldown, so a
    /// `now` earlier than the lock started is treated as the full duration
    /// rather than as time served.
    func testWindingTheClockBackDoesNotShortenTheCooldown() {
        var state = PinLockout.State()
        for _ in 0..<5 { state = PinLockout.onFailure(state, now: t0) }

        let backwards = t0 - 60 * 60 * 1000
        XCTAssertTrue(PinLockout.isLocked(state, now: backwards))
        XCTAssertEqual(PinLockout.lockoutMilliseconds, PinLockout.remaining(state, now: backwards))
        XCTAssertEqual(state, PinLockout.expireIfElapsed(state, now: backwards))
    }

    /// Pins the two numbers the whole policy is: five tries, fifteen minutes.
    func testThePolicyConstantsAreWhatTheUiPromises() {
        XCTAssertEqual(5, PinLockout.maxAttempts)
        XCTAssertEqual(15 * 60 * 1000, PinLockout.lockoutMilliseconds)
    }
}
