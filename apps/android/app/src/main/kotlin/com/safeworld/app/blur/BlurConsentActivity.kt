package com.safeworld.app.blur

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.safeworld.app.R
import com.safeworld.app.SettingsStore

/**
 * Collects the two consents blurring needs, in the order they can be collected.
 *
 * **Neither can be taken during setup and kept.** The overlay permission lives
 * on a system settings screen the user has to be sent to, and screen-capture
 * consent is, from Android 14, granted *per session* — so it must be asked for
 * every time the service starts, not once at install. The wizard therefore only
 * records the intent; this is where it is actually paid for.
 *
 * Kept apart from `MainActivity` so that turning blurring on from the home
 * screen and finishing setup both land in the same place, and so the dialog
 * appears over whatever the user was looking at instead of pushing them back
 * into the app.
 */
class BlurConsentActivity : ComponentActivity() {

    private val projections by lazy { getSystemService(MediaProjectionManager::class.java) }

    private val captureConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startService(
                Intent(this, BlurService::class.java)
                    .putExtra(BlurService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(BlurService.EXTRA_RESULT_DATA, result.data),
            )
        } else {
            // Refused. The setting is turned back off rather than left on over a
            // service that is not running — a switch reading "on" while nothing
            // is covered is the one state this product must never show.
            SettingsStore.get(this).updateBlur { it.copy(enabled = false) }
            toast(R.string.blur_consent_declined)
        }
        finish()
    }

    private val overlayConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The overlay screen reports nothing useful in its result, so the
        // permission is re-checked rather than inferred from it.
        if (canDrawOverlays()) {
            requestCapture()
        } else {
            SettingsStore.get(this).updateBlur { it.copy(enabled = false) }
            toast(R.string.blur_overlay_declined)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (canDrawOverlays()) requestCapture() else requestOverlay()
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlay() {
        overlayConsent.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestCapture() {
        captureConsent.launch(projections.createScreenCaptureIntent())
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, getString(resId), Toast.LENGTH_LONG).show()
    }

    companion object {
        /**
         * Start blurring, asking for whatever consent is still missing.
         *
         * Callers must have set `enabled` first: a refusal at either step turns
         * it back off, and that only reads correctly if it was on to begin with.
         */
        fun start(context: Context) {
            context.startActivity(
                Intent(context, BlurConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
