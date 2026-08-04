package com.safeworld.app.blur

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.safeworld.core.BlurRevealMode

/**
 * The window that covers people on screen.
 *
 * A system overlay cannot blur what is beneath it — `RenderEffect` applies to a
 * view's own content, and nothing in the public API lets one window read the
 * pixels of another. So this paints opaque rounded panels over the regions
 * [PersonScanner] returned. The effect the user asked for is "I cannot see
 * them", and a panel delivers that more completely than a blur radius, which is
 * reversible by anyone who squints.
 *
 * The window is **not touchable**. Every event passes through to the app
 * underneath, so the phone keeps working normally with panels floating over it —
 * the alternative is an overlay that silently eats taps, which reads as the
 * device being broken.
 */
class BlurOverlay(private val context: Context) {

    private val windows = context.getSystemService(WindowManager::class.java)
    private val view = PanelView(context)
    private var added = false

    private val layout = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        // Deliberately *not* FLAG_SECURE.
        //
        // It looks like the right answer — it would keep our panels out of the
        // screen capture and break the feedback loop described in
        // `BlurService.hold` — but a secure window blanks *every* screenshot and
        // screen recording on the device for as long as it is up. Verified: the
        // capture came back solid black. Taking away the user's ability to
        // screenshot anything is too high a price, so the loop is broken in the
        // service instead.
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Stated rather than left to the default: the panels must hide what is
        // under them completely. A translucent cover over a face is not a
        // weaker version of this feature, it is a failure of it.
        alpha = 1f
    }

    fun show() {
        if (added) return
        runCatching { windows.addView(view, layout) }.onSuccess { added = true }
    }

    fun hide() {
        if (!added) return
        runCatching { windows.removeView(view) }
        added = false
    }

    /**
     * Replace what is covered.
     *
     * [regions] are in captured-frame coordinates; [scale] maps them to screen
     * pixels, because the frame is captured smaller than the display to keep
     * detection affordable.
     */
    fun update(regions: List<Rect>, scale: Float, revealMode: BlurRevealMode) {
        view.panels = regions.map { r ->
            Rect(
                (r.left * scale).toInt(),
                (r.top * scale).toInt(),
                (r.right * scale).toInt(),
                (r.bottom * scale).toInt(),
            ).also { it.inset(-PADDING_PX, -PADDING_PX) }
        }
        view.showHint = revealMode == BlurRevealMode.TAP
        view.postInvalidate()
    }

    private class PanelView(context: Context) : View(context) {
        var panels: List<Rect> = emptyList()
        var showHint = false

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Fully opaque, spelled out with the alpha byte rather than left to
            // Color.rgb's implicit 0xFF, because this is the one property that
            // decides whether the feature works.
            color = Color.argb(255, 24, 27, 36)
            style = Paint.Style.FILL
            // SRC, not the default SRC_OVER: *replace* what is underneath
            // instead of compositing over it. Measured on a real screen, the
            // default left the panel around half transparent and faces were
            // still readable through it — which is this feature failing while
            // appearing to work.
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
        }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 100, 125)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        override fun onDraw(canvas: Canvas) {
            for (p in panels) {
                canvas.drawRoundRect(
                    p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat(),
                    CORNER, CORNER, fill,
                )
                canvas.drawRoundRect(
                    p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat(),
                    CORNER, CORNER, edge,
                )
            }
        }

        private companion object {
            const val CORNER = 18f
        }
    }

    private companion object {
        /**
         * Detection boxes stop at the subject's edge, and the panel is redrawn a
         * few times a second while they move. Without slack, a moving person
         * spills out of their own panel between frames.
         */
        const val PADDING_PX = 16
    }
}
