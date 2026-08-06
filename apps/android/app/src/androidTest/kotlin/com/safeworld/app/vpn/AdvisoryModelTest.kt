package com.safeworld.app.vpn

import android.util.Log
import java.util.Locale
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.safeworld.core.AdvisorySettings
import com.safeworld.core.CategoryId
import com.safeworld.core.DomainModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device, because the parts that can only fail there are the parts the
 * JVM tests cannot reach: whether 342 KB of base64 weights per category actually
 * survive into the APK and parse back, and whether scoring is fast enough to sit
 * on the DNS path.
 *
 * `:core:test` reads the same files off the classpath and proves the arithmetic.
 * What it cannot prove is packaging. A resource that failed to ship would leave
 * `DomainModel.bundled` empty, `advisoryBlocks` returning false for everything,
 * and an app that looks healthy while the feature does nothing — the failure
 * this project is arranged to prevent everywhere else.
 */
@RunWith(AndroidJUnit4::class)
class AdvisoryModelTest {

    @Test
    fun modelsAreActuallyPackagedInTheApk() {
        val models = DomainModel.bundled
        assertEquals("both shipped categories must be present", 2, models.size)
        assertEquals(setOf(CategoryId.GAMBLING, CategoryId.ADULT), models.map { it.category }.toSet())
        for (m in models) {
            assertEquals("weight table for ${m.category}", DomainModel.TABLE_SIZE, m.values.size)
            // A table that decoded to all zeroes would score every host at the
            // bias and look like a working model that never fires.
            assertTrue("weights for ${m.category} are all zero", m.values.any { it.toInt() != 0 })
            assertTrue("block tier must be stricter for ${m.category}", m.blockThreshold > m.threshold)
        }
    }

    @Test
    fun blocksOnlyWhatTheStrictTierCovers() {
        val models = DomainModel.bundled
        val gambling = DomainModel.advise("best-casino-slots-bonus.com", models, allowBlocking = true)
        assertNotNull("a plainly gambling name must be caught", gambling)
        assertEquals(DomainModel.Action.BLOCK, gambling!!.action)
        assertEquals(CategoryId.GAMBLING, gambling.category)

        val adult = DomainModel.advise("adult-xxx-tube-videos.com", models, allowBlocking = true)
        assertNotNull(adult)
        assertEquals(DomainModel.Action.BLOCK, adult!!.action)
        assertEquals(CategoryId.ADULT, adult.category)
    }

    @Test
    fun ordinarySitesAreUntouched() {
        // The sites where one wrong block ends the user's trust in the whole
        // product. `acme-plumbing-services.com` stands for the long tail the
        // model must not flag for merely being unknown.
        val models = DomainModel.bundled
        for (host in listOf(
            "github.com", "wikipedia.org", "nhs.uk", "chase.com", "gov.uk",
            "who.int", "sciencedirect.com", "acme-plumbing-services.com",
            "stackoverflow.com", "archive.org", "python.org",
        )) {
            assertNull(host, DomainModel.advise(host, models, allowBlocking = true))
        }
    }

    @Test
    fun sharedPlatformsAreNeverBlocked() {
        // Blogger's image CDN serves the pictures on every Blogger blog there
        // is; the adult model scores it 6.29, which is why the guard exists
        // rather than a higher threshold.
        val models = DomainModel.bundled
        for (host in listOf("1.bp.blogspot.com", "3.bp.blogspot.com", "someone.github.io")) {
            assertNull(host, DomainModel.advise(host, models, allowBlocking = true))
        }
    }

    @Test
    fun settingsGateBothTiers() {
        val models = DomainModel.bundled
        val off = AdvisorySettings(enabled = false)
        assertFalse(off.enabledFor(CategoryId.GAMBLING))

        val gamblingOnly = AdvisorySettings(
            enabled = true,
            categories = mapOf(CategoryId.GAMBLING to true, CategoryId.ADULT to false),
        )
        assertTrue(gamblingOnly.enabledFor(CategoryId.GAMBLING))
        assertFalse(gamblingOnly.enabledFor(CategoryId.ADULT))

        // With adult switched off, an adult name must fall through even though
        // the model would block it.
        val enabled = models.filter { gamblingOnly.enabledFor(it.category) }
        assertNull(DomainModel.advise("adult-xxx-tube-videos.com", enabled, allowBlocking = true))
    }

    @Test
    fun warnTierIsInertUnlessBlockingIsAskedFor() {
        // The tunnel is the only caller that passes true. Everything else must
        // get a warn at most, whatever the score.
        val models = DomainModel.bundled
        val verdict = DomainModel.advise("best-casino-slots-bonus.com", models)
        assertEquals(DomainModel.Action.WARN, verdict?.action)
    }

    @Test
    fun scoringIsCheapEnoughForTheDnsPath() {
        // Every DNS query for a name no list covers pays this once, before the
        // per-host cache takes over. Warm up first so the measurement is steady
        // state rather than class loading and the lazy resource read.
        val models = DomainModel.bundled
        repeat(200) { DomainModel.advise("warmup-$it.example", models, allowBlocking = true) }

        val hosts = List(2000) { "some-unseen-host-number-$it.example" }
        val started = System.nanoTime()
        for (h in hosts) DomainModel.advise(h, models, allowBlocking = true)
        val perHostMicros = (System.nanoTime() - started) / 1000.0 / hosts.size

        // Generous by design: this is a regression guard, not a benchmark. A
        // port that dropped the sparse representation and multiplied the whole
        // 262,144-weight table per host would land orders of magnitude above it.
        assertTrue("$perHostMicros µs per host is too slow for the DNS path", perHostMicros < 2000.0)
        // Log, not println: Gradle uninstalls the test APK when the run ends, so
        // stdout is gone by the time anyone looks. logcat survives it.
        // Locale.ROOT, or a device set to Arabic logs "٢٢٤٫٢" and the number is
        // unreadable in a bug report.
        Log.i(
            "AdvisoryModelTest",
            String.format(Locale.ROOT, "advisory scoring: %.1f us per host, two models", perHostMicros),
        )
    }
}
