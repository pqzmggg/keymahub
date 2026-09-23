package dev.keymanc.poc.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import dev.keymanc.poc.AppLog
import dev.keymanc.poc.host.A11yCapture

/**
 * Receiver: gestures, global actions and the overlay window type for [AccessibilitySink].
 * Host without Shizuku: key filtering and (Android 14+) mouse interception for [A11yCapture].
 */
class KeymancAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        AppLog.i("accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Set while the Android host runs without Shizuku (see [A11yCapture]). */
    @Volatile
    var hostCapture: A11yCapture? = null
        set(value) {
            field = value
            if (value == null) interceptMouse(false)
        }

    override fun onKeyEvent(event: KeyEvent): Boolean = hostCapture?.onKeyEvent(event) ?: false

    override fun onMotionEvent(event: MotionEvent) {
        hostCapture?.onMotionEvent(event)
    }

    /**
     * Android 14+: take mouse events away from the phone (only while the target is being
     * controlled). Returns false when the platform cannot do it.
     */
    fun interceptMouse(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val info = serviceInfo ?: return false
        info.motionEventSources = if (on) InputDevice.SOURCE_MOUSE else 0
        serviceInfo = info
        return true
    }

    fun gesture(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 10_000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        @Volatile
        var instance: KeymancAccessibilityService? = null
            private set
    }
}
