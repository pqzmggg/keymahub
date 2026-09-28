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
import android.provider.Settings
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
        BleHid.onForgotten = { _, name -> hostForgot(name) }
        // The first thing the app's process does (it restarts the service after an update): the GATT
        // server goes up at once, so hosts still linked see the HID service back soon (BleHid.refresh).
        Thread({ BleHid.openServer(applicationContext) }, "gatt-open").start()
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
        softKeyboardDespiteHardKeyboard(false)
        if (instance === this) instance = null
        if (capture != null) {
            Hub.log("accessibility service turned off while running")
            HubService.stop(applicationContext, problem = R.string.problem_a11y_turned_off)
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

    /**
     * A host forgot the keyboard (BleHid.onForgotten): it has to pair again. Says so in a
     * notification and turns pairing mode on (starting hosting), ready for it.
     */
    private fun hostForgot(name: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(HOSTS_CHANNEL, getString(R.string.notif_channel_hosts), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(
                name.hashCode(),
                Notification.Builder(this, HOSTS_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(getString(R.string.host_forgot_title, name))
                    .setContentText(getString(R.string.host_forgot_text, name))
                    .setStyle(Notification.BigTextStyle().bigText(getString(R.string.host_forgot_text, name)))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { Hub.log("forgotten host notice failed: $it") }
        runCatching { HubService.pairing(applicationContext, true) }
            .onFailure { Hub.log("pairing mode for a forgotten host failed: $it") }
    }

    /** Android 14+ fallback when pointer capture is refused: take mouse events from the phone. */
    fun interceptMouse(on: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        val info = serviceInfo ?: return
        info.motionEventSources = if (on) InputDevice.SOURCE_MOUSE else 0
        serviceInfo = info
    }

    /**
     * Android hides the on-screen keyboard while a physical keyboard is connected. While that
     * keyboard controls another device, [on] lets the phone's own on-screen keyboard show again.
     */
    fun softKeyboardDespiteHardKeyboard(on: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        val mode = if (on) AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD else AccessibilityService.SHOW_MODE_AUTO
        val ok = runCatching { softKeyboardController.setShowMode(mode) }
            .onFailure { Hub.log("on-screen keyboard mode failed: $it") }
            .getOrDefault(false)
        if (!on && ok) return
        // Diagnostics: whether the request took, and what the system and the keyboard see.
        val now = runCatching { softKeyboardController.showMode }.getOrNull()
        val setting = runCatching { Settings.Secure.getInt(contentResolver, "show_ime_with_hard_keyboard") }
            .getOrElse { "unreadable" }
        val config = resources.configuration
        Hub.log(
            "on-screen keyboard ${if (on) "despite" else "hidden by"} physical keyboard: request ${if (ok) "ok" else "refused"}, " +
                "mode=$now, show_ime_with_hard_keyboard=$setting, keyboard=${config.keyboard}, hardKeyboardHidden=${config.hardKeyboardHidden}",
        )
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
