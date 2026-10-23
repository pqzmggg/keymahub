package app.keymahub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import app.keymahub.core.AutoHostingMode
import app.keymahub.core.AutoHostingRule
import app.keymahub.core.Hub
import app.keymahub.core.keyboardsAndMice
import app.keymahub.ui.HostingShortcutActivity

/**
 * Turns hosting on when a keyboard or mouse that counts connects ([app.keymahub.core.UiPrefs.autoHosting],
 * see [AutoHostingRule]), and with [app.keymahub.core.UiPrefs.autoHostingStop] off again once every one
 * that counts has been gone [STOP_AFTER_MS] (a Bluetooth mouse drops its link to save power and
 * comes back when moved).
 *
 * Lives in the accessibility service, which stays on while the app is closed. Starting hosting from
 * there is a start from the background, which Android may refuse; then a notification offers it
 * with a tap. Main thread.
 */
class AutoHostingWatcher(private val context: Context) {
    private val im = context.getSystemService(InputManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val rule = AutoHostingRule()
    private val stopLater = Runnable(::stopIfAllGone)

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = check()
        override fun onInputDeviceRemoved(deviceId: Int) = check()
        override fun onInputDeviceChanged(deviceId: Int) = check()
    }

    fun start() {
        val im = im ?: return
        check() // the first look: what is connected already does not count as connecting
        im.registerInputDeviceListener(listener, main)
    }

    fun stop() {
        im?.unregisterInputDeviceListener(listener)
        main.removeCallbacks(stopLater)
    }

    private fun check() {
        val im = im ?: return
        val ui = Hub.loadUi(context)
        val change = rule.update(ui.autoHosting, ui.autoHostingDevices.map { it.id }.toSet(), im.keyboardsAndMice())
        change.arrived?.let { device ->
            main.removeCallbacks(stopLater)
            if (!HubService.running) turnOn(device.name)
        }
        if (change.allGone && ui.autoHostingStop && HubService.running) {
            main.removeCallbacks(stopLater)
            main.postDelayed(stopLater, STOP_AFTER_MS)
        }
    }

    private fun turnOn(name: String) {
        val problem = HubService.startProblem(context)
        if (problem != null) {
            Hub.log("auto hosting: $name connected, not started: ${context.getString(problem)}")
            Hub.update { it.copy(problem = problem) }
            return
        }
        runCatching { HubService.start(context) }
            .onSuccess {
                Hub.update { it.copy(autoStartedBy = name) }
                Hub.log("auto hosting: $name connected, hosting on")
            }
            .onFailure {
                Hub.log("auto hosting: $name connected, start refused ($it), offering it in a notification")
                offer(name)
            }
    }

    /** Hosting could not start from the background: a notification starts it with a tap (from an activity, which may). */
    private fun offer(name: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_auto_hosting), NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(context, HostingShortcutActivity::class.java)
                .setAction(HostingShortcutActivity.ACTION_ON)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val tap = PendingIntent.getActivity(context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(context.getString(R.string.auto_hosting_offer_title, name))
                    .setContentText(context.getString(R.string.auto_hosting_offer_text))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { Hub.log("auto hosting notice failed: $it") }
    }

    private fun stopIfAllGone() {
        val im = im ?: return
        val ui = Hub.loadUi(context)
        if (!ui.autoHostingStop || !HubService.running || ui.autoHosting == AutoHostingMode.OFF) return
        val ids = ui.autoHostingDevices.map { it.id }.toSet()
        if (AutoHostingRule.counted(ui.autoHosting, ids, im.keyboardsAndMice()).isNotEmpty()) return
        Hub.log("auto hosting: no keyboard or mouse that counts for ${STOP_AFTER_MS / 1000} s, hosting off")
        HubService.stop(context)
    }

    companion object {
        private const val CHANNEL = "auto_hosting"
        private const val NOTIFICATION_ID = 0x4155 // "AU"
        /** How long every device that counts must stay gone before hosting turns off. */
        const val STOP_AFTER_MS = 10_000L
    }
}
