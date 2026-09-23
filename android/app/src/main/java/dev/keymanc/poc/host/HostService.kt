package dev.keymanc.poc.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import dev.keymanc.poc.AppLog
import dev.keymanc.poc.MainActivity
import dev.keymanc.poc.a11y.KeymancAccessibilityService
import dev.keymanc.poc.bt.BtHid
import dev.keymanc.poc.bt.BtHidSink
import dev.keymanc.poc.priv.PrivClient

/**
 * Android host (P0-5): physical keyboard/mouse on this phone → Bluetooth HID target.
 *
 * Capture, in order of preference:
 * - Shizuku: the privileged process grabs the devices and runs [HostRouter]; events meant
 *   for the target come back through [callback].
 * - Accessibility (no Shizuku): [A11yCapture] filters keys, and on Android 14+ intercepts
 *   the mouse while the target has focus.
 * Either way the target receives Bluetooth HID reports; Bluetooth itself needs no Shizuku.
 */
class HostService : Service() {
    private var sink: BtHidSink? = null
    private var started = false
    @Volatile private var destroyed = false
    @Volatile private var a11y: A11yCapture? = null
    private val btListener: (Boolean) -> Unit = { up ->
        a11y?.setRemoteAvailable(up) ?: PrivClient.setRemoteAvailable(up)
        updateNotification()
    }

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in HostCallbackProtocol.FOCUS..HostCallbackProtocol.LOG) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(HostCallbackProtocol.DESCRIPTOR)
            val s = sink ?: return true
            when (code) {
                HostCallbackProtocol.FOCUS -> onFocus(data.readInt() != 0)
                HostCallbackProtocol.KEY -> s.key(data.readInt(), data.readInt() != 0, false)
                HostCallbackProtocol.MOVE -> s.move(data.readInt(), data.readInt())
                HostCallbackProtocol.BUTTON -> s.button(data.readInt(), data.readInt() != 0)
                HostCallbackProtocol.WHEEL -> s.wheel(data.readInt(), data.readInt())
                HostCallbackProtocol.RELEASE_ALL -> s.releaseAll()
                HostCallbackProtocol.LOG -> AppLog.i("capture: ${data.readString()}")
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        startInForeground()
        Thread(::begin, "host-start").start()
        return START_STICKY
    }

    private fun begin() {
        val s = BtHidSink(this)
        if (destroyed) return
        s.start()?.let {
            AppLog.i("host: Bluetooth unavailable: $it")
            stopSelf()
            return
        }
        sink = s
        val err = if (PrivClient.hasPermission() && PrivClient.connect()) {
            startShizukuCapture()
        } else {
            AppLog.i("host: Shizuku unavailable (${PrivClient.shizukuState()}), using accessibility")
            startA11yCapture(s)
        }
        if (err != null) {
            AppLog.i("host: $err")
            stopSelf()
            return
        }
        if (destroyed) {
            stopCapture()
            return
        }
        BtHid.onConnectionChanged(btListener)
        btListener(BtHid.connected != null)
        running = true
        AppLog.i("host running — Ctrl+Alt+→ target, Ctrl+Alt+← this phone, Ctrl+Alt+Shift+Esc emergency")
        updateNotification()
    }

    private fun startShizukuCapture(): String? {
        PrivClient.captureStart(callback)?.let { return "Shizuku capture failed: $it" }
        AppLog.i("host capture: Shizuku (keyboard + mouse grabbed)")
        return null
    }

    private fun startA11yCapture(s: BtHidSink): String? {
        val service = KeymancAccessibilityService.instance
            ?: return "keymanc 접근성 서비스가 꺼져 있습니다 (설정 → 접근성 → keymanc 켜기, Shizuku 불필요)"
        val capture = A11yCapture(s, ::onFocus)
        a11y = capture
        service.hostCapture = capture
        val mouse = if (Build.VERSION.SDK_INT >= 34) "mouse intercepted while the target has focus" else "mouse needs Android 14+"
        AppLog.i("host capture: accessibility (keyboard; $mouse)")
        return null
    }

    private fun stopCapture() {
        val capture = a11y
        if (capture != null) {
            KeymancAccessibilityService.instance?.let { if (it.hostCapture === capture) it.hostCapture = null }
            capture.stop()
            a11y = null
        } else {
            PrivClient.captureStop()
        }
    }

    private fun onFocus(remote: Boolean) {
        remoteFocus = remote
        a11y?.let { KeymancAccessibilityService.instance?.interceptMouse(remote) }
        AppLog.i(if (remote) "focus → Bluetooth target" else "focus → this phone")
        updateNotification()
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        remoteFocus = false
        BtHid.removeListener(btListener)
        Thread {
            stopCapture()
            sink?.stop()
            BtHid.stop(applicationContext)
            AppLog.i("host stopped")
        }.start()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, HostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(if (remoteFocus) "keymanc 호스트 — 대상 기기 조작 중" else "keymanc 호스트 — 이 폰 조작 중")
            .setContentText("블루투스: ${BtHid.state()}")
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
            .createNotificationChannel(NotificationChannel(CHANNEL, "keymanc host", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    companion object {
        private const val CHANNEL = "host"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "dev.keymanc.poc.HOST_STOP"

        @Volatile
        var running = false
            private set

        @Volatile
        private var remoteFocus = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, HostService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HostService::class.java).setAction(ACTION_STOP))
        }
    }
}
