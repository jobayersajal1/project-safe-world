package com.safeworld.app

import android.content.Context
import com.safeworld.core.CategoryId
import com.safeworld.core.DomainHasher
import com.safeworld.core.DomainSet
import com.safeworld.core.FeedParser
import com.safeworld.core.LongKeyDomainSet
import com.safeworld.core.Subscriptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The blocklists Safe World is not allowed to ship, fetched by the user's own
 * device.
 *
 * See `Subscriptions` in `:core` for why this is legal where bundling is not: we
 * distribute a URL and a licence notice, and the device fetches the file from the
 * publisher. **The data must never pass through anything of ours** — not the
 * update host, not a crash report, not a backup we control — because that would
 * make us the redistributor and put us back where we started.
 *
 * Stored as 64-bit digests, never as domains. That is not primarily a privacy
 * measure here (the file came from a public URL); it is what makes 929,000
 * entries cost 7.4 MB instead of ~110 MB. See [LongKeyDomainSet].
 */
class SubscriptionStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** One `<id>.keys` file per subscription. Never in shared or backed-up storage. */
    private val dir = File(context.applicationContext.filesDir, "subscriptions").apply { mkdirs() }

    private val _enabled = MutableStateFlow(loadEnabled())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    private val _status = MutableStateFlow(loadStatus())
    /** Per-subscription fetch state, for the UI. Keyed by subscription id. */
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()

    /**
     * @param domainCount how many domains the last successful fetch produced.
     * @param fetchedAt wall-clock ms of that fetch, 0 if never.
     * @param etag so an unchanged feed costs a 304 instead of nine megabytes.
     * @param failed set when the last attempt failed, so the row can say so
     *   without pretending the subscription is working.
     */
    data class Status(
        val domainCount: Int = 0,
        val fetchedAt: Long = 0,
        val etag: String? = null,
        val failed: Boolean = false,
        val inProgress: Boolean = false,
    )

    /**
     * The loaded key sets, one per subscription id. Held in memory because the
     * VPN service consults them on every DNS query; only enabled ones are loaded.
     */
    @Volatile
    private var sets: Map<String, LongKeyDomainSet> = emptyMap()

    init {
        // Loading from disk is a few MB of sequential read; done eagerly so the
        // first DNS query after a restart is already filtered rather than racing
        // the load.
        sets = _enabled.value.mapNotNull { id ->
            readKeys(id)?.let { id to LongKeyDomainSet.ofKeys(it) }
        }.toMap()
    }

    /**
     * Enabled subscriptions collapsed into one set per category, in the shape
     * [SettingsStore.mergeBlocklists] wants. Two adult feeds become one
     * [LongKeyDomainSet] rather than two lookups on the DNS hot path.
     */
    fun setsByCategory(): Map<CategoryId, DomainSet> {
        val byCategory = mutableMapOf<CategoryId, MutableList<LongKeyDomainSet>>()
        for (id in _enabled.value) {
            val subscription = Subscriptions.byId(id) ?: continue
            val set = sets[id] ?: continue
            byCategory.getOrPut(subscription.category) { mutableListOf() }.add(set)
        }
        return byCategory.mapValues { (_, list) ->
            if (list.size == 1) {
                list[0]
            } else {
                // Merge-and-sort once here rather than paying an extra binary
                // search per query forever.
                val merged = LongArray(list.sumOf { it.size })
                var at = 0
                for (set in list) {
                    val keys = set.toKeyArray()
                    keys.copyInto(merged, at)
                    at += keys.size
                }
                LongKeyDomainSet.ofKeys(merged)
            }
        }
    }

    val totalDomainCount: Int
        get() = _enabled.value.sumOf { sets[it]?.size ?: 0 }

    // MARK: Enable / disable

    /**
     * Turning one off drops its keys immediately, rather than leaving a 7 MB file
     * for a list that is no longer blocking anything.
     */
    fun setEnabled(id: String, on: Boolean) {
        if (Subscriptions.byId(id) == null) return
        val next = _enabled.value.toMutableSet()
        if (on) next.add(id) else next.remove(id)
        _enabled.value = next
        prefs.edit().putStringSet(KEY_ENABLED, next).apply()

        if (!on) {
            File(dir, "$id.keys").delete()
            sets = sets - id
            _status.value = _status.value - id
            persistStatus()
        }
    }

    fun isEnabled(id: String): Boolean = id in _enabled.value

    // MARK: Onboarding offer

    private val _offered = MutableStateFlow(prefs.getBoolean(KEY_OFFERED, false))

    /**
     * Whether the one-time setup offer has been answered.
     *
     * These lists more than double what the app blocks, and a toggle buried under
     * "want to block more?" is a toggle most people never find — so the choice is
     * put in front of them once, during setup, and never again.
     *
     * Deliberately **not** default-on. Enabling it downloads several megabytes
     * from a third party and accepts that party's terms; doing either without
     * asking would be both legally weaker and rude on a metered connection. It is
     * recorded as answered whichever way they answer, so declining is respected
     * rather than re-asked at every launch.
     *
     * Offered *after* the PIN and recovery code exist, not before. These feeds
     * belong to the two categories with no off switch, so as soon as one is on,
     * turning it off costs the PIN — and there should be no window where the
     * strongest protection in the app can be undone with one tap.
     */
    val offered: StateFlow<Boolean> = _offered.asStateFlow()

    fun markOffered() {
        _offered.value = true
        prefs.edit().putBoolean(KEY_OFFERED, true).apply()
    }

    // MARK: Fetch

    /**
     * Downloads and stores one subscription. Returns the domain count, or null on
     * any failure.
     *
     * Failure leaves whatever was already stored in place: a flaky network should
     * cost an update, never the protection the user already had.
     */
    suspend fun refresh(id: String, force: Boolean = false): Int? = withContext(Dispatchers.IO) {
        val subscription = Subscriptions.byId(id) ?: return@withContext null

        val previous = _status.value[id] ?: Status()
        setStatus(id, previous.copy(inProgress = true, failed = false))

        try {
            val connection = (URL(subscription.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                // These feeds compress to roughly a fifth of their size, and the
                // user is paying for the bytes.
                setRequestProperty("Accept-Encoding", "gzip")
                // An unchanged feed costs a 304 and no body at all. hagezi
                // rebuilds daily, so most checks should hit this.
                if (!force) previous.etag?.let { setRequestProperty("If-None-Match", it) }
            }

            try {
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    // Still current. Record the check so it isn't retried on every
                    // launch, and keep the stored keys.
                    setStatus(
                        id,
                        previous.copy(fetchedAt = System.currentTimeMillis(), inProgress = false),
                    )
                    return@withContext previous.domainCount
                }
                if (code !in 200..299) throw IOException("HTTP $code")

                val raw = connection.inputStream
                val stream = if (
                    connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
                ) {
                    GZIPInputStream(raw)
                } else {
                    raw
                }

                val keys = stream.bufferedReader().use { reader ->
                    parseToKeys(reader.lineSequence(), subscription.format)
                }

                if (keys.isEmpty()) {
                    // A feed that parses to nothing means the syntax changed. Keep
                    // the previous keys rather than replacing protection with an
                    // empty set that would look like a healthy subscription.
                    throw IOException("parsed no domains — upstream format may have changed")
                }

                writeKeys(id, keys)
                sets = sets + (id to LongKeyDomainSet.ofKeys(keys))
                setStatus(
                    id,
                    Status(
                        domainCount = keys.size,
                        fetchedAt = System.currentTimeMillis(),
                        etag = connection.getHeaderField("ETag"),
                        failed = false,
                        inProgress = false,
                    ),
                )
                keys.size
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            setStatus(id, previous.copy(failed = true, inProgress = false))
            null
        }
    }

    /**
     * Refreshes every enabled subscription whose last fetch is older than
     * [REFRESH_INTERVAL_HOURS].
     */
    suspend fun refreshDue() {
        val cutoff = System.currentTimeMillis() - REFRESH_INTERVAL_HOURS * 3_600_000
        for (id in _enabled.value) {
            if ((_status.value[id]?.fetchedAt ?: 0) < cutoff) refresh(id)
        }
    }

    /**
     * Parses straight to sorted digests without ever holding the domain list.
     *
     * The oisd feed is 452,000 entries; as a `List<String>` that is ~35 MB of
     * transient garbage on a phone, on top of the response body. Hashing each line
     * as it arrives keeps the peak to the growable `LongArray`.
     */
    private fun parseToKeys(lines: Sequence<String>, format: FeedParser.Format): LongArray {
        var keys = LongArray(1 shl 14)
        var count = 0

        for (line in lines) {
            val domain = FeedParser.parseLine(line, format) ?: continue
            val key = DomainHasher.key(domain)
            if (key == 0L) continue
            if (count == keys.size) keys = keys.copyOf(keys.size * 2)
            keys[count++] = key
        }

        val exact = keys.copyOf(count)
        exact.sort()

        // De-duplicate in place now that it is sorted; feeds do repeat entries,
        // and a duplicate costs 8 bytes forever.
        var unique = 0
        for (i in exact.indices) {
            if (i == 0 || exact[i] != exact[i - 1]) exact[unique++] = exact[i]
        }
        return exact.copyOf(unique)
    }

    // MARK: Persistence

    /**
     * Raw big-endian longs behind a magic number and version, so a file from a
     * future format is discarded rather than read as garbage keys.
     */
    private fun writeKeys(id: String, keys: LongArray) {
        val file = File(dir, "$id.keys")
        val temp = File(dir, "$id.keys.tmp")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(keys.size)
            for (key in keys) out.writeLong(key)
        }
        // Rename last, so an interrupted write leaves the old file intact rather
        // than a truncated one.
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("could not replace $file")
        }
    }

    private fun readKeys(id: String): LongArray? {
        val file = File(dir, "$id.keys")
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != FORMAT_VERSION) return null
                val count = input.readInt()
                if (count < 0 || count > MAX_KEYS) return null
                LongArray(count) { input.readLong() }
            }
        }.getOrNull()
    }

    private fun loadEnabled(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, emptySet())
            .orEmpty()
            // Drops ids from a build that offered a subscription this one doesn't.
            .filter { Subscriptions.byId(it) != null }
            .toSet()

    private fun loadStatus(): Map<String, Status> {
        val result = mutableMapOf<String, Status>()
        for (subscription in Subscriptions.ALL) {
            val fetchedAt = prefs.getLong("${subscription.id}$KEY_FETCHED_SUFFIX", 0)
            if (fetchedAt == 0L) continue
            result[subscription.id] = Status(
                domainCount = prefs.getInt("${subscription.id}$KEY_COUNT_SUFFIX", 0),
                fetchedAt = fetchedAt,
                etag = prefs.getString("${subscription.id}$KEY_ETAG_SUFFIX", null),
            )
        }
        return result
    }

    private fun setStatus(id: String, status: Status) {
        _status.value = _status.value + (id to status)
        persistStatus()
    }

    private fun persistStatus() {
        prefs.edit().apply {
            for (subscription in Subscriptions.ALL) {
                val status = _status.value[subscription.id]
                if (status == null) {
                    remove("${subscription.id}$KEY_FETCHED_SUFFIX")
                    remove("${subscription.id}$KEY_COUNT_SUFFIX")
                    remove("${subscription.id}$KEY_ETAG_SUFFIX")
                } else {
                    putLong("${subscription.id}$KEY_FETCHED_SUFFIX", status.fetchedAt)
                    putInt("${subscription.id}$KEY_COUNT_SUFFIX", status.domainCount)
                    putString("${subscription.id}$KEY_ETAG_SUFFIX", status.etag)
                }
            }
        }.apply()
    }

    companion object {
        const val PREFS_NAME = "safe_world_subscriptions"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FETCHED_SUFFIX = ".fetchedAt"
        private const val KEY_COUNT_SUFFIX = ".domainCount"
        private const val KEY_ETAG_SUFFIX = ".etag"
        private const val KEY_OFFERED = "offered"

        private const val MAGIC = 0x53575355 // "SWSU"
        private const val FORMAT_VERSION = 1

        /** Sanity bound on a stored file, ~2M domains. Guards a corrupt length. */
        private const val MAX_KEYS = 2_000_000

        private const val TIMEOUT_MS = 20_000

        /** These are multi-megabyte bodies over mobile networks. */
        private const val READ_TIMEOUT_MS = 120_000

        const val REFRESH_INTERVAL_HOURS = 24L

        @Volatile
        private var instance: SubscriptionStore? = null

        fun get(context: Context): SubscriptionStore =
            instance ?: synchronized(this) {
                instance ?: SubscriptionStore(context).also { instance = it }
            }
    }
}
