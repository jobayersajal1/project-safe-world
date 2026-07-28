package com.safeworld.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2 hashing for the user's PIN.
 *
 * The PIN exists to add friction to the user's own future self (this is a
 * self-control app), not to defend against an attacker with the device and a
 * disassembler — anyone with root or a rooted backup can clear the app's data
 * and be rid of it entirely. It is still stored hashed and salted rather than
 * in plaintext, because a 4-6 digit PIN is very likely reused elsewhere and
 * SharedPreferences is readable in a full-device backup.
 *
 * Deliberately uses only `java.*`/`javax.*` — no Android APIs — so it runs
 * under plain JVM unit tests. `java.util.Base64` needs API 26, which is our
 * minSdk.
 */
object PinHasher {
    /**
     * Chosen so a single verify is imperceptible (~100ms on a modern phone)
     * while brute-forcing the whole 4-digit space offline still costs real
     * time. Stored alongside each hash so this can be raised later without
     * invalidating existing PINs.
     */
    const val DEFAULT_ITERATIONS = 120_000

    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    /** A stored PIN record. Salt and hash are Base64; nothing here is secret-by-obscurity. */
    data class Record(val salt: String, val hash: String, val iterations: Int)

    fun hash(pin: String, iterations: Int = DEFAULT_ITERATIONS): Record {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return Record(
            salt = Base64.getEncoder().encodeToString(salt),
            hash = Base64.getEncoder().encodeToString(derive(pin, salt, iterations)),
            iterations = iterations,
        )
    }

    /** True if [pin] matches [record]. Comparison is constant-time. */
    fun verify(pin: String, record: Record): Boolean {
        val salt = runCatching { Base64.getDecoder().decode(record.salt) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(record.hash) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(pin, salt, record.iterations))
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // Clears the PIN copy PBEKeySpec holds internally.
            spec.clearPassword()
        }
    }
}
