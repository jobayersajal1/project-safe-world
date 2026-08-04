package com.safeworld.app.blur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.safeworld.app.LocaleHelper
import com.safeworld.app.MainActivity
import com.safeworld.app.R
import com.safeworld.app.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures the screen, finds people on it, and covers them.
 *
 * **This is the honest cost of covering apps rather than only the browser.**
 * Android will not let one app read another's pixels except through
 * `MediaProjection`, and that means a capture, a detection pass and a redraw
 * between a person appearing and being covered. The lag is inherent to the
 * mechanism, not a tuning problem — the UI says so rather than implying
 * otherwise.
 *
 * Two things the platform imposes and no code here can remove:
 *  - A recording indicator is shown for as long as this runs. It cannot be
 *    suppressed, and trying would be the wrong thing to want.
 *  - From Android 14, consent is required **every session**, so this cannot
 *    start itself on boot. The user restarts it from the app.
 */
class BlurService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var store: SettingsStore
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: BlurOverlay? = null
    private var scanner: PersonScanner? = null

    /** Captured-frame → screen scale, for placing the panels. */
    private var scale = 1f

    override fun attachBaseContext(base: Context) {
        // Without this the notification lands in a different language from the
        // app that started it — the same trap the VPN service already documents.
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        store = SettingsStore.get(this)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            // Consent is per-session and cannot be replayed from storage, so
            // there is nothing to recover here — stop rather than sit running
            // and covering nothing, which would look identical to working.
            Log.w(TAG, "started without projection consent; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // The foreground notification has to be posted *before* the projection is
        // taken, or the system kills the service for capturing without one.
        //
        // Wrapped because this genuinely throws in a reachable case: when the
        // system restarts a service that died while a start was pending, the
        // consent that made `mediaProjection` a legal foreground type is long
        // gone, and `startForeground` answers with a SecurityException that
        // crashes the process a second time. There is nothing to recover — the
        // token cannot be reissued without the user — so it stops quietly.
        try {
            startForegroundWithType()
        } catch (e: SecurityException) {
            Log.w(TAG, "no live capture consent; stopping instead of restarting", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return if (begin(resultCode, data)) START_NOT_STICKY else { stopSelf(); START_NOT_STICKY }
    }

    private fun startForegroundWithType() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.blur_channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.blur_channel_description) },
            )
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.blur_notification_title))
            .setContentText(getString(R.string.blur_notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun begin(resultCode: Int, data: Intent): Boolean {
        val projections = getSystemService(MediaProjectionManager::class.java)
        val media = runCatching { projections.getMediaProjection(resultCode, data) }.getOrNull()
        if (media == null) {
            Log.w(TAG, "projection refused")
            return false
        }
        projection = media

        media.registerCallback(
            object : MediaProjection.Callback() {
                // The user can revoke capture from the system UI at any time.
                // When they do, the panels must come down with it rather than
                // freeze over a screen nobody is scanning any more.
                override fun onStop() = stopSelf()
            },
            null,
        )

        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
        }

        // Captured well below screen resolution. The detectors resize to their
        // own small inputs anyway, so the extra pixels cost memory and copy time
        // and buy nothing — and this runs several times a second.
        scale = metrics.widthPixels.toFloat() / CAPTURE_WIDTH
        val height = (metrics.heightPixels / scale).toInt().coerceAtLeast(1)

        reader = ImageReader.newInstance(CAPTURE_WIDTH, height, android.graphics.PixelFormat.RGBA_8888, 2)
        display = media.createVirtualDisplay(
            "SafeWorldBlur",
            CAPTURE_WIDTH, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null,
        )

        overlay = BlurOverlay(this).also { it.show() }

        _running.value = true
        scope.launch { loop() }
        return true
    }

    /**
     * Scan, cover, wait.
     *
     * Paced rather than driven by every available frame: the screen produces
     * frames far faster than a detection pass completes, and consuming them all
     * would keep a core pinned while the panels updated no sooner.
     */
    private suspend fun loop() {
        // Built here rather than in `begin`: loading three models takes long
        // enough to be an ANR on the main thread, and a failure to load must
        // stop the service rather than take the process down with it.
        val scanner = try {
            PersonScanner(this).also { this.scanner = it }
        } catch (e: Throwable) {
            Log.e(TAG, "detection models unavailable; stopping", e)
            stopSelf()
            return
        }

        var bitmap: Bitmap? = null
        var held: List<Rect> = emptyList()
        while (scope.isActive) {
            val blur = store.blur.value
            if (!blur.enabled) {
                // Turned off from the UI while running. Nothing left to cover.
                stopSelf()
                return
            }

            // A VirtualDisplay only produces a frame when the screen *changes*.
            // On a still page `acquireLatestImage` returns null indefinitely, so
            // waiting for one meant a person on a motionless screen was never
            // covered — exactly the case of someone stopping to look. When there
            // is no new frame the previous one is re-scanned instead.
            val image = runCatching { reader?.acquireLatestImage() }.getOrNull()
            if (image != null || bitmap != null) {
                try {
                    if (image != null) {
                        val plane = image.planes[0]
                        val rowStride = plane.rowStride / plane.pixelStride
                        if (bitmap == null || bitmap.width != rowStride || bitmap.height != image.height) {
                            bitmap?.recycle()
                            bitmap = Bitmap.createBitmap(rowStride, image.height, Bitmap.Config.ARGB_8888)
                        }
                        bitmap.copyPixelsFromBuffer(plane.buffer)
                    }
                    val frame = bitmap ?: continue

                    val found: List<Rect> = scanner.scan(frame, blur.target)
                    held = hold(frame, held, found)
                    overlay?.update(held, scale, blur.revealMode)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "frame failed", e)
                } finally {
                    image?.close()
                }
            }

            delay(FRAME_INTERVAL_MS)
        }
        bitmap?.recycle()
    }

    /**
     * Keep covering a region for as long as our own panel is what the capture
     * shows there.
     *
     * **The capture includes our overlay**, and that closes a loop: cover
     * somebody opaquely, and the next frame has no person there to find, so the
     * region is dropped, so they are visible again, so they are found again.
     * Left alone it oscillates several times a second and the feature is worse
     * than useless — it shows the thing it was installed to hide, rhythmically.
     *
     * `FLAG_SECURE` on the overlay would exclude it from capture and solve this
     * outright, but it also blanks every screenshot and screen recording on the
     * device while it is up, which is not ours to take away.
     *
     * So a held region is released only when the pixels under it stop looking
     * like a panel. A panel is flat by construction, so "still a panel" is just
     * "still nearly uniform" — when the user scrolls or switches app, real
     * content appears, variance jumps, and the region is re-evaluated on its
     * merits.
     */
    private fun hold(frame: Bitmap, previous: List<Rect>, found: List<Rect>): List<Rect> {
        val kept = previous.filter { it.stillCovered(frame) && found.none { f -> Rect.intersects(f, it) } }
        return found + kept
    }

    /**
     * True if this region of the frame is flat enough to be our own panel
     * rather than content.
     *
     * Sampled on a coarse grid: this runs per region per frame, and the
     * question is only "flat or not", which a few dozen points answer as well
     * as a hundred thousand.
     */
    private fun Rect.stillCovered(frame: Bitmap): Boolean {
        val stepX = (width() / SAMPLES).coerceAtLeast(1)
        val stepY = (height() / SAMPLES).coerceAtLeast(1)
        var min = 255
        var max = 0
        var seen = 0
        var y = top.coerceAtLeast(0)
        while (y < bottom && y < frame.height) {
            var x = left.coerceAtLeast(0)
            while (x < right && x < frame.width) {
                val p = frame.getPixel(x, y)
                val luma = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (luma < min) min = luma
                if (luma > max) max = luma
                seen++
                x += stepX
            }
            y += stepY
        }
        return seen > 0 && (max - min) <= FLAT_RANGE
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        overlay?.hide()
        runCatching { scanner?.close() }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        super.onDestroy()
    }

    companion object {
        /**
         * Whether capture is actually running.
         *
         * The home screen needs this because "enabled" and "running" genuinely
         * come apart on Android 14+: consent is per-session, so a setting that
         * survives a reboot outlives the permission that made it work. A row
         * reading "on" over a service that is not covering anything is the one
         * thing this product must never show, so it reads both.
         */
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private const val TAG = "BlurService"
        private const val CHANNEL = "safeworld-blur"
        private const val NOTIFICATION_ID = 0x5AFE
        const val ACTION_STOP = "com.safeworld.app.blur.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        /** Long edge of the captured frame, in pixels. */
        private const val CAPTURE_WIDTH = 480

        /**
         * ~5 scans a second. Faster shortens the window in which someone is
         * visible but costs battery continuously; this is the point where the
         * lag stops being the limiting factor and the detector does.
         */
        private const val FRAME_INTERVAL_MS = 200L

        /** Grid resolution per axis for the flatness test in [stillCovered]. */
        private const val SAMPLES = 8

        /**
         * Luma spread below which a region counts as one of our own panels.
         *
         * Generous: the capture is scaled and JPEG-ish artefacts move flat
         * colour around by a few levels. Erring high keeps a region covered a
         * little too long, which is the harmless direction.
         */
        private const val FLAT_RANGE = 24

        fun stop(context: Context) {
            context.startService(Intent(context, BlurService::class.java).setAction(ACTION_STOP))
        }
    }
}
