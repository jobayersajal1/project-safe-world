package com.safeworld.app.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.safeworld.app.SettingsStore
import com.safeworld.core.AppGroups

/**
 * The set of Linux UIDs whose traffic must be dropped, resolved from the package names the user's
 * choices add up to.
 *
 * **Why UIDs and not package names.** By the time a packet reaches us there is no package name left
 * — the kernel knows only which UID opened the socket. Resolving once and matching on integers is
 * also what makes this affordable: under a full tunnel [contains] is consulted for every packet on
 * the device, so it is a binary search over a sorted `IntArray` rather than a `Set<String>` lookup
 * plus a `PackageManager` call.
 *
 * **A UID is not always one app.** Packages sharing an `android:sharedUserId` share a UID, so
 * blocking one blocks its siblings. That mechanism is deprecated and rare among the apps in
 * [AppGroups], and there is nothing finer to match on — the kernel does not record which of them
 * opened the socket — so this is a limit to know about rather than a bug to fix.
 */
class BlockedApps private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    /**
     * Sorted so [contains] can binary-search it. Swapped wholesale on refresh rather than mutated,
     * so the packet path never sees a half-built array and needs no lock.
     */
    @Volatile
    private var uids: IntArray = IntArray(0)

    /** Package names currently blocked, for the UI's "and N apps" count. */
    @Volatile
    var packages: Set<String> = emptySet()
        private set

    /** How many of the chosen apps are actually installed — the only number worth showing. */
    val installedCount: Int get() = uids.size

    /** Whether anything at all is blocked by app. Lets callers skip the work entirely. */
    val isEmpty: Boolean get() = uids.isEmpty()

    fun contains(uid: Int): Boolean = uids.binarySearch(uid) >= 0

    /**
     * Re-reads the user's choices and resolves them to UIDs.
     *
     * Called when settings change and when a package is installed or removed — **a reinstall gives
     * an app a new UID**, so a cache that is never invalidated would keep dropping traffic for a UID
     * nobody owns while the app itself runs freely.
     */
    fun refresh(store: SettingsStore = SettingsStore.get(appContext)) {
        val chosen = AppGroups.blockedPackages(
            enabledGroups = store.blockedAppGroups(),
            userPicked = store.blockedPackages(),
        )
        packages = chosen

        val resolved = ArrayList<Int>(chosen.size)
        for (name in chosen) {
            val uid = uidOf(name) ?: continue
            resolved.add(uid)
        }

        // Distinct because shared-UID packages collapse to the same integer, and a duplicate would
        // break the binary search's precondition.
        uids = resolved.distinct().sorted().toIntArray()
        Log.i(TAG, "blocking ${uids.size} uids from ${chosen.size} chosen packages")
    }

    /** Null when the app is not installed, which is the ordinary case for most of the catalogue. */
    private fun uidOf(packageName: String): Int? = try {
        packageManager.getApplicationInfo(packageName, 0).uid
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        private const val TAG = "SafeWorldBlockedApps"

        @Volatile
        private var instance: BlockedApps? = null

        fun get(context: Context): BlockedApps =
            instance ?: synchronized(this) {
                instance ?: BlockedApps(context).also {
                    it.refresh()
                    instance = it
                }
            }
    }
}
