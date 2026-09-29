package app.keymahub.capture

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import app.keymahub.R
import app.keymahub.core.Hub

/**
 * Covers the phone's screen in black, at the lowest brightness, while another device is
 * controlled. Android turns the screen on for every click and key of an external mouse or
 * keyboard, before any app sees the input, and nothing an app does can stop it: covered, the
 * screen that comes on shows black (next to nothing on an OLED screen) instead of the lock screen
 * or an app. A touch on the phone takes the cover away; with [idleMs] set, it comes back once the
 * phone has not been touched for that long, and otherwise when control moves to another device again.
 *
 * Not focusable, so pointer capture keeps its focus; above the lock screen, as accessibility
 * overlays are.
 */
@SuppressLint("ClickableViewAccessibility") // a black cover: the touch only takes it away
class ScreenCover(
    private val service: AccessibilityService,
    /** How long the phone goes untouched, once uncovered, before it is covered again; null: not again. */
    private val idleMs: () -> Long?,
) {
    private val wm = service.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null
    /** While uncovered by a touch: a window told of every touch on the phone (see [watch]). */
    private var watcher: View? = null
    private val recover = Runnable {
        Hub.log("screen cover: untouched, covered again")
        unwatch()
        cover()
    }

    fun show() = main.post {
        unwatch()
        cover()
    }

    fun hide() = main.post {
        unwatch()
        uncover()
    }

    /** On the main thread. */
    private fun cover() {
        if (view != null) return
        val v = FrameLayout(service).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                TextView(service).apply {
                    text = service.getString(R.string.cover_hint)
                    setTextColor(0xFF3A3A3A.toInt()) // barely there on black
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                ).apply { bottomMargin = (64 * service.resources.displayMetrics.density).toInt() },
            )
            setOnTouchListener { _, e ->
                // A finger on the phone, not the mouse (which belongs to the other device).
                if (e.actionMasked == MotionEvent.ACTION_UP && e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                    Hub.log("screen cover: touched, uncovered")
                    uncover()
                    idleMs()?.let(::watch)
                }
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "KeymaHub screen cover"
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Windows keep clear of the status and navigation bars by default (Android 11+): cover
            // them too, the whole screen.
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                isFitInsetsIgnoringVisibility = true
            }
        }
        runCatching { wm.addView(v, params) }
            .onSuccess {
                view = v
                Hub.log("screen cover: on")
            }
            .onFailure { Hub.log("screen cover failed: $it") }
    }

    /** On the main thread. */
    private fun uncover() {
        val v = view ?: return
        view = null
        runCatching { wm.removeView(v) }
    }

    /**
     * Covers again once the phone has gone [ms] without a touch. A 1x1 window in the corner that
     * watches outside touches is told of every touch anywhere else on the phone (where it went is
     * hidden from it, but when is enough): each one starts the wait again.
     */
    @SuppressLint("ClickableViewAccessibility") // it only watches
    private fun watch(ms: Long) {
        val w = View(service).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    main.removeCallbacks(recover)
                    main.postDelayed(recover, ms)
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "KeymaHub screen cover watch"
        }
        // Without it, touches go unseen: better not to cover again than to cover in the middle of use.
        runCatching { wm.addView(w, params) }
            .onSuccess {
                watcher = w
                main.postDelayed(recover, ms)
            }
            .onFailure { Hub.log("screen cover watch failed: $it") }
    }

    /** On the main thread. */
    private fun unwatch() {
        main.removeCallbacks(recover)
        val w = watcher ?: return
        watcher = null
        runCatching { wm.removeView(w) }
    }
}
