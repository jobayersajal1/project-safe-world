package com.safeworld.app

import android.content.Context
import com.safeworld.core.Blocklists
import com.safeworld.core.Categories
import com.safeworld.core.CategoryId
import com.safeworld.core.DailyStats
import com.safeworld.core.DomainSet
import com.safeworld.core.EmptyDomainSet
import com.safeworld.core.FuseDomainSet
import com.safeworld.core.HashedDomainSet
import com.safeworld.core.UnionDomainSet
import com.safeworld.core.Matcher
import com.safeworld.core.Settings
import com.safeworld.app.security.PinAttempt
import com.safeworld.app.security.PinHasher
import com.safeworld.app.security.PinLockout
import com.safeworld.app.security.RecoveryCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SharedPreferences-backed store for [Settings] and [DailyStats], the Android
 * analogue of `storage.ts` in the Chrome extension and `SettingsStore.swift`
 * on iOS.
 *
 * A process-wide singleton because the VPN service and the UI live in the same
 * process and must see the same settings — the service reads [blocklists] and
 * [settings] on every DNS query, so a settings change takes effect immediately
 * without restarting the tunnel.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _stats = MutableStateFlow(loadStats())
    val stats: StateFlow<DailyStats> = _stats.asStateFlow()

    private val _remoteDomains = MutableStateFlow(loadRemoteDomains())
    val remoteDomains: StateFlow<Map<CategoryId, List<String>>> = _remoteDomains.asStateFlow()

    /**
     * Bundled hashes unioned with any fetched remote ones, in the shape
     * [Matcher.decide] wants. Recomputed only when remote domains change, since
     * it is read once per DNS query on the tunnel's hot path.
     */
    @Volatile
    var blocklists: Map<CategoryId, DomainSet> = mergeBlocklists(_remoteDomains.value)
        private set

    // MARK: Loading

    private fun loadSettings(): Settings {
        val raw = prefs.getString(KEY_SETTINGS, null)
            ?: return enforceMandatoryCategories(Settings.defaults())
        val stored = runCatching { json.decodeFromString<Settings>(raw) }.getOrNull()
        return enforceMandatoryCategories(Settings.withDefaults(stored))
    }

    /**
     * Android policy: installing this app is the decision to block scam,
     * gambling, and adult content, so those three have no off switch and are
     * forced on here — a value persisted by an older build (or a hand-edited
     * backup) can't leave one off with no way to turn it back on.
     *
     * The opt-in categories are left exactly as stored. They're a preference,
     * not the promise, and forcing them either way would be overriding the
     * user's own answer to "want to block more?".
     */
    private fun enforceMandatoryCategories(settings: Settings): Settings {
        val next = settings.categories.toMutableMap()
        for (category in Categories.mandatory) next[category.id] = true
        return settings.copy(categories = next)
    }

    /**
     * How many known domains are currently being blocked — the headline figure
     * on the home screen.
     *
     * Counts only the enabled categories, so the number moves when the user
     * turns an opt-in list on and the claim stays true. Bundled and fetched
     * entries are already unioned in [blocklists], so nothing is double-counted
     * across the two sources; the categories themselves may overlap slightly,
     * which makes this a small over-count rather than an inflated one.
     */
    fun blockedDomainCount(settings: Settings = _settings.value): Int =
        Categories.all
            .filter { settings.categories[it.id] == true }
            .sumOf { blocklists[it.id]?.size ?: 0 }

    private fun loadStats(): DailyStats {
        val raw = prefs.getString(KEY_STATS, null) ?: return DailyStats(todayKey(), 0)
        val stored = runCatching { json.decodeFromString<DailyStats>(raw) }.getOrNull()
        return if (stored?.date == todayKey()) stored else DailyStats(todayKey(), 0)
    }

    private fun loadRemoteDomains(): Map<CategoryId, List<String>> {
        val raw = prefs.getString(KEY_REMOTE_DOMAINS, null) ?: return emptyMap()
        val stored = runCatching { json.decodeFromString<Map<String, List<String>>>(raw) }
            .getOrNull() ?: return emptyMap()
        return stored.mapNotNull { (key, value) -> CategoryId.fromId(key)?.let { it to value } }.toMap()
    }

    /**
     * The bundled filter beside the fetched digests, rather than one merged set.
     *
     * Both describe the same thing — the bundled filter's keys and the published
     * deltas come from the same salted digest — but a fuse filter is immutable
     * once built, so remote additions sit alongside it instead of going in. See
     * [UnionDomainSet].
     */
    private fun mergeBlocklists(remote: Map<CategoryId, List<String>>): Map<CategoryId, DomainSet> =
        CategoryId.entries.associateWith { id ->
            val bundled = Blocklists.filter(id)?.let { FuseDomainSet(it) } ?: EmptyDomainSet
            val fetched = remote[id].orEmpty()
            if (fetched.isEmpty()) bundled else UnionDomainSet(bundled, HashedDomainSet(fetched.toSet()))
        }

    // MARK: Mutating

    fun update(mutate: (Settings) -> Settings) {
        val next = enforceMandatoryCategories(mutate(_settings.value))
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
    }

    // MARK: PIN
    //
    // Kept out of `Settings` on purpose: that type is the cross-platform shape
    // shared with packages/core and iOS, and a PIN is an Android-only concern.

    private val _hasPin = MutableStateFlow(loadPinRecord() != null)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    private fun loadPinRecord(): PinHasher.Record? {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return null
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return null
        val iterations = prefs.getInt(KEY_PIN_ITERATIONS, PinHasher.DEFAULT_ITERATIONS)
        return PinHasher.Record(salt, hash, iterations)
    }

    fun setPin(pin: String) {
        val record = PinHasher.hash(pin)
        prefs.edit()
            .putString(KEY_PIN_SALT, record.salt)
            .putString(KEY_PIN_HASH, record.hash)
            .putInt(KEY_PIN_ITERATIONS, record.iterations)
            .apply()
        _hasPin.value = true
        // A newly chosen PIN starts with a clean slate.
        persistLockout(PinLockout.onSuccess())
    }

    // MARK: Recovery code
    //
    // The only way back in after a forgotten PIN. Stored hashed with the same
    // PBKDF2 as the PIN, so the app can check a code without being able to
    // reproduce one — which is also why "show me my code again" isn't offered
    // and re-issuing is the only option.

    private val _hasRecoveryCode = MutableStateFlow(loadRecoveryRecord() != null)
    val hasRecoveryCode: StateFlow<Boolean> = _hasRecoveryCode.asStateFlow()

    private fun loadRecoveryRecord(): PinHasher.Record? {
        val salt = prefs.getString(KEY_RECOVERY_SALT, null) ?: return null
        val hash = prefs.getString(KEY_RECOVERY_HASH, null) ?: return null
        val iterations = prefs.getInt(KEY_RECOVERY_ITERATIONS, PinHasher.DEFAULT_ITERATIONS)
        return PinHasher.Record(salt, hash, iterations)
    }

    /**
     * Generates a new code, stores its hash, and returns the plaintext — the
     * **only** time it exists in readable form. Any previously issued code stops
     * working, which is what makes a used code single-use.
     */
    fun issueRecoveryCode(): String {
        val code = RecoveryCode.generate()
        val record = PinHasher.hash(RecoveryCode.normalize(code))
        prefs.edit()
            .putString(KEY_RECOVERY_SALT, record.salt)
            .putString(KEY_RECOVERY_HASH, record.hash)
            .putInt(KEY_RECOVERY_ITERATIONS, record.iterations)
            .apply()
        _hasRecoveryCode.value = true
        return code
    }

    /**
     * True if [code] is the current recovery code.
     *
     * Deliberately **not** subject to [PinLockout]: the cooldown exists to stop
     * someone guessing a 4-digit PIN, and being locked out is the usual reason
     * to reach for this. A 100-bit code doesn't need the same protection, and
     * gating it behind the cooldown would disable the escape hatch exactly when
     * it's needed.
     */
    fun verifyRecoveryCode(code: String): Boolean {
        val record = loadRecoveryRecord() ?: return false
        return PinHasher.verify(RecoveryCode.normalize(code), record)
    }

    /** Verifies [code] and replaces the PIN, clearing the attempt cooldown. */
    @Synchronized
    fun resetPinWithRecoveryCode(code: String, newPin: String): Boolean {
        if (!verifyRecoveryCode(code)) return false
        // setPin also clears the lockout, so the new PIN works immediately.
        setPin(newPin)
        return true
    }

    private val _lockout = MutableStateFlow(loadLockout())

    /** Cooldown state, exposed so the UI can count down without polling storage. */
    val pinLockout: StateFlow<PinLockout.State> = _lockout.asStateFlow()

    private fun loadLockout(): PinLockout.State = PinLockout.expireIfElapsed(
        PinLockout.State(
            failures = prefs.getInt(KEY_PIN_FAILURES, 0),
            lockedUntil = prefs.getLong(KEY_PIN_LOCKED_UNTIL, 0),
            lockedAt = prefs.getLong(KEY_PIN_LOCKED_AT, 0),
        ),
        System.currentTimeMillis(),
    )

    private fun persistLockout(state: PinLockout.State) {
        prefs.edit()
            .putInt(KEY_PIN_FAILURES, state.failures)
            .putLong(KEY_PIN_LOCKED_UNTIL, state.lockedUntil)
            .putLong(KEY_PIN_LOCKED_AT, state.lockedAt)
            .apply()
        _lockout.value = state
    }

    /**
     * Checks [pin] and applies the attempt limit. The only PIN entry point, so
     * no caller can accidentally skip the cooldown.
     */
    @Synchronized
    fun verifyPin(pin: String): PinAttempt {
        val now = System.currentTimeMillis()

        val current = PinLockout.expireIfElapsed(_lockout.value, now)
        if (current != _lockout.value) persistLockout(current)

        val remaining = PinLockout.remaining(current, now)
        if (remaining > 0) return PinAttempt.LockedOut(remaining)

        val record = loadPinRecord()
        if (record != null && PinHasher.verify(pin, record)) {
            persistLockout(PinLockout.onSuccess())
            return PinAttempt.Success
        }

        val next = PinLockout.onFailure(current, now)
        persistLockout(next)
        val lockedFor = PinLockout.remaining(next, now)
        return if (lockedFor > 0) {
            PinAttempt.LockedOut(lockedFor)
        } else {
            PinAttempt.Wrong(PinLockout.attemptsRemaining(next))
        }
    }

    // MARK: Protection interruptions
    //
    // Set when the tunnel goes down without the user turning it off in-app —
    // another VPN taking over, or consent being revoked in system settings.
    // Surfaced in the UI until acknowledged with the PIN, so a silent takeover
    // can't leave the user believing they're still protected.

    private val _protectionInterrupted =
        MutableStateFlow(prefs.getBoolean(KEY_INTERRUPTED, false))
    val protectionInterrupted: StateFlow<Boolean> = _protectionInterrupted.asStateFlow()

    /**
     * Whether the tunnel has ever come up on this install.
     *
     * `enabled` defaults to true, so without this a brand-new install looks
     * exactly like a takeover — meant to be on, not running, no VPN consent —
     * and greets the user with a red "protection was interrupted" banner before
     * they have turned anything on. "Interrupted" presupposes it was running.
     */
    val protectionEverStarted: Boolean
        get() = prefs.getBoolean(KEY_EVER_STARTED, false)

    fun markProtectionStarted() {
        if (protectionEverStarted) return
        prefs.edit().putBoolean(KEY_EVER_STARTED, true).apply()
    }

    fun flagProtectionInterrupted() {
        prefs.edit().putBoolean(KEY_INTERRUPTED, true).apply()
        _protectionInterrupted.value = true
    }

    fun clearProtectionInterrupted() {
        prefs.edit().putBoolean(KEY_INTERRUPTED, false).apply()
        _protectionInterrupted.value = false
    }

    /** Called from the VPN worker thread for every sinkholed lookup. */
    @Synchronized
    fun incrementBlockedToday() {
        val today = todayKey()
        val current = _stats.value
        val next = if (current.date == today) {
            current.copy(blocked = current.blocked + 1)
        } else {
            DailyStats(today, 1)
        }
        _stats.value = next
        prefs.edit().putString(KEY_STATS, json.encodeToString(next)).apply()
    }

    // MARK: Language
    //
    // Kept out of `Settings` for the same reason as the PIN: that type is the
    // cross-platform shape, and which language this phone's UI is in has
    // nothing to do with what gets blocked.

    private val _language = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "").orEmpty())

    /** BCP-47 tag, or "" for "follow the system". See [LocaleHelper]. */
    val language: StateFlow<String> = _language.asStateFlow()

    fun setLanguage(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
        _language.value = tag
    }

    // MARK: Remote update identity
    //
    // Which published payload was last applied. Lets a daily fetch of an
    // unchanged list cost a download and nothing else, instead of rebuilding
    // the merged blocklist every time.

    /** Id of the last applied payload, or "" if none has been applied. */
    val lastAppliedUpdateId: String
        get() = prefs.getString(KEY_LAST_UPDATE_ID, "").orEmpty()

    fun setLastAppliedUpdateId(id: String) {
        prefs.edit().putString(KEY_LAST_UPDATE_ID, id).apply()
    }

    fun setRemoteDomains(domains: Map<String, List<String>>) {
        prefs.edit().putString(KEY_REMOTE_DOMAINS, json.encodeToString(domains)).apply()
        val byCategory = domains
            .mapNotNull { (key, value) -> CategoryId.fromId(key)?.let { it to value } }
            .toMap()
        _remoteDomains.value = byCategory
        blocklists = mergeBlocklists(byCategory)
    }

    companion object {
        /** Also read directly by [LocaleHelper], before this store exists. */
        const val PREFS_NAME = "safe_world"
        const val KEY_LANGUAGE = "language"

        private const val KEY_SETTINGS = "settings"
        private const val KEY_STATS = "stats"
        private const val KEY_REMOTE_DOMAINS = "remoteDomains"
        private const val KEY_LAST_UPDATE_ID = "lastAppliedUpdateId"
        private const val KEY_PIN_SALT = "pinSalt"
        private const val KEY_PIN_HASH = "pinHash"
        private const val KEY_PIN_ITERATIONS = "pinIterations"
        private const val KEY_INTERRUPTED = "protectionInterrupted"
        private const val KEY_EVER_STARTED = "protectionEverStarted"
        private const val KEY_PIN_FAILURES = "pinFailures"
        private const val KEY_PIN_LOCKED_UNTIL = "pinLockedUntil"
        private const val KEY_PIN_LOCKED_AT = "pinLockedAt"
        private const val KEY_RECOVERY_SALT = "recoverySalt"
        private const val KEY_RECOVERY_HASH = "recoveryHash"
        private const val KEY_RECOVERY_ITERATIONS = "recoveryIterations"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }

        /** Local date key, e.g. "2026-07-26" — the same shape the other platforms use. */
        fun todayKey(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
