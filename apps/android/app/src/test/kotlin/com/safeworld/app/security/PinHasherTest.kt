package com.safeworld.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    // Low iteration count: these tests exercise the logic, not the work factor,
    // and 120k iterations per case would make the suite crawl.
    private val fast = 1_000

    @Test
    fun `verify accepts the correct pin`() {
        val record = PinHasher.hash("2468", fast)
        assertTrue(PinHasher.verify("2468", record))
    }

    @Test
    fun `verify rejects a wrong pin`() {
        val record = PinHasher.hash("2468", fast)
        assertFalse(PinHasher.verify("2469", record))
        assertFalse(PinHasher.verify("", record))
        assertFalse(PinHasher.verify("24680", record))
    }

    @Test
    fun `the same pin hashes differently each time`() {
        val a = PinHasher.hash("1234", fast)
        val b = PinHasher.hash("1234", fast)
        assertNotEquals("salts must be random", a.salt, b.salt)
        assertNotEquals("equal hashes would leak that two users share a PIN", a.hash, b.hash)
        assertTrue(PinHasher.verify("1234", a))
        assertTrue(PinHasher.verify("1234", b))
    }

    @Test
    fun `the pin is not recoverable from the stored record`() {
        val record = PinHasher.hash("1234", fast)
        assertFalse(record.hash.contains("1234"))
        assertFalse(record.salt.contains("1234"))
    }

    @Test
    fun `verify fails closed on a corrupt record`() {
        assertFalse(PinHasher.verify("1234", PinHasher.Record("!!not base64!!", "also bad", fast)))
    }

    @Test
    fun `a record carries its own iteration count so it survives raising the default`() {
        val record = PinHasher.hash("1234", fast)
        assertTrue(PinHasher.verify("1234", record))
        // A record written at a different work factor must not silently fail.
        assertFalse(PinHasher.verify("1234", record.copy(iterations = fast + 1)))
    }
}
