package com.safeworld.core

import com.safeworld.core.FeedParser.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Kotlin half of the cross-platform feed-parsing contract.
 *
 * **Every vector here also appears in `packages/core/test/feed.test.ts`**, the
 * same arrangement `DomainHasherTest` and `ScrambleTests` use. It matters more
 * here than for those: the app downloads a third-party file at runtime and stores
 * what this produces, so a parser that quietly yields nothing gives a
 * subscription that says "enabled" and blocks not one extra domain.
 */
class FeedParserTest {

    @Test
    fun `hosts lines yield the mapped name, not the address`() {
        assertEquals("example.com", FeedParser.parseLine("0.0.0.0 example.com", Format.HOSTS))
        assertEquals(
            "example.com",
            FeedParser.parseLine("127.0.0.1 example.com # inline comment", Format.HOSTS),
        )
        assertNull(FeedParser.parseLine("0.0.0.0 localhost", Format.HOSTS))
        // One field is not a hosts line.
        assertNull(FeedParser.parseLine("example.com", Format.HOSTS))
    }

    @Test
    fun `plain domain lines pass through`() {
        assertEquals("example.com", FeedParser.parseLine("example.com", Format.DOMAINS))
        assertEquals("example.com", FeedParser.parseLine("example.com   ", Format.DOMAINS))
    }

    @Test
    fun `oisd wildcards lose the star, because subdomain matching already covers it`() {
        assertEquals("example.com", FeedParser.parseLine("*.example.com", Format.DOMAINSWILD))
        assertEquals("example.com", FeedParser.parseLine("example.com", Format.DOMAINSWILD))
        assertEquals("sub.example.com", FeedParser.parseLine("*.sub.example.com", Format.DOMAINSWILD))
    }

    @Test
    fun `adblock rules yield a domain only when they are purely a domain`() {
        assertEquals("example.com", FeedParser.parseLine("||example.com^", Format.ADBLOCK))
        assertEquals("example.com", FeedParser.parseLine("||example.com", Format.ADBLOCK))

        assertNull(FeedParser.parseLine("[Adblock Plus]", Format.ADBLOCK))
        assertNull(FeedParser.parseLine("! Title: something", Format.ADBLOCK))
        // "do not block" — cannot be expressed as an addition to a blocklist.
        assertNull(FeedParser.parseLine("@@||example.com^", Format.ADBLOCK))
        // URL-level rules a DNS layer cannot express.
        assertNull(FeedParser.parseLine("||example.com^\$third-party", Format.ADBLOCK))
        assertNull(FeedParser.parseLine("||example.com/path^", Format.ADBLOCK))
        assertNull(FeedParser.parseLine("/banner\\d+/", Format.ADBLOCK))
    }

    @Test
    fun `comments blanks addresses and stars are rejected in every format`() {
        for (format in Format.entries) {
            assertNull(FeedParser.parseLine("", format))
            assertNull(FeedParser.parseLine("# comment", format))
            assertNull(FeedParser.parseLine("! comment", format))
        }
        assertNull(FeedParser.parseLine("1.2.3.4", Format.DOMAINS))
        assertNull(FeedParser.parseLine("*", Format.DOMAINSWILD))
    }

    @Test
    fun `normalization matches the shared spec`() {
        assertEquals("example.com", FeedParser.parseLine("0.0.0.0 WWW.Example.COM", Format.HOSTS))
    }

    /**
     * The catastrophic case rather than the merely wrong one: a general-purpose
     * bare TLD stored as a blocked domain makes [Matcher.hostMatchesDomain] match
     * every host under it, so one stray line takes out every .com.
     */
    @Test
    fun `a general-purpose bare TLD is never yielded, however it is written`() {
        for (tld in listOf("com", "net", "org", "io", "co", "uk", "de", "app", "dev")) {
            for (line in listOf(tld, "*.$tld", "||$tld^", "0.0.0.0 $tld", "  $tld  ")) {
                for (format in Format.entries) {
                    assertNull("$format should reject '$line'", FeedParser.parseLine(line, format))
                }
            }
        }
    }

    /**
     * The deliberate exception. Blocking all of `.xxx` is what an adult-content
     * blocker is for, and the upstreams publish it — dropping those lines lost
     * the most valuable entries in the file.
     */
    @Test
    fun `adult and gambling TLDs are yielded, because upstreams block them whole`() {
        for (tld in listOf("xxx", "porn", "sex", "adult", "casino", "bet", "poker")) {
            assertEquals(tld, FeedParser.parseLine("*.$tld", Format.DOMAINSWILD))
            assertEquals(tld, FeedParser.parseLine("||$tld^", Format.ADBLOCK))
        }
    }

    @Test
    fun `parse de-duplicates and preserves first-seen order`() {
        val parsed = FeedParser.parse("b.com\na.com\nb.com\n", Format.DOMAINS)
        assertEquals(listOf("b.com", "a.com"), parsed.domains)
    }

    @Test
    fun `comments and blanks are not counted as skipped`() {
        val parsed = FeedParser.parse("# c\n\n!x\n[Adblock Plus]\na.com\n", Format.DOMAINS)
        assertEquals(listOf("a.com"), parsed.domains)
        assertEquals(0, parsed.skipped)
    }

    /**
     * "0 domains, many skipped" means the format is wrong; "0 and 0" means the
     * body was empty. The UI needs to tell those apart.
     */
    @Test
    fun `a wrong format is diagnosable from the skipped count`() {
        val wrongFormat = FeedParser.parse("||a.com^\n||b.com^\n", Format.HOSTS)
        assertEquals(emptyList<String>(), wrongFormat.domains)
        assertEquals(2, wrongFormat.skipped)

        val empty = FeedParser.parse("", Format.HOSTS)
        assertEquals(emptyList<String>(), empty.domains)
        assertEquals(0, empty.skipped)
    }

    @Test
    fun `a realistic hagezi header plus entries`() {
        val feed = listOf(
            "[Adblock Plus]",
            "! Title: HaGeZi's NSFW DNS Blocklist",
            "! Number of entries: 108676",
            "!",
            "||explorer.01coin.io^",
            "||eromitai.2chblog.jp^",
        ).joinToString("\n")

        val parsed = FeedParser.parse(feed, Format.ADBLOCK)
        assertEquals(listOf("explorer.01coin.io", "eromitai.2chblog.jp"), parsed.domains)
        assertEquals(0, parsed.skipped)
    }

    @Test
    fun `a realistic oisd header plus wildcard entries`() {
        val feed = listOf(
            "# Version: 202607300106",
            "# Syntax: Domains (wildcards)",
            "",
            "*.0-0.asia",
            "*.0-porno.com",
        ).joinToString("\n")

        assertEquals(
            listOf("0-0.asia", "0-porno.com"),
            FeedParser.parse(feed, Format.DOMAINSWILD).domains,
        )
    }

    /**
     * The whole point of subscriptions: a domain from a fetched feed has to block
     * through the same [Matcher.decide] path as a bundled one, including
     * subdomains, or the feature adds entries that never match.
     */
    @Test
    fun `a parsed domain blocks through the normal matcher, subdomains included`() {
        val parsed = FeedParser.parse("*.example.com\n*.xxx\n", Format.DOMAINSWILD)
        val set = PlainDomainSet(parsed.domains.toSet())

        assertEquals(true, set.matches("example.com"))
        assertEquals(true, set.matches("deep.sub.example.com"))
        assertEquals(true, set.matches("anything.xxx"))
        assertEquals(false, set.matches("notexample.com"))
    }

    /**
     * The bug this pairing is prone to, and the reason [BlockableTlds] is one
     * shared set rather than two lists that happen to match.
     *
     * Subscriptions are stored **hashed**, so matching goes through
     * [DomainHasher.candidates] rather than suffix comparison. `candidates` stops
     * before bare single-label names, because looking up "com" against a
     * probabilistic filter risks blocking every .com. That guard used to rest on
     * "a blocklist can never contain a bare name" — which stopped being true the
     * moment the parser started keeping `*.xxx`.
     *
     * Stored but never looked up is the worst kind of wrong: the app reports the
     * subscription as active and blocks nothing from it.
     */
    @Test
    fun `a bare adult TLD from a feed actually matches once hashed`() {
        val parsed = FeedParser.parse("*.xxx\n*.porn\n", Format.DOMAINSWILD)
        assertEquals(listOf("xxx", "porn"), parsed.domains)

        val stored = parsed.domains.map { DomainHasher.hash(it) }.toSet()
        val set = HashedDomainSet(stored)

        assertEquals("a host under a blocked TLD must match", true, set.matches("anything.xxx"))
        assertEquals(true, set.matches("deep.sub.porn"))
        assertEquals(true, set.matches("xxx"))
        assertEquals(false, set.matches("example.com"))
    }

    /**
     * The other half of that guard, which must stay intact: a general-purpose TLD
     * is never looked up, so even a filter collision on "com" cannot block the
     * web.
     */
    @Test
    fun `general-purpose TLDs are still never even looked up`() {
        for (host in listOf("example.com", "a.b.example.net", "thing.co.uk")) {
            val candidates = DomainHasher.candidates(host)
            for (candidate in candidates) {
                if (!candidate.contains('.')) {
                    assertEquals(
                        "candidates('$host') offered bare '$candidate' for lookup",
                        true,
                        candidate in BlockableTlds,
                    )
                }
            }
        }
        assertEquals(false, DomainHasher.candidates("example.com").contains("com"))
    }
}
