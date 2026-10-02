package app.keymahub.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
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
 * It keeps focus meanwhile (capture needs it), so a phone app's text field cannot take focus
 * and show the on-screen keyboard; [TextRelay] types into it instead, through a hidden text
 * field in this window. [reclaim] takes focus back when a mouse button is pressed after
 * something else took it (the notification shade).
 */
class PointerCaptureOverlay(
    private val service: AccessibilityService,
    private val onCaptured: (MotionEvent) -> Unit,
    /** Called with false if capture could not be obtained (caller falls back). */
    private val onCaptureState: (Boolean) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private var view: CaptureRoot? = null
    @Volatile private var wanted = false
    private var lastReclaim = 0L

    fun start() = main.post {
        wanted = true
        if (view == null) add()
    }

    /** A mouse button was pressed while not captured (focus went elsewhere): take focus and capture back. */
    fun reclaim() = main.post {
        val v = view ?: return@post
        val now = SystemClock.uptimeMillis()
        if (!wanted || v.hasPointerCapture() || now - lastReclaim < 500) return@post
        lastReclaim = now
        // Only a newly added window gets focus back; re-add it.
        view = null
        v.relay.end()
        wm.removeQuietly(v)
        add()
    }

    /** Accessibility events while a target has focus: text fields touched on the phone (see [TextRelay]). */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        // The event is recycled once the service returns: take what the relay needs now.
        if (event.packageName == service.packageName) return // this window's own field, and the app's own screens
        val type = event.eventType
        val source = event.source ?: return
        main.post { if (wanted) view?.relay?.onEvent(type, source) }
    }

    /** On the main thread. */
    private fun add() {
        val v = CaptureRoot(service)
        val params = overlayParams(
            1, 1,
            // Focusable (no FLAG_NOT_FOCUSABLE): pointer capture is only granted to the focused window.
            // Not touchable, and not touch-modal: every touch goes to the windows below.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT, "KeymaHub pointer capture",
        ).apply {
            // The on-screen keyboard shown for TextRelay must not move or resize this window.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
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

    /** The unbuffered mouse setting changed: applies it to the capture going on now. */
    fun refreshDispatch() = main.post {
        view?.takeIf { it.hasPointerCapture() }?.applyDispatch()
    }

    fun stop() = main.post {
        wanted = false
        val v = view ?: return@post
        view = null
        v.relay.end()
        runCatching { v.releasePointerCapture() }
        wm.removeQuietly(v)
    }

    /** The window's content: holds focus and capture, and [TextRelay]'s hidden text field. */
    private inner class CaptureRoot(context: Context) : FrameLayout(context) {
        val relay = TextRelay(context, this)

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            // Focus stays on this layout, not on the field, until TextRelay moves it there.
            descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && wanted) requestPointerCapture()
        }

        override fun onPointerCaptureChange(hasCapture: Boolean) {
            Hub.log(if (hasCapture) "pointer captured (phone pointer hidden)" else "pointer capture released")
            if (view !== this) return // an old window being replaced by reclaim()
            onCaptureState(hasCapture)
            if (hasCapture) applyDispatch()
            // Lost it while still on the target (e.g. notification shade took focus): try again.
            if (!hasCapture && wanted && hasWindowFocus()) main.postDelayed({ if (wanted) requestPointerCapture() }, 200)
        }

        /**
         * Captured mouse motion is otherwise batched and handed over once per display frame
         * (8-16 ms); the sender coalesces it itself, so it is taken event by event unless the
         * advanced setting says otherwise (source 0: batched again).
         */
        fun applyDispatch() {
            if (Build.VERSION.SDK_INT < 30) return
            requestUnbufferedDispatch(if (Hub.ui.value.tuning.unbufferedMouse) InputDevice.SOURCE_MOUSE_RELATIVE else 0)
        }

        // Captured events go to the focused view, which is the relay's field while typing: take them here.
        override fun dispatchCapturedPointerEvent(event: MotionEvent): Boolean {
            onCaptured(event)
            return true
        }
    }
}
