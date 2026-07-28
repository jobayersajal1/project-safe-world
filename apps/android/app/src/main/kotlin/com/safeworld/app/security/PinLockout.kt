package com.safeworld.app.security

/**
 * How many wrong PINs are allowed before a cooldown, and how that cooldown
 * behaves. Pure policy with no Android or storage dependency, so it can be
 * unit-tested against a fake clock; [com.safeworld.app.SettingsStore] owns
 * persisting the [State].
 *
 * The counter and deadline are **persisted**, so force-stopping the app or
 * swiping it away doesn't reset the cooldown — otherwise the limit would be
 * bypassable in two taps and worth nothing.
 */
object PinLockout {
    /** Wrong attempts allowed before the cooldown starts. */
    const val MAX_ATTEMPTS = 5

    /** How long the cooldown lasts once the limit is hit. */
    const val LOCKOUT_MS = 15 * 60 * 1000L

    /**
     * @param failures consecutive wrong attempts since the last success.
     * @param lockedUntil wall-clock ms when the cooldown ends, 0 if not locked.
     * @param lockedAt wall-clock ms when the cooldown started, used to notice
     *   the clock being wound backwards.
     */
    data class State(
        val failures: Int = 0,
        val lockedUntil: Long = 0,
        val lockedAt: Long = 0,
    )

    val CLEARED = State()

    /**
     * Milliseconds left on the cooldown, or 0 if unlocked.
     *
     * If the clock has moved backwards since the lock started, the remaining
     * time is treated as the full duration rather than trusting the new time —
     * winding the clock back is the obvious way to try to skip a cooldown.
     * Winding it *forwards* still skips it; that's a deliberate act well past
     * the impulse this is meant to slow down, and is documented rather than
     * defended against (see apps/android/README.md).
     */
    fun remaining(state: State, now: Long): Long {
        if (state.lockedUntil == 0L) return 0
        if (now < state.lockedAt) return LOCKOUT_MS
        return (state.lockedUntil - now).coerceAtLeast(0)
    }

    fun isLocked(state: State, now: Long): Boolean = remaining(state, now) > 0

    /** Attempts left before the next wrong entry triggers the cooldown. */
    fun attemptsRemaining(state: State): Int =
        (MAX_ATTEMPTS - state.failures).coerceAtLeast(0)

    /** State after a wrong PIN: counts up, and starts the cooldown at the limit. */
    fun onFailure(state: State, now: Long): State {
        val failures = state.failures + 1
        if (failures < MAX_ATTEMPTS) return State(failures = failures)
        return State(failures = failures, lockedUntil = now + LOCKOUT_MS, lockedAt = now)
    }

    /** State after a correct PIN: everything resets. */
    fun onSuccess(): State = CLEARED

    /** Clears a cooldown that has run its course, so the user gets a fresh five. */
    fun expireIfElapsed(state: State, now: Long): State =
        if (state.lockedUntil != 0L && !isLocked(state, now)) CLEARED else state
}

/** Outcome of a PIN entry, so callers can tell "wrong" from "locked out". */
sealed interface PinAttempt {
    data object Success : PinAttempt

    /** Wrong PIN; [attemptsRemaining] entries left before a cooldown. */
    data class Wrong(val attemptsRemaining: Int) : PinAttempt

    /** Too many wrong entries; [millisRemaining] until another is accepted. */
    data class LockedOut(val millisRemaining: Long) : PinAttempt
}
