package dev.keymanc.poc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import dev.keymanc.poc.bt.BtHidSink
import dev.keymanc.poc.sink.AccessibilitySink
import dev.keymanc.poc.sink.InjectSink
import dev.keymanc.poc.sink.InputSink
import dev.keymanc.poc.sink.Pipeline
import dev.keymanc.poc.sink.UhidSink
import dev.keymanc.poc.wire.FrameReader
import dev.keymanc.poc.wire.Msg
import dev.keymanc.poc.wire.Wire
import dev.keymanc.poc.wire.writeMsg
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Foreground service that keeps the receiver alive while the app is in the background:
 * accepts one host connection at a time and feeds its input into the selected sink.
 */
class ReceiverService : Service() {
    private var server: ServerSocket? = null
    private var sink: InputSink? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var client: Socket? = null
    private var started = false
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        startInForeground()
        Thread(::serve, "receiver").start()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        runCatching { client?.close() }
        runCatching { server?.close() }
        wifiLock?.let { if (it.isHeld) it.release() }
        AppLog.i("receiver stopped")
        super.onDestroy()
    }

    private fun serve() {
        val prefs = Prefs(this)
        val backend = prefs.backend
        val inner = when (backend) {
            Backend.UHID -> UhidSink()
            Backend.INJECT -> InjectSink(this)
            Backend.ACCESSIBILITY -> AccessibilitySink()
            Backend.BT_RELAY -> BtHidSink(this)
        }
        val err = inner.start()
        if (err != null) {
            AppLog.i("backend ${backend.name} unavailable: $err")
            toast("리시버를 시작하지 못했습니다: $err")
            stopSelf()
            return
        }
        if (destroyed) {
            inner.stop()
            return
        }
        val s = Pipeline(inner, prefs.hangulKey, prefs.mouseSpeed)
        sink = s
        running = true
        AppLog.i("backend ${backend.name}, Hangul key ${prefs.hangulKey.name}, speed ${prefs.mouseSpeed}x")

        // Wi-Fi power saving adds tens to hundreds of ms of latency; keep the radio awake.
        val wifi = applicationContext.getSystemService(WifiManager::class.java)!!
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "keymanc:receiver").apply { acquire() }

        try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(Wire.DEFAULT_PORT))
            }
            server = ss
            AppLog.i("listening on ${Net.addresses().joinToString()} port ${Wire.DEFAULT_PORT}")
            while (running) {
                val sock = ss.accept()
                if (client != null) {
                    AppLog.i("busy: rejected ${sock.inetAddress.hostAddress}")
                    sock.close()
                    continue
                }
                client = sock
                Thread({ handle(sock, s) }, "receiver-client").start()
            }
        } catch (e: Exception) {
            if (running) AppLog.i("server error: $e")
        } finally {
            runCatching { s.stop() }
            sink = null
        }
    }

    private fun toast(msg: String) = android.os.Handler(mainLooper).post {
        android.widget.Toast.makeText(applicationContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun handle(sock: Socket, sink: InputSink) {
        val peer = sock.inetAddress.hostAddress
        if (!Net.isLocalPeer(sock.inetAddress)) {
            AppLog.i("rejected $peer: not on the local network")
            sock.close()
            client = null
            return
        }
        AppLog.i("host connected: $peer")
        val stats = Stats()
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 5000 // host pings every second
            val out = BufferedOutputStream(sock.getOutputStream())
            val reader = FrameReader(BufferedInputStream(sock.getInputStream()))
            out.writeMsg(Msg.Hello(Wire.VERSION, Build.MODEL))
            while (true) {
                val m = reader.next() ?: break
                val t0 = SystemClock.elapsedRealtimeNanos()
                when (m) {
                    is Msg.Hello -> AppLog.i("host: ${m.name} (wire v${m.version})")
                    is Msg.Ping -> out.writeMsg(Msg.Pong(m.tUs))
                    is Msg.Pong -> {}
                    Msg.Enter -> { sink.enter(); AppLog.i("focus → this device") }
                    Msg.Leave -> { sink.leave(); AppLog.i("focus → host") }
                    is Msg.Key -> sink.key(m.usage, m.down, m.repeat)
                    is Msg.MouseMove -> sink.move(m.dx, m.dy)
                    is Msg.MouseButton -> sink.button(m.button, m.down)
                    is Msg.Wheel -> sink.wheel(m.v, m.h)
                    Msg.ReleaseAll -> sink.releaseAll()
                }
                if (m !is Msg.Ping && m !is Msg.Hello && m !is Msg.Pong) {
                    stats.add(SystemClock.elapsedRealtimeNanos() - t0)
                }
            }
            AppLog.i("host closed the connection")
        } catch (_: SocketTimeoutException) {
            AppLog.i("host timed out (no traffic for 5 s)")
        } catch (e: Exception) {
            if (running) AppLog.i("connection error: $e")
        } finally {
            runCatching { sink.releaseAll(); sink.leave() }
            stats.flush()
            runCatching { sock.close() }
            client = null
            AppLog.i("host disconnected: $peer")
        }
    }

    /** Time spent handing events to the sink (not end-to-end latency). */
    private class Stats {
        private var n = 0
        private var total = 0L
        private var max = 0L
        private var since = SystemClock.elapsedRealtime()

        fun add(ns: Long) {
            n++
            total += ns
            if (ns > max) max = ns
            if (SystemClock.elapsedRealtime() - since >= 10_000) flush()
        }

        fun flush() {
            if (n > 0) AppLog.i("sink: $n events, avg %.0f µs, max %.0f µs".format(total / n / 1e3, max / 1e3))
            n = 0
            total = 0
            max = 0
            since = SystemClock.elapsedRealtime()
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "keymanc receiver", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("keymanc 리시버 실행 중")
            .setContentText("${Prefs(this).backend.name} · port ${Wire.DEFAULT_PORT}")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "중지", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.keymanc.poc.STOP"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
        }
    }
}
