package com.safeworld.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockoutTest {
    private val t0 = 1_000_000L

    /** Applies [count] consecutive wrong attempts starting from a clean state. */
    private fun afterFailures(count: Int, now: Long = t0): PinLockout.State {
        var state = PinLockout.CLEARED
        repeat(count) { state = PinLockout.onFailure(state, now) }
        return state
    }

    @Test
    fun `four wrong attempts do not lock`() {
        val state = afterFailures(4)
        assertFalse(PinLockout.isLocked(state, t0))
        assertEquals(1, PinLockout.attemptsRemaining(state))
    }

    @Test
    fun `the fifth wrong attempt starts the cooldown`() {
        val state = afterFailures(PinLockout.MAX_ATTEMPTS)
        assertTrue(PinLockout.isLocked(state, t0))
        assertEquals(PinLockout.LOCKOUT_MS, PinLockout.remaining(state, t0))
        assertEquals(0, PinLockout.attemptsRemaining(state))
    }

    @Test
    fun `the cooldown counts down and then expires`() {
        val state = afterFailures(PinLockout.MAX_ATTEMPTS)
        assertEquals(PinLockout.LOCKOUT_MS - 1000, PinLockout.remaining(state, t0 + 1000))
        assertFalse(PinLockout.isLocked(state, t0 + PinLockout.LOCKOUT_MS))
        assertEquals(0, PinLockout.remaining(state, t0 + PinLockout.LOCKOUT_MS + 1))
    }

    @Test
    fun `an expired cooldown resets to a fresh five attempts`() {
        val locked = afterFailures(PinLockout.MAX_ATTEMPTS)
        val fresh = PinLockout.expireIfElapsed(locked, t0 + PinLockout.LOCKOUT_MS)
        assertEquals(PinLockout.CLEARED, fresh)
        assertEquals(PinLockout.MAX_ATTEMPTS, PinLockout.attemptsRemaining(fresh))
    }

    @Test
    fun `an unexpired cooldown is left alone`() {
        val locked = afterFailures(PinLockout.MAX_ATTEMPTS)
        assertEquals(locked, PinLockout.expireIfElapsed(locked, t0 + 1000))
    }

    @Test
    fun `winding the clock backwards does not skip the cooldown`() {
        val state = afterFailures(PinLockout.MAX_ATTEMPTS)
        // The obvious bypass: set the device clock into the past.
        assertTrue(PinLockout.isLocked(state, t0 - 10_000_000))
        assertEquals(PinLockout.LOCKOUT_MS, PinLockout.remaining(state, t0 - 10_000_000))
    }

    @Test
    fun `a correct PIN clears the count`() {
        assertEquals(PinLockout.CLEARED, PinLockout.onSuccess())
        assertEquals(PinLockout.MAX_ATTEMPTS, PinLockout.attemptsRemaining(PinLockout.onSuccess()))
    }

    @Test
    fun `a clean state is not locked`() {
        assertFalse(PinLockout.isLocked(PinLockout.CLEARED, t0))
        assertEquals(PinLockout.MAX_ATTEMPTS, PinLockout.attemptsRemaining(PinLockout.CLEARED))
    }
}
