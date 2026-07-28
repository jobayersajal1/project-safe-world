package com.safeworld.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCodeTest {

    @Test
    fun `a generated code is well formed and self-consistent`() {
        val code = RecoveryCode.generate()
        assertTrue("should be dash-grouped for reading: $code", code.contains("-"))
        assertTrue(RecoveryCode.isWellFormed(code))
        assertEquals(20, RecoveryCode.normalize(code).length)
    }

    @Test
    fun `codes do not repeat`() {
        val codes = List(50) { RecoveryCode.generate() }
        assertEquals("generated codes must be unique", codes.size, codes.toSet().size)
    }

    @Test
    fun `the ambiguous letters never appear, so they can only be misreadings`() {
        val generated = List(200) { RecoveryCode.normalize(RecoveryCode.generate()) }.joinToString("")
        for (c in "ILOU") {
            assertFalse("'$c' is too easily misread to be in the alphabet", generated.contains(c))
        }
    }

    @Test
    fun `a code read off paper verifies however it was typed`() {
        val code = RecoveryCode.generate()
        val canonical = RecoveryCode.normalize(code)

        assertEquals(canonical, RecoveryCode.normalize(code.lowercase()))
        assertEquals(canonical, RecoveryCode.normalize(code.replace("-", "")))
        assertEquals(canonical, RecoveryCode.normalize(code.replace("-", " ")))
        assertEquals(canonical, RecoveryCode.normalize("  $code  "))
    }

    @Test
    fun `the characters people confuse are folded onto what they meant`() {
        assertEquals("101", RecoveryCode.normalize("IO1"))
        assertEquals("101", RecoveryCode.normalize("LOl"))
    }

    @Test
    fun `an incomplete or overlong code is rejected before hashing`() {
        assertFalse(RecoveryCode.isWellFormed(""))
        assertFalse(RecoveryCode.isWellFormed("ABCDE-FGHJK"))
        assertFalse(RecoveryCode.isWellFormed(RecoveryCode.generate() + "X"))
        // 'U' is dropped rather than mapped, so a code containing one comes out
        // short instead of silently verifying as something else.
        assertFalse(RecoveryCode.isWellFormed(RecoveryCode.generate().dropLast(1) + "U"))
    }

    @Test
    fun `a stored code cannot be read back out of its hash`() {
        val code = RecoveryCode.generate()
        val record = PinHasher.hash(RecoveryCode.normalize(code), iterations = 1_000)

        assertTrue(PinHasher.verify(RecoveryCode.normalize(code), record))
        assertFalse(PinHasher.verify(RecoveryCode.normalize(RecoveryCode.generate()), record))
        assertNotEquals(RecoveryCode.normalize(code), record.hash)
    }
}
