package com.safeworld.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import com.safeworld.app.ui.theme.SafeWorldTheme
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.safeworld.app.security.SafeWorldDeviceAdminReceiver
import com.safeworld.app.ui.SafeWorldApp
import com.safeworld.app.update.UpdateManager
import com.safeworld.app.vpn.SafeWorldVpnService
import com.safeworld.app.work.RemoteUpdateWorker
import com.safeworld.app.work.SubscriptionWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var store: SettingsStore
    private lateinit var subscriptions: SubscriptionStore

    /**
     * Process-wide, not owned here: an Activity-scoped one lost its state and
     * cancelled any in-flight download on rotation. See [UpdateManager].
     */
    private lateinit var updateManager: UpdateManager

    /**
     * The running build's `versionName`, shown in Settings and compared against
     * the newest release. `by lazy` because this is a binder call, and it is
     * read from the `setContent` lambda — as a getter it re-queried the package
     * manager on every root recomposition.
     */
    private val installedVersion: String by lazy {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    }

    /** Mirrors device-admin state, which only changes via system screens. */
    private var uninstallProtectionActive by mutableStateOf(false)

    /**
     * The system VPN consent dialog. Android requires it before the first
     * `establish()`, and again if the user later revokes it — declining is a
     * legitimate answer, so we roll the master switch back off rather than
     * leaving the UI claiming protection is on.
     */
    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            SafeWorldVpnService.start(this)
        } else {
            store.update { it.copy(enabled = false) }
        }
    }

    /** The system's "activate device admin?" screen. */
    private val deviceAdmin = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshUninstallProtectionState() }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The tunnel runs either way; without it the status notification is silent. */ }

    /** Applies the chosen UI language before any resource is resolved. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore.get(this)
        subscriptions = SubscriptionStore.get(this)
        updateManager = UpdateManager.get(this, installedVersion)

        // Off the main thread: this is megabytes of read plus sorting close to a
        // million longs, which ANRs in onCreate. Until it lands the app blocks the
        // bundled lists as normal, so nothing waits on it.
        lifecycleScope.launch {
            subscriptions.load()
            store.setSubscriptionSets(subscriptions.setsByCategory())
        }

        lifecycleScope.launch { RemoteUpdateService.refreshIfDue(store) }
        RemoteUpdateWorker.schedule(this)
        SubscriptionWorker.schedule(this)
        restoreProtectionIfLeftOn()
        observeLanguageChanges()

        setContent {
            // Null means follow the system, which is the default — see `SettingsStore.darkTheme`.
            val darkPreference by store.darkTheme.collectAsStateWithLifecycle()
            SafeWorldTheme(darkTheme = darkPreference ?: isSystemInDarkTheme()) {
                SafeWorldApp(
                    store = store,
                    subscriptions = subscriptions,
                    onProtectionChange = ::setProtectionEnabled,
                    onSubscriptionsChanged = {
                        // Apply what is already on disk right away, then ask the
                        // worker to fetch anything missing. Turning a subscription
                        // *off* must take effect immediately rather than waiting
                        // for a download that isn't needed.
                        store.setSubscriptionSets(subscriptions.setsByCategory())
                        SubscriptionWorker.runNow(this)
                    },
                    onEnableUninstallProtection = {
                        deviceAdmin.launch(SafeWorldDeviceAdminReceiver.enableIntent(this))
                    },
                    onDisableUninstallProtection = {
                        SafeWorldDeviceAdminReceiver.deactivate(this)
                        refreshUninstallProtectionState()
                    },
                    uninstallProtectionActive = uninstallProtectionActive,
                    updateManager = updateManager,
                    installedVersion = installedVersion,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUninstallProtectionState()
        // Catches a takeover that happened while the app was in the background:
        // onRevoke() may not have run if our process was already gone.
        detectInterruptedProtection()
    }

    /**
     * The language is applied in [attachBaseContext], which only runs when the
     * activity is created — so picking a new one has to recreate it. Skips the
     * value it started with, or every launch would immediately restart itself.
     */
    private fun observeLanguageChanges() {
        val initial = LocaleHelper.storedTag(this)
        lifecycleScope.launch {
            store.language.collect { tag ->
                if (tag != initial) recreate()
            }
        }
    }

    private fun refreshUninstallProtectionState() {
        uninstallProtectionActive = SafeWorldDeviceAdminReceiver.isActive(this)
    }

    /**
     * Bring the tunnel back up if the user left protection on and the process
     * has since been restarted. Only acts when consent is already granted
     * (`prepare()` returns null) — a fresh install shouldn't ambush the user
     * with a system VPN dialog before they've touched anything.
     */
    private fun restoreProtectionIfLeftOn() {
        if (!store.settings.value.enabled) return
        if (SafeWorldVpnService.running.value) return
        if (VpnService.prepare(this) != null) return
        SafeWorldVpnService.start(this)
    }

    /**
     * Protection is meant to be on, but nothing is running and our consent is
     * gone — another VPN displaced us, or it was revoked in system settings.
     * Flag it so the UI says so instead of quietly showing "off".
     */
    private fun detectInterruptedProtection() {
        // A fresh install matches every condition below — `enabled` defaults to
        // true, nothing is running, and consent has never been granted — so
        // without this the first launch opens on a red "protection was
        // interrupted" banner that costs the PIN to dismiss.
        if (!store.protectionEverStarted) return
        if (!store.settings.value.enabled) return
        if (SafeWorldVpnService.running.value) return
        if (VpnService.prepare(this) == null) return
        store.flagProtectionInterrupted()
    }

    /**
     * Turning protection on flips the master switch *and* brings the tunnel up;
     * turning it off tears the tunnel down. Both matter: `enabled` is what
     * `Matcher.decide` checks, and the tunnel is what sees the traffic.
     *
     * Callers are responsible for the PIN gate on the "off" path — see
     * HomeScreen, where only weakening actions request it.
     */
    private fun setProtectionEnabled(enabled: Boolean) {
        store.update { it.copy(enabled = enabled) }

        if (!enabled) {
            SafeWorldVpnService.stop(this)
            return
        }

        // Turning it back on is the user's own doing, so clear any stale
        // interruption warning.
        store.clearProtectionInterrupted()
        requestNotificationPermissionIfNeeded()

        val consent: Intent? = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else SafeWorldVpnService.start(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
