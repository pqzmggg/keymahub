package com.kemahub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.kemahub.ble.BleHid
import com.kemahub.ble.HidSender
import com.kemahub.capture.A11yCapture
import com.kemahub.capture.HudOverlay
import com.kemahub.capture.PointerCaptureOverlay
import com.kemahub.core.Hotkey
import com.kemahub.core.Hub
import com.kemahub.ui.MainActivity

/**
 * Runs KemaHub in the background: the phone advertises as a BLE keyboard + mouse, and the
 * active profile's hotkeys move the phone's keyboard and mouse between receivers and the
 * phone itself.
 */
class HubService : Service(), BleHid.Listener {
    private var started = false
    @Volatile private var destroyed = false
    private var sender: HidSender? = null
    private var capture: A11yCapture? = null
    private var pointerCapture: PointerCaptureOverlay? = null
    private var hud: HudOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        startInForeground()
        Thread(::begin, "hub-start").start()
        return START_STICKY
    }

    private fun fail(problem: Int) {
        Hub.log("start failed: ${getString(problem)}")
        Hub.update { it.copy(running = false, problem = problem) }
        stopSelf()
    }

    private val profile get() = Hub.settings(this).active

    private fun begin() {
        val a11y = KemaAccessibilityService.instance ?: return fail(R.string.problem_a11y_off)
        BleHid.start(this)?.let { return fail(it) }
        if (destroyed) return BleHid.stop()

        val s = HidSender()
        val c = A11yCapture(s, ::hotkeyTarget, ::isAvailable, ::onSelect, ::onUnavailable)
        sender = s
        capture = c
        hud = HudOverlay(a11y)
        pointerCapture = PointerCaptureOverlay(a11y, c::onCapturedPointer) { captured ->
            // Without capture, fall back to accessibility interception (the phone's pointer keeps moving).
            if (c.slot != 0) a11y.interceptMouse(!captured)
        }
        a11y.capture = c
        // Another profile may put other receivers on the hotkeys: start again from the phone.
        Hub.onProfileChanged = {
            c.select(0)
            updateNotification()
        }
        BleHid.addListener(this)
        for (address in BleHid.readyTargets()) onReady(address, BleHid.nameOf(address))

        running = true
        Hub.update { it.copy(running = true, slot = 0, ready = BleHid.readyTargets(), problem = null) }
        Hub.log("running, profile ${profile.id}")
        updateNotification()
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        Hub.onProfileChanged = null
        BleHid.removeListener(this)
        val c = capture
        KemaAccessibilityService.instance?.let { if (it.capture === c) it.capture = null }
        c?.stop()
        pointerCapture?.stop()
        hud?.remove()
        sender?.shutdown()
        Thread { BleHid.stop() }.start()
        Hub.update { it.copy(running = false, slot = 0, ready = emptySet()) }
        Hub.log("stopped")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- routing callbacks

    private fun hotkeyTarget(h: Hotkey): Int? = if (Hub.recordingHotkey) null else profile.slotFor(h)

    private fun isAvailable(slot: Int): Boolean {
        val address = profile.addressOf(slot) ?: return false
        return address in BleHid.readyTargets()
    }

    private fun receiverName(slot: Int): String? =
        profile.addressOf(slot)?.let { Hub.settings(this).device(it)?.name }

    private fun hotkeyLabel(slot: Int) = KeyLabels.hotkey(profile.hotkeys[slot])

    private fun onSelect(slot: Int) {
        val a11y = KemaAccessibilityService.instance
        if (slot == 0) {
            sender?.select(null)
            pointerCapture?.stop()
            a11y?.interceptMouse(false)
            hud?.show(getString(R.string.hud_phone))
        } else {
            sender?.select(profile.addressOf(slot))
            pointerCapture?.start()
            hud?.show(getString(R.string.hud_target, receiverName(slot).orEmpty()))
        }
        Hub.update { it.copy(slot = slot) }
        updateNotification()
    }

    private fun onUnavailable(slot: Int) {
        val name = receiverName(slot)
        hud?.show(
            if (name != null) getString(R.string.hud_unavailable, name)
            else getString(R.string.hud_empty, hotkeyLabel(slot)),
        )
    }

    override fun onReady(address: String, name: String) {
        Hub.edit(this) { it.deviceConnected(address, name) }
        Hub.update { it.copy(ready = BleHid.readyTargets()) }
        Hub.settings(this).device(address)?.let { hud?.show(getString(R.string.hud_connected, it.name)) }
        updateNotification()
    }

    override fun onGone(address: String) {
        profile.slotOf(address)?.let { capture?.targetLost(it) }
        Hub.update { it.copy(ready = BleHid.readyTargets()) }
        updateNotification()
    }

    // ---------------------------------------------------------------- notification

    private fun notification(): Notification {
        val status = Hub.status.value
        val title = if (status.slot == 0) {
            getString(R.string.status_phone)
        } else {
            getString(R.string.status_target, receiverName(status.slot).orEmpty())
        }
        val text = getString(R.string.notif_text, status.ready.size, hotkeyLabel(0))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (destroyed) return
        getSystemService(NotificationManager::class.java)!!.notify(NOTIFICATION_ID, notification())
    }

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java)!!
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    companion object {
        private const val CHANNEL = "hub"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.kemahub.STOP"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            Hub.update { it.copy(problem = null) }
            context.startForegroundService(Intent(context, HubService::class.java))
        }

        fun stop(context: Context, problem: Int? = null) {
            if (problem != null) Hub.update { it.copy(problem = problem) }
            // May be refused if the app is no longer allowed to start services; nothing to stop then.
            runCatching { context.startService(Intent(context, HubService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
