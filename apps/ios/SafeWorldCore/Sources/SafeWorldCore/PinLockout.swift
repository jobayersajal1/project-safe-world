import Foundation

/// How many wrong PINs are allowed before a cooldown, and how that cooldown
/// behaves. Pure policy with no storage dependency, so it can be unit-tested
/// against a fake clock; the app's `SettingsStore` owns persisting the `State`.
///
/// The counter and deadline are **persisted**, so force-quitting the app or
/// swiping it away doesn't reset the cooldown — otherwise the limit would be
/// bypassable in two taps and worth nothing.
///
/// Ported from `PinLockout.kt`, constants included.
public enum PinLockout {

    /// Wrong attempts allowed before the cooldown starts.
    public static let maxAttempts = 5

    /// How long the cooldown lasts once the limit is hit.
    public static let lockoutMilliseconds: Int64 = 15 * 60 * 1000

    /// - Parameters:
    ///   - failures: consecutive wrong attempts since the last success.
    ///   - lockedUntil: wall-clock ms when the cooldown ends, 0 if not locked.
    ///   - lockedAt: wall-clock ms when the cooldown started, used to notice the
    ///     clock being wound backwards.
    public struct State: Equatable, Sendable {
        public var failures: Int
        public var lockedUntil: Int64
        public var lockedAt: Int64

        public init(failures: Int = 0, lockedUntil: Int64 = 0, lockedAt: Int64 = 0) {
            self.failures = failures
            self.lockedUntil = lockedUntil
            self.lockedAt = lockedAt
        }
    }

    public static let cleared = State()

    /// Milliseconds left on the cooldown, or 0 if unlocked.
    ///
    /// If the clock has moved backwards since the lock started, the remaining
    /// time is treated as the full duration rather than trusting the new time —
    /// winding the clock back is the obvious way to try to skip a cooldown.
    /// Winding it *forwards* still skips it; that's a deliberate act well past
    /// the impulse this is meant to slow down, and is documented rather than
    /// defended against.
    public static func remaining(_ state: State, now: Int64) -> Int64 {
        guard state.lockedUntil != 0 else { return 0 }
        if now < state.lockedAt { return lockoutMilliseconds }
        return max(0, state.lockedUntil - now)
    }

    public static func isLocked(_ state: State, now: Int64) -> Bool {
        remaining(state, now: now) > 0
    }

    /// Attempts left before the next wrong entry triggers the cooldown.
    public static func attemptsRemaining(_ state: State) -> Int {
        max(0, maxAttempts - state.failures)
    }

    /// State after a wrong PIN: counts up, and starts the cooldown at the limit.
    public static func onFailure(_ state: State, now: Int64) -> State {
        let failures = state.failures + 1
        if failures < maxAttempts { return State(failures: failures) }
        return State(failures: failures, lockedUntil: now + lockoutMilliseconds, lockedAt: now)
    }

    /// State after a correct PIN: everything resets.
    public static func onSuccess() -> State { cleared }

    /// Clears a cooldown that has run its course, so the user gets a fresh five.
    public static func expireIfElapsed(_ state: State, now: Int64) -> State {
        (state.lockedUntil != 0 && !isLocked(state, now: now)) ? cleared : state
    }
}

/// Outcome of a PIN entry, so callers can tell "wrong" from "locked out".
public enum PinAttempt: Equatable, Sendable {
    case success

    /// Wrong PIN; `attemptsRemaining` entries left before a cooldown.
    case wrong(attemptsRemaining: Int)

    /// Too many wrong entries; `millisecondsRemaining` until another is accepted.
    case lockedOut(millisecondsRemaining: Int64)
}
