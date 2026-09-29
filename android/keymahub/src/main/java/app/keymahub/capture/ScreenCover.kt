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
 * or an app. A touch on the phone takes the cover away until control moves to another device again.
 *
 * Not focusable, so pointer capture keeps its focus; above the lock screen, as accessibility
 * overlays are.
 */
@SuppressLint("ClickableViewAccessibility") // a black cover: the touch only takes it away
class ScreenCover(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null

    fun show() = main.post {
        if (view != null) return@post
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
                    hide()
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
        }
        runCatching { wm.addView(v, params) }
            .onSuccess {
                view = v
                Hub.log("screen cover: on")
            }
            .onFailure { Hub.log("screen cover failed: $it") }
    }

    fun hide() = main.post {
        val v = view ?: return@post
        view = null
        runCatching { wm.removeView(v) }
    }
}
