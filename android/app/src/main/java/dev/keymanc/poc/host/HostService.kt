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
import dev.keymanc.poc.bt.BtHid
import dev.keymanc.poc.bt.BtHidSink
import dev.keymanc.poc.priv.PrivClient

/**
 * Android host (P0-5): physical keyboard/mouse on this phone → Bluetooth HID target.
 *
 * The privileged process grabs the devices and runs [HostRouter]; events meant for the
 * target come back through [callback] and are sent as Bluetooth HID reports.
 */
class HostService : Service() {
    private var sink: BtHidSink? = null
    private var started = false
    @Volatile private var destroyed = false
    private val btListener: (Boolean) -> Unit = { up ->
        PrivClient.setRemoteAvailable(up)
        updateNotification()
    }

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in HostCallbackProtocol.FOCUS..HostCallbackProtocol.LOG) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(HostCallbackProtocol.DESCRIPTOR)
            val s = sink ?: return true
            when (code) {
                HostCallbackProtocol.FOCUS -> {
                    remoteFocus = data.readInt() != 0
                    AppLog.i(if (remoteFocus) "focus → Bluetooth target" else "focus → this phone")
                    updateNotification()
                }
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
        if (!PrivClient.connect()) {
            AppLog.i("host: Shizuku ${PrivClient.shizukuState()}")
            stopSelf()
            return
        }
        PrivClient.captureStart(callback)?.let {
            AppLog.i("host: capture failed: $it")
            stopSelf()
            return
        }
        if (destroyed) {
            PrivClient.captureStop()
            return
        }
        BtHid.onConnectionChanged(btListener)
        PrivClient.setRemoteAvailable(BtHid.connected != null)
        running = true
        AppLog.i("host running — Ctrl+Alt+→ target, Ctrl+Alt+← this phone, Ctrl+Alt+Shift+Esc emergency")
        updateNotification()
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        remoteFocus = false
        BtHid.removeListener(btListener)
        Thread {
            PrivClient.captureStop()
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
