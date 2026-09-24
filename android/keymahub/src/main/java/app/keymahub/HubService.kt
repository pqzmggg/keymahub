package app.keymahub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.keymahub.ble.BleHid
import app.keymahub.ble.HidSender
import app.keymahub.capture.A11yCapture
import app.keymahub.capture.HudOverlay
import app.keymahub.capture.PointerCaptureOverlay
import app.keymahub.core.Hotkey
import app.keymahub.core.Hub
import app.keymahub.ui.MainActivity

/**
 * Runs KeymaHub in the background ("hosting"): the phone advertises as a BLE keyboard + mouse,
 * and the hotkeys (modifiers + 1..9, 0) move the phone's keyboard and mouse between the active profile's receivers and
 * the phone itself. Also re-picks the active profile as devices connect and disconnect.
 */
class HubService : Service(), BleHid.Listener {
    private var started = false
    @Volatile private var destroyed = false
    @Volatile private var ready = false
    private var sender: HidSender? = null
    private var capture: A11yCapture? = null
    private var pointerCapture: PointerCaptureOverlay? = null
    private var hud: HudOverlay? = null
    private val main = Handler(Looper.getMainLooper())
    private var pendingPairing = false
    /** Pairing mode was turned on by starting hosting, not by the user (see startReconnectWindow). */
    private var reconnectWindow = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Locales.wrap(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAIR -> {
                val on = intent.getBooleanExtra(EXTRA_ON, true)
                if (ready) setPairing(on) else pendingPairing = on
            }
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
        main.post { stopSelf() }
    }

    private val profile get() = Hub.settings(this).active

    private fun begin() {
        val a11y = KeymaAccessibilityService.instance ?: return fail(R.string.problem_a11y_off)
        Hub.loadUi(this)
        BleHid.isBlocked = { address -> Hub.settings(this).device(address)?.blocked == true }
        BleHid.knownTargets = { Hub.settings(this).devices.map { it.address } }
        BleHid.start(this)?.let { return fail(it) }
        if (destroyed) return BleHid.stop()

        val s = HidSender()
        val c = A11yCapture(s, ::hotkeyTarget, ::isAvailable, ::onSelect, ::onUnavailable) { pointerCapture?.reclaim() }
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
            showHud(getString(R.string.hud_profile, profileLabel()))
            updateNotification()
        }
        BleHid.addListener(this)
        for (address in BleHid.readyTargets()) onReady(address, BleHid.nameOf(address))

        running = true
        ready = true
        Hub.update { it.copy(running = true, slot = 0, ready = BleHid.readyTargets(), problem = null) }
        Hub.log("running, profile ${profile.id}")
        main.post {
            if (destroyed) return@post
            if (pendingPairing) setPairing(true) else startReconnectWindow()
        }
        updateNotification()
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        ready = false
        main.removeCallbacksAndMessages(null)
        Hub.onProfileChanged = null
        BleHid.removeListener(this)
        val c = capture
        KeymaAccessibilityService.instance?.let {
            it.softKeyboardDespiteHardKeyboard(false)
            if (it.capture === c) it.capture = null
        }
        c?.stop()
        pointerCapture?.stop()
        hud?.remove()
        sender?.shutdown()
        Thread { BleHid.stop() }.start()
        Hub.update { it.copy(running = false, slot = 0, ready = emptySet(), pairingUntil = 0) }
        Hub.log("stopped")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pairing mode

    private val pairingOff = Runnable { setPairing(false) }

    /** Main thread. */
    private fun setPairing(on: Boolean, durationMs: Long = PAIRING_MS) {
        reconnectWindow = false
        BleHid.setPairing(on)
        main.removeCallbacks(pairingOff)
        if (on) main.postDelayed(pairingOff, durationMs)
        Hub.update { it.copy(pairingUntil = if (on) SystemClock.elapsedRealtime() + durationMs else 0) }
        updateNotification()
    }

    /**
     * Paired tablets come back reliably only while pairing mode is on, so hosting starts with a
     * short pairing window. It ends early once every paired device is back.
     */
    private fun startReconnectWindow() {
        if (waitingFor().isEmpty()) return
        Hub.log("pairing mode on for ${RECONNECT_WINDOW_MS / 1000} s so paired devices can reconnect")
        setPairing(true, RECONNECT_WINDOW_MS)
        reconnectWindow = true
    }

    /** Paired devices (not disconnected by the user) that are not back yet. */
    private fun waitingFor(): List<String> {
        val ready = BleHid.readyTargets()
        return Hub.settings(this).devices.filter { !it.blocked && it.address !in ready }.map { it.address }
    }

    // ---------------------------------------------------------------- routing callbacks

    private fun hotkeyTarget(h: Hotkey): Int? = Hub.settings(this).slotFor(h)

    private fun isAvailable(slot: Int): Boolean {
        val address = profile.addressOf(slot) ?: return false
        return address in BleHid.readyTargets()
    }

    private fun receiverName(slot: Int): String? =
        profile.addressOf(slot)?.let { Hub.settings(this).device(it)?.name }

    private fun hotkeyLabel(slot: Int) = KeyLabels.hotkey(Hub.settings(this).hotkey(slot))

    private fun profileLabel() = profile.name.ifEmpty { getString(R.string.profile_default) }

    private fun showHud(text: String) {
        if (Hub.ui.value.showHud) hud?.show(text)
    }

    private fun onSelect(slot: Int) {
        val a11y = KeymaAccessibilityService.instance
        if (slot == 0) {
            sender?.select(null)
            pointerCapture?.stop()
            a11y?.interceptMouse(false)
            a11y?.softKeyboardDespiteHardKeyboard(false)
            showHud(getString(R.string.hud_phone))
        } else {
            val address = profile.addressOf(slot)
            sender?.select(address)
            pointerCapture?.start()
            a11y?.softKeyboardDespiteHardKeyboard(Hub.ui.value.touchKeyboard)
            showHud(getString(R.string.hud_target, receiverName(slot).orEmpty()))
            if (address != null) Hub.edit(this) { it.touch(address, System.currentTimeMillis()) }
        }
        Hub.update { it.copy(slot = slot) }
        updateNotification()
    }

    private fun onUnavailable(slot: Int) {
        val name = receiverName(slot)
        showHud(
            if (name != null) getString(R.string.hud_unavailable, name)
            else getString(R.string.hud_empty, hotkeyLabel(slot)),
        )
    }

    override fun onReady(address: String, name: String) {
        // Ready set first: the edit re-picks the profile, and "when connected" rules look at it.
        Hub.update { it.copy(ready = BleHid.readyTargets()) }
        Hub.edit(this) { it.deviceConnected(address, name).touch(address, System.currentTimeMillis()) }
        Hub.settings(this).device(address)?.let { showHud(getString(R.string.hud_connected, it.name)) }
        updateNotification()
        main.post { if (reconnectWindow && waitingFor().isEmpty()) setPairing(false) }
    }

    override fun onGone(address: String) {
        profile.slotOf(address)?.let { capture?.targetLost(it) }
        Hub.update { it.copy(ready = BleHid.readyTargets()) }
        if (Hub.settings(this).device(address) != null) Hub.edit(this) { it.touch(address, System.currentTimeMillis()) }
        Hub.resolve(this)
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
        val text = if (status.pairingUntil != 0L) {
            getString(R.string.notif_pairing)
        } else {
            getString(R.string.notif_text, profileLabel(), status.ready.size, hotkeyLabel(0))
        }
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
        private const val ACTION_STOP = "app.keymahub.STOP"
        private const val ACTION_PAIR = "app.keymahub.PAIR"
        private const val EXTRA_ON = "on"
        const val PAIRING_MS = 3 * 60_000L
        private const val RECONNECT_WINDOW_MS = 60_000L

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

        /** Turns pairing mode on (starting hosting if needed) or off. */
        fun pairing(context: Context, on: Boolean) {
            if (!on && !running) return
            Hub.update { it.copy(problem = null) }
            val intent = Intent(context, HubService::class.java).setAction(ACTION_PAIR).putExtra(EXTRA_ON, on)
            if (running) context.startService(intent) else context.startForegroundService(intent)
        }
    }
}
