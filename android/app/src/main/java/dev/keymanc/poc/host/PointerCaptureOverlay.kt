package dev.keymanc.poc.host

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dev.keymanc.poc.AppLog

/**
 * While the target has focus (accessibility host mode), takes the phone's mouse with
 * pointer capture: a transparent, focusable accessibility overlay grabs input focus and
 * requests capture, so the phone's pointer is hidden and stops moving, and the mouse's
 * relative motion arrives in [onCaptured].
 *
 * Accessibility motion interception alone cannot do this: the phone's pointer is moved by
 * the input system before events are dispatched.
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

    fun start() = main.post {
        wanted = true
        if (view != null) return@post
        val v = CaptureView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE): pointer capture is only granted to the focused window.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { title = "keymanc pointer capture" }
        runCatching { wm.addView(v, params) }
            .onSuccess {
                view = v
                v.requestFocus()
                // If focus/capture does not arrive, let the caller fall back.
                main.postDelayed({ if (wanted && view === v && !v.hasPointerCapture()) onCaptureState(false) }, 700)
            }
            .onFailure {
                AppLog.i("pointer capture overlay failed: $it")
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
            AppLog.i(if (hasCapture) "pointer captured (phone pointer hidden)" else "pointer capture released")
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
