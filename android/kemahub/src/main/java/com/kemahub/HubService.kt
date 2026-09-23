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
import com.kemahub.core.Hub
import com.kemahub.ui.MainActivity

/**
 * Runs KemaHub in the background: the phone advertises as a BLE keyboard + mouse, and
 * Ctrl+Alt+1..9 / Ctrl+Alt+0 move the phone's keyboard and mouse between targets and the
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

    private fun fail(problem: String) {
        Hub.log("start failed: $problem")
        Hub.update { it.copy(running = false, problem = problem) }
        stopSelf()
    }

    private fun begin() {
        val a11y = KemaAccessibilityService.instance ?: return fail("접근성 서비스가 꺼져 있습니다")
        BleHid.start(this)?.let { return fail(it) }
        if (destroyed) return BleHid.stop()

        val s = HidSender()
        val c = A11yCapture(s, ::isAvailable, ::onSelect, ::onUnavailable)
        sender = s
        capture = c
        hud = HudOverlay(a11y)
        pointerCapture = PointerCaptureOverlay(a11y, c::onCapturedPointer) { captured ->
            // Without capture, fall back to accessibility interception (the phone's pointer keeps moving).
            if (c.slot != 0) a11y.interceptMouse(!captured)
        }
        a11y.capture = c
        BleHid.addListener(this)
        for (address in BleHid.readyTargets()) onReady(address, BleHid.nameOf(address))

        running = true
        Hub.update { it.copy(running = true, slot = 0, ready = BleHid.readyTargets(), problem = null) }
        Hub.log("running — Ctrl+Alt+1..9 target, Ctrl+Alt+0 this phone")
        updateNotification()
    }

    override fun onDestroy() {
        destroyed = true
        running = false
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

    private fun isAvailable(slot: Int): Boolean {
        val address = Hub.targets(this).addressOf(slot) ?: return false
        return address in BleHid.readyTargets()
    }

    private fun onSelect(slot: Int) {
        val a11y = KemaAccessibilityService.instance
        if (slot == 0) {
            sender?.select(null)
            pointerCapture?.stop()
            a11y?.interceptMouse(false)
            hud?.show("← 이 폰")
        } else {
            val target = Hub.targets(this).all.firstOrNull { it.slot == slot }
            sender?.select(target?.address)
            pointerCapture?.start()
            hud?.show("→ $slot  ${target?.name.orEmpty()}")
        }
        Hub.update { it.copy(slot = slot) }
        updateNotification()
    }

    private fun onUnavailable(slot: Int) {
        val name = Hub.targets(this).all.firstOrNull { it.slot == slot }?.name
        hud?.show(if (name != null) "$slot  $name — 연결 안 됨" else "$slot 번에 등록된 대상 없음")
    }

    override fun onReady(address: String, name: String) {
        Hub.editTargets(this) { it.ensure(address, name) }
        Hub.update { it.copy(ready = BleHid.readyTargets()) }
        val t = Hub.targets(this).get(address)
        if (t != null) hud?.show("${t.slot}  ${t.name} 연결됨")
        updateNotification()
    }

    override fun onGone(address: String) {
        Hub.targets(this).get(address)?.let { capture?.targetLost(it.slot) }
        Hub.update { it.copy(ready = BleHid.readyTargets()) }
        updateNotification()
    }

    // ---------------------------------------------------------------- notification

    private fun notification(): Notification {
        val status = Hub.status.value
        val slot = status.slot
        val title = if (slot == 0) {
            "이 폰 조작 중"
        } else {
            "$slot  ${status.targets.firstOrNull { it.slot == slot }?.name.orEmpty()} 조작 중"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HubService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText("연결된 대상 ${status.ready.size}개 · Ctrl+Alt+1~9 대상 · Ctrl+Alt+0 이 폰")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (destroyed) return
        getSystemService(NotificationManager::class.java)!!.notify(NOTIFICATION_ID, notification())
    }

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java)!!
            .createNotificationChannel(NotificationChannel(CHANNEL, "KemaHub", NotificationManager.IMPORTANCE_LOW))
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

        fun stop(context: Context, problem: String? = null) {
            if (problem != null) Hub.update { it.copy(problem = problem) }
            // May be refused if the app is no longer allowed to start services; nothing to stop then.
            runCatching { context.startService(Intent(context, HubService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
