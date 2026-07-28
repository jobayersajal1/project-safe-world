package com.safeworld.app

import com.safeworld.core.RemoteUpdatePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a `RemoteUpdatePayload` from [RemoteConfig.UPDATE_URL], the Android
 * analogue of `runRemoteUpdate` in the Chrome extension's background.ts.
 *
 * Uses HttpURLConnection rather than pulling in an HTTP client: this is one
 * plain GET of a JSON document, once a day.
 */
object RemoteUpdateService {
    private val json = Json { ignoreUnknownKeys = true }
    private const val TIMEOUT_MS = 15_000

    suspend fun fetch(urlString: String): RemoteUpdatePayload = withContext(Dispatchers.IO) {
        require(urlString.isNotEmpty()) { "No remote update URL configured." }

        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            json.decodeFromString<RemoteUpdatePayload>(
                connection.inputStream.bufferedReader().use { it.readText() },
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetches and applies an update if one is configured and the last fetch is
     * older than [RemoteConfig.UPDATE_INTERVAL_HOURS]. Safe to call on every
     * app launch — it no-ops when not due.
     *
     * Deliberately silent: nothing in the UI reports remote update state, so a
     * failure just means we retry the next time it's due. Bundled lists remain
     * the offline baseline; remote updates only ever add domains.
     */
    suspend fun refreshIfDue(store: SettingsStore) {
        if (RemoteConfig.UPDATE_URL.isEmpty()) return
        val dueAt = store.settings.value.lastRemoteUpdate +
            RemoteConfig.UPDATE_INTERVAL_HOURS * 3_600_000
        if (System.currentTimeMillis() < dueAt) return

        runCatching { fetch(RemoteConfig.UPDATE_URL) }.onSuccess { payload ->
            // Mark the check done either way, so an unchanged list doesn't get
            // re-fetched on every launch.
            store.update { it.copy(lastRemoteUpdate = System.currentTimeMillis()) }

            // Already applied this exact set — rewriting identical domains would
            // rebuild the whole merged blocklist for nothing. A payload with no
            // updateId is always applied, which is how older feeds behaved.
            val id = payload.updateId
            if (id != null && id == store.lastAppliedUpdateId) return@onSuccess

            store.setRemoteDomains(payload.domains)
            if (id != null) store.setLastAppliedUpdateId(id)
        }
    }
}
