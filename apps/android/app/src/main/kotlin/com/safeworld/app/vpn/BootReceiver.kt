package com.safeworld.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.safeworld.app.SettingsStore

/**
 * Brings protection back after a reboot or an app update.
 *
 * **Why this is necessary.** Until it existed, the only thing that restarted the tunnel was
 * `MainActivity.restoreProtectionIfLeftOn()` — so after every reboot the device was unprotected
 * until somebody opened the app. On a phone where Safe World is installed and then deliberately
 * never opened again, which is the normal end state for a self-control tool, that meant protection
 * effectively ended at the first restart. Silently: the app still said it was on.
 *
 * `ACTION_MY_PACKAGE_REPLACED` is handled for the same reason — an app update stops our service and
 * nothing else would start it again.
 *
 * **Starting a foreground service from here is allowed.** Android 12 forbids background
 * foreground-service starts in general, but `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are on the
 * documented exemption list, which is exactly why those two actions and no others are registered.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            // Anything else is not one of the exempt broadcasts, so starting a
            // foreground service on it would throw.
            else -> return
        }

        val store = SettingsStore.get(context)
        val enabled = store.settings.value.enabled
        val alreadyRunning = SafeWorldVpnService.running.value
        val consentMissing = VpnService.prepare(context) != null

        // Logged unconditionally, including the no-op cases. Every reason this can decline to act is
        // invisible from the outside — the symptom is identical to the receiver never firing at all,
        // which is exactly the ambiguity that cost time the first time this was tested.
        Log.i(
            TAG,
            "${intent.action}: enabled=$enabled running=$alreadyRunning consentMissing=$consentMissing",
        )

        if (!enabled) return
        if (alreadyRunning) return

        // Consent not granted, or another VPN holds the slot. Only an Activity can ask, so nothing
        // useful can happen here — the interruption banner and notification already cover it, and
        // calling start() anyway would throw on boot.
        if (consentMissing) return

        runCatching { SafeWorldVpnService.start(context) }
            .onFailure { Log.w(TAG, "could not restart protection after ${intent.action}", it) }
    }

    private companion object {
        const val TAG = "SafeWorldBoot"
    }
}
