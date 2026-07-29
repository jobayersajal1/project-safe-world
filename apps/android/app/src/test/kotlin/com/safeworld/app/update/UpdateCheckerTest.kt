package com.safeworld.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing and asset selection, against the real shape of a GitHub release
 * response.
 *
 * [LATEST_RELEASE_JSON] is trimmed from an actual reply for the v0.1.0 release
 * — abbreviated, but with its unknown fields left in on purpose, because the
 * one thing that would silently break this is GitHub adding a field and the
 * decoder refusing the whole document over it.
 */
class UpdateCheckerTest {

    @Test
    fun `parses a real release payload, ignoring fields it does not know`() {
        val release = UpdateChecker.parse(LATEST_RELEASE_JSON)
        assertEquals("v0.1.0", release.tagName)
        assertEquals(4, release.assets.size)
    }

    @Test
    fun `picks the apk out of a release that also carries dmg, exe and zip`() {
        val update = UpdateChecker.select(UpdateChecker.parse(LATEST_RELEASE_JSON), "0.0.9")
        assertNotNull(update)
        assertTrue(update!!.apkUrl!!.endsWith("SafeWorld-0.1.0.apk"))
        assertEquals(20243764L, update.sizeBytes)
    }

    @Test
    fun `strips the leading v so the tag compares against a versionName`() {
        val update = UpdateChecker.select(UpdateChecker.parse(LATEST_RELEASE_JSON), "0.0.9")
        assertEquals("0.1.0", update!!.version)
    }

    @Test
    fun `the release the user is already on is not an update`() {
        assertNull(UpdateChecker.select(UpdateChecker.parse(LATEST_RELEASE_JSON), "0.1.0"))
    }

    @Test
    fun `a newer install is not offered an older release`() {
        assertNull(UpdateChecker.select(UpdateChecker.parse(LATEST_RELEASE_JSON), "0.2.0"))
    }

    @Test
    fun `drafts and pre-releases are not offered`() {
        val draft = UpdateChecker.parse(LATEST_RELEASE_JSON.replace("\"draft\": false", "\"draft\": true"))
        assertNull(UpdateChecker.select(draft, "0.0.9"))

        val pre = UpdateChecker.parse(
            LATEST_RELEASE_JSON.replace("\"prerelease\": false", "\"prerelease\": true"),
        )
        assertNull(UpdateChecker.select(pre, "0.0.9"))
    }

    @Test
    fun `a release with no apk still offers its page rather than nothing`() {
        // The desktop-only release case: there is something newer, but nothing
        // this platform can install directly.
        val noApk = UpdateChecker.parse(
            LATEST_RELEASE_JSON.replace("SafeWorld-0.1.0.apk", "SafeWorld-0.1.0.txt"),
        )
        val update = UpdateChecker.select(noApk, "0.0.9")
        assertNotNull(update)
        assertNull(update!!.apkUrl)
        assertEquals("https://github.com/jobayersajal1/project-safe-world/releases/tag/v0.1.0", update.pageUrl)
    }

    private companion object {
        val LATEST_RELEASE_JSON = """
        {
          "url": "https://api.github.com/repos/jobayersajal1/project-safe-world/releases/258",
          "id": 258,
          "html_url": "https://github.com/jobayersajal1/project-safe-world/releases/tag/v0.1.0",
          "tag_name": "v0.1.0",
          "target_commitish": "main",
          "name": "Safe World 0.1.0 — Android, macOS, Windows",
          "draft": false,
          "prerelease": false,
          "created_at": "2026-07-27T01:05:44Z",
          "published_at": "2026-07-27T01:30:42Z",
          "assets": [
            {
              "name": "SafeWorld-0.1.0-macOS.dmg",
              "content_type": "application/x-apple-diskimage",
              "state": "uploaded",
              "size": 2523649,
              "download_count": 3,
              "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0-macOS.dmg"
            },
            {
              "name": "SafeWorld-0.1.0-win-x64.exe",
              "content_type": "application/x-msdownload",
              "state": "uploaded",
              "size": 144246169,
              "download_count": 1,
              "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0-win-x64.exe"
            },
            {
              "name": "SafeWorld-0.1.0.apk",
              "content_type": "application/vnd.android.package-archive",
              "state": "uploaded",
              "size": 20243764,
              "download_count": 12,
              "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-0.1.0.apk"
            },
            {
              "name": "SafeWorld-chrome-0.1.0.zip",
              "content_type": "application/zip",
              "state": "uploaded",
              "size": 11699461,
              "download_count": 0,
              "browser_download_url": "https://github.com/jobayersajal1/project-safe-world/releases/download/v0.1.0/SafeWorld-chrome-0.1.0.zip"
            }
          ],
          "body": "First Android, macOS, and Windows releases."
        }
        """.trimIndent()
    }
}
