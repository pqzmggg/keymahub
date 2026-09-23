package dev.keymanc.poc.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * A non-touchable arrow drawn above everything. Needed by the inject and accessibility
 * backends because injected events do not move the system pointer.
 *
 * [windowType] is TYPE_APPLICATION_OVERLAY (needs "display over other apps") or
 * TYPE_ACCESSIBILITY_OVERLAY (only from an AccessibilityService context).
 */
class CursorOverlay(private val context: Context, private val windowType: Int) {
    private val wm = context.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private var view: ArrowView? = null
    private val params = WindowManager.LayoutParams(
        SIZE, SIZE, windowType,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "keymanc cursor"
    }

    fun show() {
        main.post { addIfNeeded() }
    }

    fun hide() {
        main.post { removeIfShown() }
    }

    private fun addIfNeeded() {
        if (view != null) return
        val v = ArrowView(context)
        runCatching { wm.addView(v, params) }
            .onSuccess { view = v }
            .onFailure { dev.keymanc.poc.AppLog.i("cursor overlay failed: $it") }
    }

    private fun removeIfShown() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** Coalesces to at most one layout update per frame. */
    @Volatile private var pendingX = 0f
    @Volatile private var pendingY = 0f
    @Volatile private var scheduled = false

    fun moveTo(x: Float, y: Float) {
        pendingX = x
        pendingY = y
        if (scheduled) return
        scheduled = true
        main.post {
            scheduled = false
            val v = view ?: return@post
            params.x = pendingX.toInt()
            params.y = pendingY.toInt()
            runCatching { wm.updateViewLayout(v, params) }
        }
    }

    private class ArrowView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val path = Path().apply {
            val s = SIZE.toFloat()
            moveTo(2f, 2f)
            lineTo(2f, s * 0.85f)
            lineTo(s * 0.28f, s * 0.64f)
            lineTo(s * 0.45f, s * 0.98f)
            lineTo(s * 0.58f, s * 0.92f)
            lineTo(s * 0.42f, s * 0.6f)
            lineTo(s * 0.72f, s * 0.6f)
            close()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }

    private companion object {
        const val SIZE = 48
    }
}
