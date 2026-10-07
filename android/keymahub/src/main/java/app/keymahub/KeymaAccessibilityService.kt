package app.keymahub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import app.keymahub.ble.BleHid
import app.keymahub.capture.A11yCapture
import app.keymahub.core.Hub
import app.keymahub.ui.MainActivity

/**
 * Receives the phone's hardware key events (key filtering) and, while a target has focus,
 * provides the overlay window type for pointer capture and the HUD. Does nothing unless
 * [HubService] is running.
 */
class KeymaAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Hub.log("accessibility service connected")
        BleHid.onForgotten = { name -> hostForgot(name) }
        BleHid.onRefusedNew = { name ->
            hostNotice(name, getString(R.string.host_refused_title, name), getString(R.string.host_refused_text, name))
        }
        // The first thing the app's process does (it restarts the service after an update): the GATT
        // server goes up at once, so hosts still linked see the HID service back soon (BleHid.refresh).
        Thread({ BleHid.openServer(applicationContext) }, "gatt-open").start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        leaveTarget()
        if (instance === this) instance = null
        if (capture != null) {
            Hub.log("accessibility service turned off while running")
            HubService.stop(applicationContext, problem = R.string.problem_a11y_turned_off)
        }
        capture = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) textEvents?.invoke(event)
    }

    /**
     * While set (a target has the mouse, see HubService.onSelect), gets the events that show a
     * text field touched on the phone, and its text changing (capture.TextRelay). Only then are
     * they subscribed to: otherwise the service reads nothing on the screen.
     */
    @Volatile
    var textEvents: ((AccessibilityEvent) -> Unit)? = null
        set(value) {
            field = value
            editServiceInfo {
                it.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or (
                    if (value == null) 0
                    else AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    )
            }
        }

    /** Changes [serviceInfo]: one change at a time, so changes from different threads do not undo each other. */
    @Synchronized
    private fun editServiceInfo(change: (AccessibilityServiceInfo) -> Unit) {
        val info = serviceInfo ?: return
        change(info)
        serviceInfo = info
    }

    /** Back to the phone (no target, or the service stops): undoes what a target turned on. */
    fun leaveTarget() {
        interceptMouse(false)
        softKeyboardDespiteHardKeyboard(false)
        textEvents = null
    }

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

    /**
     * A host forgot the keyboard (BleHid.onForgotten): it has to pair again. Says so in a
     * notification and turns pairing mode on (starting hosting), ready for it.
     */
    private fun hostForgot(name: String) {
        hostNotice(name, getString(R.string.host_forgot_title, name), getString(R.string.host_forgot_text, name))
        runCatching { HubService.pairing(applicationContext, true) }
            .onFailure { Hub.log("pairing mode for a forgotten host failed: $it") }
    }

    /** A notification about the host [name] (one per host: a newer one replaces it); opens the app. */
    private fun hostNotice(name: String, title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(HOSTS_CHANNEL, getString(R.string.notif_channel_hosts), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                name.hashCode(),
                Notification.Builder(this, HOSTS_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { Hub.log("host notice failed: $it") }
    }

    /** Android 14+ fallback when pointer capture is refused: take mouse events from the phone. */
    fun interceptMouse(on: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        editServiceInfo { it.motionEventSources = if (on) InputDevice.SOURCE_MOUSE else 0 }
    }

    /**
     * Android hides the on-screen keyboard while a physical keyboard is connected. While that
     * keyboard controls another device, [on] lets the phone's own on-screen keyboard show again.
     */
    fun softKeyboardDespiteHardKeyboard(on: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        val mode = if (on) AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD else AccessibilityService.SHOW_MODE_AUTO
        runCatching { softKeyboardController.setShowMode(mode) }
            .onFailure { Hub.log("on-screen keyboard mode failed: $it") }
    }

    companion object {
        private const val HOSTS_CHANNEL = "hosts"

        @Volatile
        var instance: KeymaAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        }
    }
}
