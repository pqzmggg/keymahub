package app.keymahub.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
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
 * The overlay is a 1x1 window that is not touchable, so touches keep reaching the phone's apps.
 * Touching an app moves focus there and ends the capture; [reclaim] takes it back when the
 * mouse is used again.
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

    /** The mouse moved while not captured (focus went to a touched app): take focus and capture back. */
    fun reclaim() = main.post {
        val v = view ?: return@post
        val now = SystemClock.uptimeMillis()
        if (!wanted || v.hasPointerCapture() || now - lastReclaim < 500) return@post
        lastReclaim = now
        // Only a newly added window gets focus back; re-add it.
        view = null
        runCatching { wm.removeView(v) }
        add()
    }

    /** On the main thread. */
    private fun add() {
        val v = CaptureView(service)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE): pointer capture is only granted to the focused window.
            // Not touchable, and not touch-modal: every touch goes to the windows below.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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

        override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
            onCaptured(event)
            return true
        }
    }
}
