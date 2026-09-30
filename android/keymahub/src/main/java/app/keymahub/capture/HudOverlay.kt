package app.keymahub.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** A short-lived label at the top of the screen ("→ 1 Desktop", "← This phone"). */
class HudOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hide = Runnable { remove() }

    fun show(text: String) = main.post {
        // Added again each time, so it is above a screen cover put up since the last one.
        remove()
        val v = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                cornerRadius = 24 * resources.displayMetrics.density
                setColor(0xE0202124.toInt())
            }
        }.also { tv ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (48 * service.resources.displayMetrics.density).toInt()
                title = "KeymaHub HUD"
            }
            if (runCatching { wm.addView(tv, params) }.isFailure) return@post
            view = tv
        }
        v.text = text
        main.removeCallbacks(hide)
        main.postDelayed(hide, SHOW_MS)
    }

    companion object {
        const val SHOW_MS = 1200L
    }

    fun remove() {
        main.removeCallbacks(hide)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
