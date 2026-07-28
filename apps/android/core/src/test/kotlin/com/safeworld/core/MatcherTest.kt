package com.safeworld.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors packages/core/test/matcher.test.ts — keep the two in sync. */
class MatcherTest {
    private fun lists(
        scam: List<String> = emptyList(),
        gambling: List<String> = listOf("bet365.com"),
        adult: List<String> = listOf("pornhub.com"),
    ): Map<CategoryId, Set<String>> = mapOf(
        CategoryId.SCAM to scam.toSet(),
        CategoryId.GAMBLING to gambling.toSet(),
        CategoryId.ADULT to adult.toSet(),
    )

    // region normalizeHost

    @Test
    fun `normalizeHost strips scheme, path, port, www and lowercases`() {
        assertEquals("bet365.com", Matcher.normalizeHost("HTTPS://WWW.Bet365.com:443/path?x=1"))
    }

    @Test
    fun `normalizeHost strips userinfo and trailing dot`() {
        assertEquals("sub.example.com", Matcher.normalizeHost("http://user:pass@sub.example.com./a"))
    }

    @Test
    fun `normalizeHost returns empty for blank input`() {
        assertEquals("", Matcher.normalizeHost("   "))
    }

    // endregion
    // region hostMatchesDomain

    @Test
    fun `hostMatchesDomain matches exact and subdomains but not siblings`() {
        assertTrue(Matcher.hostMatchesDomain("bet365.com", "bet365.com"))
        assertTrue(Matcher.hostMatchesDomain("m.bet365.com", "bet365.com"))
        assertFalse(Matcher.hostMatchesDomain("notbet365.com", "bet365.com"))
    }

    // endregion
    // region decide

    @Test
    fun `decide blocks a listed domain in an enabled category`() {
        val d = Matcher.decide("www.bet365.com", Settings.defaults(), lists())
        assertEquals(Matcher.BlockDecision(blocked = true, reason = "list2"), d)
    }

    @Test
    fun `decide blocks subdomains of a listed domain`() {
        assertTrue(Matcher.decide("sports.bet365.com", Settings.defaults(), lists()).blocked)
    }

    @Test
    fun `decide does not block when the master switch is off`() {
        val s = Settings.defaults().copy(enabled = false)
        assertFalse(Matcher.decide("bet365.com", s, lists()).blocked)
    }

    @Test
    fun `decide does not block when the category is disabled`() {
        val s = Settings.defaults().let {
            it.copy(categories = it.categories + (CategoryId.GAMBLING to false))
        }
        assertFalse(Matcher.decide("bet365.com", s, lists()).blocked)
    }

    @Test
    fun `decide custom allow overrides a category block`() {
        val s = Settings.defaults().copy(customAllow = listOf("bet365.com"))
        assertFalse(Matcher.decide("bet365.com", s, lists()).blocked)
    }

    @Test
    fun `decide custom block blocks an otherwise allowed domain`() {
        val s = Settings.defaults().copy(customBlock = listOf("example.com"))
        assertEquals(
            Matcher.BlockDecision(blocked = true, reason = "custom"),
            Matcher.decide("app.example.com", s, lists()),
        )
    }

    @Test
    fun `decide allows unlisted domains`() {
        assertFalse(Matcher.decide("wikipedia.org", Settings.defaults(), lists()).blocked)
    }

    // endregion
}
