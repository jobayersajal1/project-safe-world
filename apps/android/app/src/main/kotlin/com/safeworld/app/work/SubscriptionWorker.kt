package com.safeworld.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.safeworld.app.SettingsStore
import com.safeworld.app.SubscriptionStore
import java.util.concurrent.TimeUnit

/**
 * Downloads the user's subscribed blocklists in the background.
 *
 * Separate from [RemoteUpdateWorker] because the two differ in every way that
 * matters to scheduling: that one fetches a small delta from our own host, this
 * one fetches up to 18 MB from three third parties.
 *
 * **Unmetered only.** These are multi-megabyte bodies and nobody agreed to spend
 * their mobile data on them. `NetworkType.UNMETERED` means a subscription enabled
 * on cellular simply waits for Wi-Fi rather than quietly costing money — which is
 * why the UI says "over Wi-Fi when possible" instead of promising it is immediate.
 */
class SubscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val subscriptions = SubscriptionStore.get(applicationContext)
        val force = inputData.getBoolean(KEY_FORCE, false)

        if (force) {
            for (id in subscriptions.enabled.value) subscriptions.refresh(id)
        } else {
            subscriptions.refreshDue()
        }

        // Whatever landed has to reach the matcher, or the download was for
        // nothing until the next app start.
        SettingsStore.get(applicationContext)
            .setSubscriptionSets(subscriptions.setsByCategory())

        // `refresh` records its own failure on the subscription and keeps whatever
        // was already stored, so there is nothing here worth retrying: the next
        // periodic run tries again, and the row already says it failed.
        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC = "subscription-refresh"
        private const val UNIQUE_NOW = "subscription-refresh-now"
        private const val KEY_FORCE = "force"

        private val constraints = Constraints.Builder()
            // Not CONNECTED: see the class comment. 18 MB is not something to put
            // on someone's cellular plan without being asked.
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        /**
         * Daily. The feeds rebuild about that often, and each check sends an
         * `If-None-Match`, so an unchanged list costs a 304 and no body.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(
                SubscriptionStore.REFRESH_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP, so opening the app often doesn't reset the interval and
                // starve the job.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Fetches now-ish, for when the user has just turned a subscription on and
         * is watching the row.
         *
         * `REPLACE`, unlike the periodic job: toggling twice should leave one
         * pending download, not two.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SubscriptionWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
