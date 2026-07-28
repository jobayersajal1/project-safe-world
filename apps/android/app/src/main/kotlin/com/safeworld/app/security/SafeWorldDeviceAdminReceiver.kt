package com.safeworld.app.security

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.safeworld.app.R

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

    companion object {
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
                dpm.removeActiveAdmin(componentName(context))
            }
        }
    }
}
