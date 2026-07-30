package com.safeworld.app.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.safeworld.app.R
import com.safeworld.app.SettingsStore

/**
 * Registered as a device admin purely for the uninstall friction it buys:
 * Android refuses to uninstall an app while it is an active device admin, so
 * removing Safe World means first deactivating admin, which the app gates
 * behind the PIN.
 *
 * This is friction, **not** a real block. The user can still deactivate admin
 * from Settings ▸ Security ▸ Device admin apps, boot to safe mode, or wipe the
 * app's data. A genuine block needs Device Owner, which requires provisioning
 * from a factory-reset device. The point is to make quitting a deliberate,
 * multi-step act rather than a moment's impulse — which is the whole premise
 * of a self-control app.
 *
 * It requests **no policies** (see res/xml/device_admin.xml): it never locks,
 * wipes, or reads anything. Admin registration alone is what blocks uninstall.
 */
class SafeWorldDeviceAdminReceiver : DeviceAdminReceiver() {

    /** Shown on the system's confirmation screen when deactivation is attempted. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    /**
     * Admin was actually removed — which, because the in-app path is PIN-gated, most likely means
     * it was done from Settings ▸ Security ▸ Device admin apps.
     *
     * We cannot prevent that: the system's own screen is not ours to gate, and only a Device Owner
     * (which needs provisioning from a factory-reset device) can set `setUninstallBlocked`. What we
     * *can* do is refuse to let it happen quietly. The same interruption flag the VPN uses on
     * takeover is raised here, so the next time the app opens there is a banner that costs the PIN
     * to dismiss, and a notification fires in case it never does.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        // Our own PIN-gated path also lands here, and that removal is not a surprise to anyone.
        // Warning about it would be a false alarm — and false alarms are how a warning that matters
        // gets learned as noise.
        if (expectedRemoval) {
            expectedRemoval = false
            return
        }
        SettingsStore.get(context).flagProtectionInterrupted()
        notifyRemoved(context)
    }

    private fun notifyRemoved(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.alert_channel_description) },
        )

        val text = context.getString(R.string.alert_admin_removed_text)
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.alert_admin_removed_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "protection_alerts"
        private const val NOTIFICATION_ID = 3

        /**
         * Set while we are removing admin ourselves, so [onDisabled] can tell an authorized removal
         * from someone doing it in Settings.
         *
         * In memory rather than persisted on purpose: `removeActiveAdmin` and the resulting
         * `onDisabled` happen back to back in this same process, and a persisted flag that somehow
         * outlived the removal would suppress a warning that was real. Losing it to process death
         * fails the safe way — a spurious warning, not a silent removal.
         */
        @Volatile
        private var expectedRemoval = false

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, SafeWorldDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            return dpm.isAdminActive(componentName(context))
        }

        /** Intent for the system screen that asks the user to grant admin. */
        fun enableIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_explanation),
                )

        /** Drops admin, re-allowing uninstall. Callers must gate this behind the PIN. */
        fun deactivate(context: Context) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (dpm.isAdminActive(componentName(context))) {
                // Marked before the call, because onDisabled arrives during it.
                expectedRemoval = true
                dpm.removeActiveAdmin(componentName(context))
            }
        }
    }
}
