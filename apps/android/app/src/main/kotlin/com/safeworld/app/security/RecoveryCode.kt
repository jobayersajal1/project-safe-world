package com.safeworld.app.security

import java.security.SecureRandom

/**
 * The escape hatch for a forgotten PIN.
 *
 * There is no account and no server, so there is nobody to prove your identity
 * to — the only thing that can authorize a PIN reset is a secret the user
 * already holds. So one is generated at setup, shown once, and stored hashed
 * exactly like the PIN itself. Without it, a forgotten PIN would mean clearing
 * the app's data, which also removes the uninstall friction and every setting.
 *
 * It is deliberately long. The PIN is short because it's typed constantly and
 * is protected by an attempt limit; this is typed approximately once, so it can
 * afford ~100 bits and doesn't need a cooldown to stay unguessable — which
 * matters, because the cooldown is usually *why* someone reaches for it.
 *
 * Pure `java.*` so it runs under plain JVM unit tests, same as [PinHasher].
 */
object RecoveryCode {
    /**
     * Crockford-style base32: no I, L, O, or U. The first three are the classic
     * 1/l/0 confusions and U is dropped so a random code can't spell something
     * unfortunate. The user is copying this off a screen by hand, so ambiguity
     * is a real failure mode rather than a theoretical one.
     */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 20 symbols over a 32-symbol alphabet — 100 bits. */
    private const val LENGTH = 20

    /** Dashes every this many characters, for readability only. */
    private const val GROUP = 5

    /** A fresh code, formatted for display: `XXXXX-XXXXX-XXXXX-XXXXX`. */
    fun generate(): String {
        val random = SecureRandom()
        val raw = buildString(LENGTH) {
            // nextInt(bound) is unbiased for any bound, unlike `nextInt() % 32`.
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        return format(raw)
    }

    fun format(raw: String): String = raw.chunked(GROUP).joinToString("-")

    /**
     * Strips formatting and folds the characters Crockford excludes onto what
     * the user meant, so a code read off paper verifies whether or not they
     * typed the dashes, used lower case, or wrote `O` for zero.
     */
    fun normalize(input: String): String = buildString {
        for (c in input.uppercase()) {
            when (c) {
                'I', 'L' -> append('1')
                'O' -> append('0')
                in ALPHABET -> append(c)
                // Dashes, spaces, and anything else are formatting noise. `U`
                // lands here too: it's excluded from the alphabet, so it can
                // only be a misreading, and dropping it makes the code come out
                // the wrong length rather than silently wrong.
                else -> Unit
            }
        }
    }

    /** True if [input] could be a complete code, before spending PBKDF2 on it. */
    fun isWellFormed(input: String): Boolean = normalize(input).length == LENGTH
}
