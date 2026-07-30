package com.safeworld.core

import com.safeworld.core.FeedParser.Format
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Checks [FeedParser] against the TypeScript parser over the **whole real
 * corpus**, not just the shared vectors.
 *
 * [FeedParserTest] pins about thirty hand-written cases. That proves the two
 * implementations agree on the situations someone thought to write down; it does
 * not prove they agree on 929,455 lines of somebody else's file. This does, by
 * comparing a SHA-256 of the sorted output against the digest
 * `scripts/check-feed-parsers.ts` prints for the same input.
 *
 * **Skipped unless the feeds are already downloaded**, so the normal test run
 * stays offline and hermetic — the corpus is 18 MB of non-redistributable lists
 * that must never become a committed fixture. To run it:
 *
 *     npx tsx scripts/check-feed-parsers.ts            # downloads + prints digests
 *     ./gradlew :core:test -Dsafeworld.feedCache=<the cache path it printed>
 *
 * If a digest here disagrees with the one there, the ports have diverged and a
 * subscription will parse differently depending on which platform fetched it.
 */
class FeedParserCorpusTest {

    /** Digests produced by `scripts/check-feed-parsers.ts` on 2026-07-30. */
    private val expected = listOf(
        Triple(
            "hagezi-gambling", Format.ADBLOCK,
            "f2e084a41a998c5850127f32201bb3370ae3d53266e3049190c92312b03d09e0",
        ),
        Triple(
            "hagezi-nsfw", Format.ADBLOCK,
            "772d3cda540c70a77216c70b2ab0a7c5c3a546ca78fab3431fef3629fbb2e23b",
        ),
        Triple(
            "oisd-nsfw", Format.DOMAINSWILD,
            "5ad577a7262ac7252be97096c9611b8a6eb0b2caec380a4065a9981004137a7c",
        ),
    )

    @Test
    fun `the Kotlin parser produces the same corpus as the TypeScript one`() {
        val cache = System.getProperty("safeworld.feedCache")
        assumeTrue("set -Dsafeworld.feedCache=<dir> to run this", cache != null)

        for ((id, format, digest) in expected) {
            val file = File(cache, "$id.txt")
            assumeTrue("missing ${file.path}", file.exists())

            val parsed = FeedParser.parse(file.readText(), format)

            // Sorted, so iteration order is not mistaken for a behavioural
            // difference. The feeds shift daily, so a mismatch means either the
            // parsers diverged or the cache is newer than the pinned digest —
            // re-run the script to tell which.
            val actual = sha256(parsed.domains.sorted().joinToString("\n"))
            if (actual != digest) {
                throw AssertionError(
                    "$id: parsed ${parsed.domains.size} domains, skipped ${parsed.skipped}\n" +
                        "  expected $digest\n" +
                        "  actual   $actual\n" +
                        "  Either the Kotlin and TypeScript parsers disagree, or the cached feed " +
                        "is newer than the pinned digest. Re-run scripts/check-feed-parsers.ts.",
                )
            }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
