package app.keymahub.capture

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Layout of an accessibility overlay window: [w] x [h] at [gravity], named [title] (shown in window dumps). */
internal fun overlayParams(w: Int, h: Int, flags: Int, format: Int, title: String, gravity: Int = Gravity.TOP or Gravity.START) =
    WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, format).also {
        it.gravity = gravity
        it.title = title
    }

/** Removes an overlay window; one already gone is fine. */
internal fun WindowManager.removeQuietly(v: View) {
    runCatching { removeView(v) }
}

/** [n] density-independent pixels in pixels. */
internal fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
