package com.kemahub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.kemahub.capture.A11yCapture
import com.kemahub.core.Hub

/**
 * Receives the phone's hardware key events (key filtering) and, while a target has focus,
 * provides the overlay window type for pointer capture and the HUD. Does nothing unless
 * [HubService] is running.
 */
class KemaAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Hub.log("accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        if (instance === this) instance = null
        if (capture != null) {
            Hub.log("accessibility service turned off while running")
            HubService.stop(applicationContext, problem = "접근성 서비스가 꺼져서 중지했습니다")
        }
        capture = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Set by [HubService] while it runs. */
    @Volatile
    var capture: A11yCapture? = null
        set(value) {
            field = value
            if (value == null) interceptMouse(false)
        }

    override fun onKeyEvent(event: KeyEvent): Boolean = capture?.onKeyEvent(event) ?: false

    override fun onMotionEvent(event: MotionEvent) {
        capture?.onMotionEvent(event)
    }

    /** Android 14+ fallback when pointer capture is refused: take mouse events from the phone. */
    fun interceptMouse(on: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        val info = serviceInfo ?: return
        info.motionEventSources = if (on) InputDevice.SOURCE_MOUSE else 0
        serviceInfo = info
    }

    companion object {
        @Volatile
        var instance: KemaAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        }
    }
}
