package com.safeworld.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import com.safeworld.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the update section is currently showing. */
sealed interface UpdateState {
    /** Nothing checked yet this session. */
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** Checked, and this build is the newest published one. */
    data object UpToDate : UpdateState

    data class Available(val update: AvailableUpdate) : UpdateState

    /** [fraction] is 0..1, or null while the server withholds a content length. */
    data class Downloading(val update: AvailableUpdate, val fraction: Float?) : UpdateState

    /** Handed to the system installer; the confirmation dialog is the user's to answer. */
    data class Installing(val update: AvailableUpdate) : UpdateState

    data class Failed(@StringRes val messageRes: Int) : UpdateState
}

/**
 * Checks for, downloads, and hands off a newer APK.
 *
 * A process-wide singleton, like [com.safeworld.app.SettingsStore], rather than
 * something the Activity owns. Activity-scoped was the obvious first shape and
 * it was wrong: a rotation destroys the Activity, which cancelled an in-flight
 * download mid-stream and reset the state to Idle — while `lastCheckedAt`
 * survived as a static, so the automatic re-check was throttled out for another
 * 24 hours and a "version 0.2.0 is available" the user had just been shown
 * silently became a bare "Check for updates" button. Outliving the Activity
 * fixes both halves, and lets the throttle be an ordinary instance field.
 *
 * **The downloaded APK must be signed with the same key as the installed one**
 * or Android refuses the update outright. That makes this path work only for
 * builds installed from a signed release — a locally-built debug install can
 * check for updates but can never apply one, which is the expected and correct
 * behaviour rather than a bug to work around.
 */
class UpdateManager private constructor(
    context: Context,
    /** The running build's version, e.g. "0.1.0". */
    private val currentVersion: String,
) {
    private val context = context.applicationContext

    /**
     * Survives the Activity, so a download keeps running across a rotation.
     * SupervisorJob so one failed check can't tear down the scope for good.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        // A finished session tells us the system is done with the APK, whether
        // the user accepted it or backed out. Either way "Installing…" is no
        // longer true, so fall back to offering it again.
        scope.launch {
            UpdateInstallReceiver.results.collect { result ->
                val current = _state.value
                if (result is UpdateInstallReceiver.Result.Failed && current is UpdateState.Installing) {
                    _state.value = UpdateState.Available(current.update)
                }
            }
        }
    }

    /**
     * Looks for a newer release. [force] runs even when one ran recently — the
     * throttle exists so that opening Settings repeatedly doesn't spend the
     * unauthenticated rate limit, but a button the user pressed should always
     * do something visible.
     */
    fun check(force: Boolean = false) {
        if (job?.isActive == true) return
        if (!force && !isDue()) return

        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val update = UpdateChecker.check(currentVersion)
                lastCheckedAt = System.currentTimeMillis()
                if (update == null) UpdateState.UpToDate else UpdateState.Available(update)
            } catch (_: Exception) {
                // Network, HTTP, and malformed-JSON all mean the same thing to
                // the user: we couldn't ask right now.
                UpdateState.Failed(R.string.update_error_check)
            }
        }
    }

    /**
     * Downloads the APK, then hands it to the system installer.
     *
     * A release with no APK attached (the .dmg and .exe are in the same
     * release) can still be reached on the web, so that case sends the user to
     * the release page instead of failing.
     */
    fun download(update: AvailableUpdate) {
        if (job?.isActive == true) return

        val url = update.apkUrl
        if (url == null) {
            openReleasePage(update)
            return
        }

        // Android will not let an app install an APK until the user has
        // allowed it for this specific app. Asking first is far clearer than
        // downloading 3 MB and then being refused.
        if (!canInstallPackages()) {
            requestInstallPermission()
            return
        }

        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(update, null)
                val apk = withContext(Dispatchers.IO) {
                    downloadTo(url, update) { fraction ->
                        _state.value = UpdateState.Downloading(update, fraction)
                    }
                }
                _state.value = UpdateState.Installing(update)
                withContext(Dispatchers.IO) { install(apk) }
            } catch (_: Exception) {
                _state.value = UpdateState.Failed(R.string.update_error_download)
            }
        }
    }

    /** For the "no APK attached" case, and as the manual escape hatch. */
    fun openReleasePage(update: AvailableUpdate) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // region Install permission

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Sends the user to the system screen that grants this app the right to
     * install packages. There is no in-app dialog for it — the setting lives
     * in Settings and only the user can turn it on.
     */
    fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) _state.value = UpdateState.Failed(R.string.update_error_permission)
    }

    // endregion

    // region Download

    private fun downloadTo(
        url: String,
        update: AvailableUpdate,
        onProgress: (Float?) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One file per version, replaced wholesale, so a half-finished download
        // from a previous attempt can never be handed to the installer.
        val target = File(dir, "SafeWorld-${update.version}.apk")
        if (target.exists()) target.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "SafeWorld-Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            // The release metadata's size is the more reliable of the two: the
            // asset download redirects, and the final response doesn't always
            // carry a length.
            val total = update.sizeBytes.takeIf { it > 0 }
                ?: connection.contentLengthLong.takeIf { it > 0 }

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastReported = 0f
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read

                        if (total == null) continue
                        val fraction = (copied.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        // Reporting every 8 KB buffer would recompose the
                        // progress bar a few thousand times over a 20 MB APK;
                        // 1% steps are past what the eye resolves on a short bar.
                        if (fraction - lastReported >= 0.01f || fraction >= 1f) {
                            lastReported = fraction
                            onProgress(fraction)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    // endregion

    // region Install

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("safeworld", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }

            val intent = Intent(context, UpdateInstallReceiver::class.java)
            // MUTABLE because the installer fills in the result extras. An
            // immutable PendingIntent here means the callback arrives empty and
            // the confirmation dialog is never shown.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    // endregion

    private fun isDue(): Boolean =
        System.currentTimeMillis() - lastCheckedAt >= UpdateConfig.CHECK_INTERVAL_HOURS * 3_600_000

    /**
     * In memory rather than persisted: the throttle only needs to stop a burst
     * of checks within one run of the app, and a fresh launch checking once is
     * exactly what we want. An ordinary field rather than a static, now that
     * the instance outlives the Activity — as a static it kept throttling a
     * rotated Activity whose state had just been reset to Idle.
     */
    private var lastCheckedAt = 0L

    companion object {
        private const val TIMEOUT_MS = 20_000

        @Volatile
        private var instance: UpdateManager? = null

        /** The process-wide instance, matching `SettingsStore.get`. */
        fun get(context: Context, currentVersion: String): UpdateManager =
            instance ?: synchronized(this) {
                instance ?: UpdateManager(context, currentVersion).also { instance = it }
            }
    }
}
