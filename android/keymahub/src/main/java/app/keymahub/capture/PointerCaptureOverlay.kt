package app.keymahub.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.keymahub.core.Hub

/**
 * While the target has focus (accessibility host mode), takes the phone's mouse with
 * pointer capture: a transparent, focusable accessibility overlay grabs input focus and
 * requests capture, so the phone's pointer is hidden and stops moving, and the mouse's
 * relative motion arrives in [onCaptured].
 *
 * Accessibility motion interception alone cannot do this: the phone's pointer is moved by
 * the input system before events are dispatched.
 *
 * The overlay is a 1x1 window in the corner; touches go to the phone's apps below it. Being the
 * top focusable window, it keeps focus even when an app is touched, and an app's text field and
 * on-screen keyboard need that focus. So a touch on the phone (seen as a touch outside the
 * overlay) takes the overlay away: focus goes to the app, and the mouse still reaches the target
 * through accessibility interception meanwhile. [reclaim] puts it back on a mouse button press.
 */
class PointerCaptureOverlay(
    private val service: AccessibilityService,
    private val onCaptured: (MotionEvent) -> Unit,
    /** Called with false if capture could not be obtained (caller falls back). */
    private val onCaptureState: (Boolean) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private var view: CaptureView? = null
    @Volatile private var wanted = false
    private var lastReclaim = 0L

    fun start() = main.post {
        wanted = true
        if (view == null) add()
    }

    /** A mouse button was pressed while not captured (the phone was touched): take focus and capture back. */
    fun reclaim() = main.post {
        val now = SystemClock.uptimeMillis()
        if (!wanted || now - lastReclaim < 500) return@post
        val v = view
        if (v != null && v.hasPointerCapture()) return@post
        lastReclaim = now
        // Only a newly added window gets focus back; (re-)add it.
        if (v != null) {
            view = null
            runCatching { wm.removeView(v) }
        }
        add()
    }

    /** The phone was touched: take the overlay away so the touched app gets focus (see the class note). */
    private fun yieldFocus() = main.post {
        val v = view ?: return@post
        if (!wanted) return@post
        view = null
        Hub.log("phone touched: focus to the phone's app until a mouse click")
        runCatching { v.releasePointerCapture() }
        runCatching { wm.removeView(v) }
        onCaptureState(false)
    }

    /** On the main thread. */
    private fun add() {
        val v = CaptureView(service)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE): pointer capture is only granted to the focused window.
            // Not touch-modal: touches outside its one pixel go to the windows below, and it hears
            // of them (ACTION_OUTSIDE) to give focus up (see the class note).
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "KeymaHub pointer capture"
        }
        runCatching { wm.addView(v, params) }
            .onSuccess {
                view = v
                v.requestFocus()
                // If focus/capture does not arrive, let the caller fall back.
                main.postDelayed({ if (wanted && view === v && !v.hasPointerCapture()) onCaptureState(false) }, 700)
            }
            .onFailure {
                Hub.log("pointer capture overlay failed: $it")
                onCaptureState(false)
            }
    }

    fun stop() = main.post {
        wanted = false
        val v = view ?: return@post
        view = null
        runCatching { v.releasePointerCapture() }
        runCatching { wm.removeView(v) }
    }

    private inner class CaptureView(context: Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && wanted) requestPointerCapture()
        }

        override fun onPointerCaptureChange(hasCapture: Boolean) {
            Hub.log(if (hasCapture) "pointer captured (phone pointer hidden)" else "pointer capture released")
            if (view !== this) return // an old window being replaced by reclaim()
            onCaptureState(hasCapture)
            // Lost it while still on the target (e.g. notification shade took focus): try again.
            if (!hasCapture && wanted && hasWindowFocus()) main.postDelayed({ if (wanted) requestPointerCapture() }, 200)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && !event.isFromSource(InputDevice.SOURCE_MOUSE)) yieldFocus()
            return true
        }

        override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
            onCaptured(event)
            return true
        }
    }
}
