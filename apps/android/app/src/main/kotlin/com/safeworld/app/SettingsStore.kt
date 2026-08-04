package com.safeworld.app

import android.content.Context
import com.safeworld.core.AppGroups.AppGroup
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
     * Key sets for the user's enabled subscriptions, supplied by
     * [SubscriptionStore].
     *
     * Held here rather than read from that store on demand because [blocklists] is
     * consulted once per DNS query, and the merge must not be redone on the hot
     * path. Pushed in via [setSubscriptionSets], mirroring how [setRemoteDomains]
     * already works.
     *
     * **Declared above [blocklists] on purpose.** Kotlin runs property
     * initializers in declaration order, and `blocklists` calls
     * [mergeBlocklists], which reads this. Declared after, it is still null at
     * that point and the app dies in `SettingsStore.<init>` before the first
     * frame — which is exactly what happened.
     */
    @Volatile
    private var subscriptionSets: Map<CategoryId, DomainSet> = emptyMap()

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
            val withRemote =
                if (fetched.isEmpty()) bundled
                else UnionDomainSet(bundled, HashedDomainSet(fetched.toSet()))

            // Subscriptions the user turned on are a third layer, for the same
            // reason remote deltas are a second one: the bundled filter is
            // immutable. Nothing else changes — `Matcher.decide` still sees one
            // `DomainSet` per category and does not know or care how many sources
            // are behind it.
            val subscribed = subscriptionSets[id]
            if (subscribed == null) withRemote else UnionDomainSet(withRemote, subscribed)
        }

    private val _blocklistRevision = MutableStateFlow(0)

    /**
     * Bumped whenever [blocklists] is rebuilt.
     *
     * The home screen's headline comes from [blockedDomainCount], which sums
     * `blocklists` — a plain field, not a flow, because the DNS hot path reads it
     * per query. So Compose has nothing to observe when it changes. Re-emitting
     * `_settings` would not work either: `MutableStateFlow` drops a value equal to
     * the current one, and the settings are unchanged. This is the signal to key
     * the count on.
     */
    val blocklistRevision: StateFlow<Int> = _blocklistRevision.asStateFlow()

    /** Rebuilds [blocklists] so a subscription change applies to the next query. */
    fun setSubscriptionSets(sets: Map<CategoryId, DomainSet>) {
        subscriptionSets = sets
        blocklists = mergeBlocklists(_remoteDomains.value)
        _blocklistRevision.value += 1
    }

    // MARK: App blocking
    //
    // Kept out of `Settings` for the same reason as the PIN: that type is the
    // cross-platform shape, and Android is the only platform that can block an
    // app rather than a domain. iOS has no equivalent outside MDM, and the
    // desktop resolvers see addresses, not processes.

    private val _appBlockRevision = MutableStateFlow(0)

    /**
     * Bumped whenever the app-blocking selection changes.
     *
     * Same reason [blocklistRevision] exists: these live in `SharedPreferences`
     * rather than in `_settings`, so Compose has nothing to recompose on when
     * they change.
     */
    val appBlockRevision: StateFlow<Int> = _appBlockRevision.asStateFlow()

    /**
     * Which app groups are switched on.
     *
     * **Set alongside the category with the same name**, by the one switch that
     * owns both — see `AppGroups.AppGroup`. Still stored separately rather than
     * derived from `Settings.categories`, because this is what puts the device
     * on the full tunnel: `TunnelMode.perAppSwitchesOn` reads these, so the
     * expensive path stays keyed on an explicit fact that survives whatever the
     * UI does with it.
     */
    fun blockedAppGroups(): Set<AppGroup> =
        prefs.getStringSet(KEY_BLOCKED_APP_GROUPS, emptySet())
            .orEmpty()
            .mapNotNullTo(mutableSetOf()) { AppGroup.fromId(it) }

    fun setAppGroupBlocked(group: AppGroup, on: Boolean) {
        val next = blockedAppGroups().toMutableSet()
        if (on) next.add(group) else next.remove(group)
        prefs.edit().putStringSet(KEY_BLOCKED_APP_GROUPS, next.mapTo(mutableSetOf()) { it.id })
            .apply()
        _appBlockRevision.value += 1
    }

    /** Packages the user picked by hand, beyond the built-in catalogue. */
    fun blockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()

    fun setBlockedPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, packages).apply()
        _appBlockRevision.value += 1
    }

    /**
     * Whether the user has been shown what routing every packet through the app
     * costs. Asked once, before the first time it happens.
     */
    val fullTunnelAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_FULL_TUNNEL_ACK, false)

    fun acknowledgeFullTunnel() {
        prefs.edit().putBoolean(KEY_FULL_TUNNEL_ACK, true).apply()
    }

    // MARK: Mutating

    fun update(mutate: (Settings) -> Settings) {
        val next = enforceMandatoryCategories(mutate(_settings.value))
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
    }

    // MARK: First run

    /**
     * How far through the setup wizard the user has got.
     *
     * Persisted rather than held in composition because **choosing a language
     * recreates the activity** (see `LocaleHelper` — the locale is applied in
     * `attachBaseContext`, which only runs on create). A step held in `remember`
     * would reset to the first question the moment anyone picked Bengali.
     */
    private val _onboardingStep = MutableStateFlow(prefs.getInt(KEY_ONBOARDING_STEP, 0))
    val onboardingStep: StateFlow<Int> = _onboardingStep.asStateFlow()

    fun setOnboardingStep(step: Int) {
        prefs.edit().putInt(KEY_ONBOARDING_STEP, step).apply()
        _onboardingStep.value = step
    }

    /**
     * Whether setup has been finished.
     *
     * The default is what carries installs that predate the wizard: they have a
     * PIN and have never written a step, so they are treated as done rather than
     * marched back through setup for decisions they already made.
     *
     * **The step key is the half of that test which matters.** Keying only on
     * "has a PIN" looked equivalent and was not: choosing a PIN is the second
     * question, so someone who set one and then closed the app came back with
     * setup considered finished and never saw the remaining questions — silently
     * skipping the blocking they installed this for.
     */
    private val _onboardingComplete = MutableStateFlow(
        prefs.getBoolean(
            KEY_ONBOARDING_DONE,
            loadPinRecord() != null && !prefs.contains(KEY_ONBOARDING_STEP),
        ),
    )
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    fun completeOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        _onboardingComplete.value = true
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

    // MARK: Theme
    //
    // Kept beside the language for the same reason: which theme this phone
    // shows has nothing to do with what gets blocked, so it stays out of the
    // cross-platform `Settings` shape.

    /**
     * Whether to show the dark theme. **Off by default, and the system is not
     * consulted.**
     *
     * Following the phone sounds thoughtful and reads badly here: two people
     * opening the same app would see different things with no way to know
     * which, and a parent checking a child's phone would find an app that does
     * not look like the one on theirs. Light is what it is; dark is what you
     * ask for, and having asked, it stays asked.
     */
    private val _darkTheme = MutableStateFlow(prefs.getBoolean(KEY_DARK_THEME, false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_THEME, dark).apply()
        _darkTheme.value = dark
    }

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
        _blocklistRevision.value += 1
    }

    companion object {
        /** Also read directly by [LocaleHelper], before this store exists. */
        const val PREFS_NAME = "safe_world"
        const val KEY_LANGUAGE = "language"

        private const val KEY_DARK_THEME = "darkTheme"
        private const val KEY_ONBOARDING_STEP = "onboardingStep"
        private const val KEY_ONBOARDING_DONE = "onboardingComplete"

        private const val KEY_SETTINGS = "settings"
        private const val KEY_STATS = "stats"
        private const val KEY_REMOTE_DOMAINS = "remoteDomains"
        private const val KEY_BLOCKED_APP_GROUPS = "blockedAppGroups"
        private const val KEY_BLOCKED_PACKAGES = "blockedPackages"
        private const val KEY_FULL_TUNNEL_ACK = "fullTunnelAcknowledged"
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
