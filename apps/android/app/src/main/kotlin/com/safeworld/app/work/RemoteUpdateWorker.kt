package com.safeworld.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.safeworld.app.RemoteUpdateService
import com.safeworld.app.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * Refreshes the remote blocklist in the background, so the lists keep updating
 * on a device where the app is installed and never opened again — which is the
 * normal case once protection is on.
 *
 * `refreshIfDue` is itself a no-op when a fetch isn't due or no URL is
 * configured, so running this more often than the update interval is harmless.
 */
class RemoteUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        RemoteUpdateService.refreshIfDue(SettingsStore.get(applicationContext))
        // refreshIfDue swallows failures by design (bundled lists are the
        // offline baseline), so there is nothing here worth a retry: the next
        // periodic run will try again.
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "remote-list-refresh"

        /**
         * Schedules the periodic refresh. `KEEP` so re-opening the app doesn't
         * reset the interval and starve the job on a device that's opened often.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RemoteUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
