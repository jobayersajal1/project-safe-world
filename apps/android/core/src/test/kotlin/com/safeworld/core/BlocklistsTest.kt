package com.safeworld.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `npm run build:android` export: if the resources are missing,
 * malformed, or hashed with a scheme this build can't reproduce, [Blocklists]
 * degrades to empty sets and the app blocks nothing — easy to miss without a
 * test, because everything still "works", just permissively.
 */
class BlocklistsTest {
    @Test
    fun `every category has bundled hashes`() {
        for (id in CategoryId.entries) {
            assertTrue("no bundled hashes for ${id.id}", Blocklists.hashes(id).isNotEmpty())
        }
    }

    @Test
    fun `bundled lists block a known domain and its subdomains through decide`() {
        val lists = Blocklists.all()
        assertTrue(Matcher.decide("www.bet365.com", Settings.defaults(), lists).blocked)
        assertTrue(Matcher.decide("sports.bet365.com", Settings.defaults(), lists).blocked)
        assertFalse(Matcher.decide("wikipedia.org", Settings.defaults(), lists).blocked)
    }

    @Test
    fun `the opt-in categories are off until the user asks for them`() {
        val lists = Blocklists.all()
        val defaults = Settings.defaults()

        for (category in Categories.optional) {
            assertFalse(
                "${category.id.id} must not be on by default",
                defaults.categories[category.id] == true,
            )
        }

        // Social media is a choice, not part of the promise — so an untouched
        // install must not block it, and enabling the list must.
        assertFalse(Matcher.decide("facebook.com", defaults, lists).blocked)
        val optedIn = defaults.copy(
            categories = defaults.categories + (CategoryId.SOCIAL to true),
        )
        assertTrue(Matcher.decide("facebook.com", optedIn, lists).blocked)
        assertTrue(Matcher.decide("m.facebook.com", optedIn, lists).blocked)
    }

    @Test
    fun `the protection categories are on by default`() {
        val defaults = Settings.defaults()
        for (category in Categories.mandatory) {
            assertTrue(
                "${category.id.id} is the reason the app exists",
                defaults.categories[category.id] == true,
            )
        }
    }

    @Test
    fun `the shipped resources contain no plaintext domains`() {
        // The whole point of hashing the Android list: unzipping the APK must
        // not yield a readable directory of the sites it blocks.
        for (id in CategoryId.entries) {
            for (entry in Blocklists.hashes(id)) {
                assertTrue(
                    "'$entry' does not look like a digest",
                    entry.length == DomainHasher.DIGEST_BYTES * 2 &&
                        entry.all { it in "0123456789abcdef" },
                )
            }
        }
    }
}
