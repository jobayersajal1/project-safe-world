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
    fun `every category has a bundled filter`() {
        for (id in CategoryId.entries) {
            assertTrue("no bundled filter for ${id.id}", Blocklists.filter(id) != null)
            assertTrue("empty count for ${id.id}", Blocklists.count(id) > 0)
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
        // The whole point of the filter: unzipping the APK must not yield a
        // readable directory of the sites it blocks. The filter is opaque
        // binary, so this checks the sidecar carries no domains either.
        for (id in CategoryId.entries) {
            val meta = Blocklists::class.java
                .getResourceAsStream("/blocklists/${id.id}.json")!!
                .use { it.readBytes().decodeToString() }
            assertFalse("$id sidecar leaks a domain", meta.contains("bet365"))
            assertFalse("$id sidecar leaks a domain", meta.contains(".com"))
        }
    }

    @Test
    fun `a domain not on any list is not blocked`() {
        // The filter is approximate, so this is the property worth stating:
        // ordinary sites must not be caught. Any single domain could in
        // principle collide (~1 in 65,536), but these are checked.
        val lists = Blocklists.all()
        for (host in listOf("wikipedia.org", "github.com", "kernel.org", "gnu.org")) {
            assertFalse("$host must not be blocked", Matcher.decide(host, Settings.defaults(), lists).blocked)
        }
    }
}
