package com.safeworld.app.update

import com.safeworld.core.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published release newer than what's installed. */
data class AvailableUpdate(
    /** As published, e.g. "0.2.0" — the leading "v" of the tag is already stripped. */
    val version: String,
    /** Direct link to the APK, or null when the release has no APK attached. */
    val apkUrl: String?,
    /** Bytes, for showing the download size before committing to it. 0 when unknown. */
    val sizeBytes: Long,
    /** The release's own page, used when there is nothing to install directly. */
    val pageUrl: String,
)

/** The subset of GitHub's release JSON this app reads. */
@Serializable
internal data class GhRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
internal data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

/**
 * Asks [UpdateConfig.LATEST_RELEASE_URL] whether anything newer than the
 * running build has been published.
 *
 * Uses HttpURLConnection rather than pulling in an HTTP client, matching
 * `RemoteUpdateService` — this is one plain GET of a small JSON document.
 */
object UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }
    private const val TIMEOUT_MS = 15_000

    /**
     * Returns the newer release, or null when the running build is current.
     *
     * Throws on network or parse failure so the caller can say *why* a manual
     * check produced nothing — silently returning null would be
     * indistinguishable from "you're up to date", which is the one wrong
     * answer that matters here.
     */
    suspend fun check(currentVersion: String): AvailableUpdate? = withContext(Dispatchers.IO) {
        select(parse(fetchLatest()), currentVersion)
    }

    internal fun parse(body: String): GhRelease = json.decodeFromString(body)

    /**
     * Decides whether [release] is worth offering to someone on
     * [currentVersion], and which asset they'd install.
     *
     * Split out from the fetch so the wire format and the asset matching can be
     * tested against a real captured payload without a network call.
     */
    internal fun select(release: GhRelease, currentVersion: String): AvailableUpdate? {
        // A draft isn't public and a pre-release isn't being offered to
        // everyone; neither should be pushed at users automatically.
        if (release.draft || release.prerelease) return null

        val published = release.tagName.trim().removePrefix("v").removePrefix("V")
        if (published.isEmpty()) return null
        if (!Version.isNewer(published, currentVersion)) return null

        // The same release carries the .dmg, .exe, and Chrome .zip, so this
        // must pick out the APK rather than taking the first asset.
        val apk = release.assets.firstOrNull {
            it.name.endsWith(UpdateConfig.ASSET_EXTENSION, ignoreCase = true)
        }
        return AvailableUpdate(
            version = published,
            apkUrl = apk?.browserDownloadUrl,
            sizeBytes = apk?.size ?: 0,
            pageUrl = release.htmlUrl.ifEmpty { UpdateConfig.RELEASES_PAGE_URL },
        )
    }

    private fun fetchLatest(): String {
        val connection = (URL(UpdateConfig.LATEST_RELEASE_URL).openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/vnd.github+json")
                // GitHub rejects API requests that don't identify themselves.
                setRequestProperty("User-Agent", "SafeWorld-Android")
            }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
