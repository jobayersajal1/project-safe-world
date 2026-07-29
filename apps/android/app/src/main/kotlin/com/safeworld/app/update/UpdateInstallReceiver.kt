package com.safeworld.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Receives the outcome of a [PackageInstaller] session started by
 * [UpdateManager].
 *
 * Declared in the manifest rather than registered at runtime: the confirmation
 * dialog can come back after the user has left the app, and a receiver tied to
 * a live Activity would simply miss it, stranding a downloaded update with no
 * way to finish.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android will not install silently: it hands back an Intent
                // for its own confirmation screen, which we have to launch.
                val confirm = IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_INTENT,
                    Intent::class.java,
                )
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    results.tryEmit(Result.Failed(null))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // Rarely observed: a successful install replaces this app, so
                // the process is usually killed before this arrives.
                results.tryEmit(Result.Succeeded)
            }

            else -> {
                // STATUS_FAILURE_ABORTED lands here too — that's the user
                // declining the confirmation dialog, not an error to shout
                // about, so the message is left for the UI to interpret.
                results.tryEmit(
                    Result.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                )
            }
        }
    }

    sealed interface Result {
        data object Succeeded : Result
        data class Failed(val message: String?) : Result
    }

    companion object {
        /**
         * Session outcomes, observed by [UpdateManager] so the UI can stop
         * showing "installing" once the system is done with it.
         *
         * A SharedFlow of events rather than a StateFlow of the latest result,
         * because two identical outcomes in a row are two real events: a
         * StateFlow would treat the second "user declined" as no change and
         * emit nothing, leaving the UI stuck on "installing" forever.
         */
        val results = MutableSharedFlow<Result>(extraBufferCapacity = 1)
    }
}
