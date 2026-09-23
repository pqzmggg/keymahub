package dev.keymanc.poc.priv

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.view.InputEvent
import dev.keymanc.poc.AppLog
import dev.keymanc.poc.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** App-side handle to [PrivilegedService] through Shizuku. */
object PrivClient {
    const val PERMISSION_REQUEST = 4201

    @Volatile
    private var binder: IBinder? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, PrivilegedService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("priv")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var ready = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            ready.countDown()
            AppLog.i("privileged service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            AppLog.i("privileged service disconnected")
        }
    }

    fun shizukuState(): String = when {
        !Shizuku.pingBinder() -> "Shizuku not running"
        Shizuku.isPreV11() -> "Shizuku too old"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "permission not granted"
        else -> "ready (v${Shizuku.getVersion()})"
    }

    fun hasPermission() = Shizuku.pingBinder() && !Shizuku.isPreV11() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    /** Binds (if needed) and waits up to [timeoutMs]. Must not be called on the main thread. */
    fun connect(timeoutMs: Long = 5000): Boolean {
        if (binder?.isBinderAlive == true) return true
        if (!hasPermission()) {
            AppLog.i("Shizuku: ${shizukuState()}")
            return false
        }
        ready = CountDownLatch(1)
        Shizuku.bindUserService(args, connection)
        ready.await(timeoutMs, TimeUnit.MILLISECONDS)
        return binder?.isBinderAlive == true
    }

    fun disconnect() {
        if (binder != null && Shizuku.pingBinder()) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
        binder = null
    }

    fun info(): String? = call(PrivProtocol.INFO) { it.readString() }

    /** Returns null on success, else an error message. */
    fun uhidCreate(): String? {
        if (binder == null) return "privileged service not connected"
        var answered = false
        val err = call(PrivProtocol.UHID_CREATE) {
            answered = true
            it.readString()
        }
        return if (answered) err else "UHID_CREATE call failed"
    }

    fun uhidDestroy() {
        call(PrivProtocol.UHID_DESTROY) { }
    }

    fun uhidInput(device: Int, report: ByteArray) = oneway(PrivProtocol.UHID_INPUT) {
        it.writeInt(device)
        it.writeByteArray(report)
    }

    fun inject(event: InputEvent) = oneway(PrivProtocol.INJECT) { event.writeToParcel(it, 0) }

    /** Android host: start grabbing keyboards/mice; events for the target arrive on [callback]. */
    fun captureStart(callback: IBinder): String? {
        if (binder == null) return "privileged service not connected"
        var answered = false
        val err = call(PrivProtocol.CAPTURE_START, { it.writeStrongBinder(callback) }) {
            answered = true
            it.readString()
        }
        return if (answered) err else "CAPTURE_START call failed"
    }

    fun captureStop() {
        call(PrivProtocol.CAPTURE_STOP) { }
    }

    fun setRemoteAvailable(available: Boolean) =
        oneway(PrivProtocol.SET_REMOTE_AVAILABLE) { it.writeInt(if (available) 1 else 0) }

    fun evdevProbe(seconds: Int): String? = call(PrivProtocol.EVDEV_PROBE, { it.writeInt(seconds) }) { it.readString() }

    private fun <T> call(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(PrivProtocol.DESCRIPTOR)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            AppLog.i("privileged call $code failed: $e")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun oneway(code: Int, write: (Parcel) -> Unit) {
        val b = binder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(PrivProtocol.DESCRIPTOR)
            write(data)
            b.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            AppLog.i("privileged oneway $code failed: $e")
        } finally {
            data.recycle()
        }
    }
}
