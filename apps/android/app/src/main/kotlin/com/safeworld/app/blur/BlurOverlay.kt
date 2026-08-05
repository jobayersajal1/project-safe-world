package com.safeworld.app.blur

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.safeworld.core.BlurRevealMode

/**
 * The windows that cover people on screen.
 *
 * A system overlay cannot blur what is beneath it — `RenderEffect` applies to a
 * view's own content, and nothing in the public API lets one window read
 * another's pixels. So this paints opaque panels over the regions
 * [PersonScanner] returned. "I cannot see them" is what was asked for, and a
 * panel delivers that more completely than a blur radius, which is reversible
 * by anyone who squints.
 *
 * **One window per panel, and every one of them touchable. Both halves are
 * forced, and getting either wrong silently breaks the feature.**
 *
 * Android clamps the alpha of an untrusted `TYPE_APPLICATION_OVERLAY` window
 * that lets touches pass through — `FLAG_NOT_TOUCHABLE` — down to
 * `maximum_obscuring_opacity_for_touch`, which defaults to **0.8**. It is a
 * tapjacking defence: obscure the screen completely, or pass touches to the app
 * underneath, but not both. The clamp is silent. `LayoutParams.alpha = 1f` is
 * accepted and then overwritten, and `dumpsys window` is the only place it
 * shows:
 *
 *     mAttrs={... ty=APPLICATION_OVERLAY fmt=TRANSLUCENT alpha=0.8
 *     mShownAlpha=0.8 mAlpha=0.8 mLastAlpha=0.8
 *
 * At 0.8 a face is dimmed and perfectly readable — this feature failing while
 * appearing to work. No drawing beats it: the fill was already an opaque colour
 * and `PorterDuff.Mode.SRC` changed nothing, because the alpha is applied to
 * the whole surface at composition, not to the pixels.
 *
 * So the windows are touchable, and to keep the rest of the screen usable each
 * is sized to its own panel and carries `FLAG_NOT_TOUCH_MODAL`, which sends
 * every touch outside its bounds to whatever is behind. The cost is that a tap
 * landing on a covered person is swallowed — the right way round, since the
 * alternative was letting it through and seeing who it landed on.
 */
class BlurOverlay(private val context: Context) {

    private val windows = context.getSystemService(WindowManager::class.java)

    /** Window operations are main-thread only, and the scan loop is not on it. */
    private val main = Handler(Looper.getMainLooper())

    private val panels = mutableListOf<PanelWindow>()
    private var visible = false

    fun show() {
        main.post { visible = true }
    }

    fun hide() {
        main.post {
            visible = false
            for (p in panels) p.remove()
            panels.clear()
        }
    }

    /**
     * Replace what is covered.
     *
     * [regions] are in captured-frame coordinates; [scale] maps them to screen
     * pixels, because the frame is captured smaller than the display to keep
     * detection affordable.
     */
    fun update(regions: List<Rect>, scale: Float, revealMode: BlurRevealMode) {
        // Deliberately not merged into fewer, larger rectangles. Merging
        // overlapping person boxes was worth doing while the panels were
        // translucent, where overlaps double-darkened and showed a seam — but
        // opaque panels of one colour overlap invisibly, and in a group photo
        // every box touches its neighbour, so merging produced a single panel
        // over the whole group. That covers the men when the answer was women,
        // which throws away the only thing the classifier is for.
        val wanted = regions.map { r ->
            Rect(
                (r.left * scale).toInt(),
                (r.top * scale).toInt(),
                (r.right * scale).toInt(),
                (r.bottom * scale).toInt(),
            ).also { it.inset(-PADDING_PX, -PADDING_PX) }
        }.take(MAX_PANELS)

        main.post {
            if (!visible) return@post

            // Reuse the windows already up rather than tearing them down each
            // pass: adding and removing windows several times a second flickers
            // and churns a surface per panel per frame.
            while (panels.size > wanted.size) panels.removeAt(panels.lastIndex).remove()
            while (panels.size < wanted.size) panels.add(PanelWindow(context, windows))
            for ((panel, rect) in panels.zip(wanted)) panel.place(rect)
        }
    }


    /** One opaque panel, in its own window. */
    private class PanelWindow(context: Context, private val windows: WindowManager) {

        private val view = object : View(context) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(255, 24, 27, 36)
                style = Paint.Style.FILL
            }
            private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(90, 100, 125)
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }

            override fun onDraw(canvas: Canvas) {
                val r = RectF(1f, 1f, width - 1f, height - 1f)
                canvas.drawRoundRect(r, CORNER, CORNER, fill)
                canvas.drawRoundRect(r, CORNER, CORNER, edge)
            }

            @Suppress("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                // Consumed, and deliberately does nothing. The window has to be
                // touchable to be allowed full opacity; swallowing a tap on a
                // covered person is what that costs.
                return true
            }
        }

        private val layout = WindowManager.LayoutParams(
            0, 0, 0, 0,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // No FLAG_NOT_TOUCHABLE — see the note on the class, it is exactly
            // what triggers the 0.8 clamp. NOT_TOUCH_MODAL is what keeps the
            // rest of the screen working: touches outside this window's bounds
            // go to whatever is behind it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        private var added = false

        fun place(rect: Rect) {
            layout.x = rect.left
            layout.y = rect.top
            layout.width = rect.width().coerceAtLeast(1)
            layout.height = rect.height().coerceAtLeast(1)

            if (!added) {
                runCatching { windows.addView(view, layout) }.onSuccess { added = true }
            } else {
                runCatching { windows.updateViewLayout(view, layout) }
            }
            view.invalidate()
        }

        fun remove() {
            if (!added) return
            runCatching { windows.removeView(view) }
            added = false
        }

        private companion object {
            const val CORNER = 18f
        }
    }

    private companion object {
        /**
         * Detection boxes stop at the subject's edge, and a panel is redrawn a
         * few times a second while they move. Without slack, someone moving
         * spills out of their own panel between frames.
         */
        const val PADDING_PX = 16

        /**
         * Each panel is a window with its own surface. A scene with more people
         * than this has already been merged into large panels, and more
         * surfaces buy nothing.
         */
        const val MAX_PANELS = 8
    }
}
