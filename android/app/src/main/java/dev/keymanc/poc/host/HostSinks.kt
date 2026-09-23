package dev.keymanc.poc.host

import android.os.IBinder
import android.os.Parcel
import dev.keymanc.poc.input.KeyboardReport
import dev.keymanc.poc.input.MouseReport
import dev.keymanc.poc.priv.UhidDevice
import dev.keymanc.poc.sink.InputSink

/** Feeds grabbed input back into this phone through UHID devices (runs in the privileged process). */
class UhidPassthrough(private val keyboardDev: UhidDevice, private val mouseDev: UhidDevice) : InputSink {
    private val keyboard = KeyboardReport()
    private val mouse = MouseReport()

    override fun start(): String? = null

    override fun stop() {
        releaseAll()
        runCatching { keyboardDev.close() }
        runCatching { mouseDev.close() }
    }

    override fun key(usage: Int, down: Boolean, repeat: Boolean) {
        keyboard.update(usage, down)?.let(keyboardDev::input)
    }

    override fun move(dx: Int, dy: Int) = mouse.move(dx, dy).forEach(mouseDev::input)

    override fun button(button: Int, down: Boolean) {
        mouse.setButton(button, down)?.let(mouseDev::input)
    }

    override fun wheel(v: Int, h: Int) {
        mouse.wheel(v, h)?.let(mouseDev::input)
    }

    override fun releaseAll() {
        keyboardDev.input(keyboard.clear())
        mouseDev.input(mouse.clear())
    }
}

/** Binder protocol for events the privileged process sends to the app (all oneway). */
object HostCallbackProtocol {
    const val DESCRIPTOR = "dev.keymanc.poc.IHostCallback"
    const val FOCUS = 1        // (int remote)
    const val KEY = 2          // (int usage, int down)
    const val MOVE = 3         // (int dx, int dy)
    const val BUTTON = 4       // (int button, int down)
    const val WHEEL = 5        // (int v, int h)
    const val RELEASE_ALL = 6  // ()
    const val LOG = 7          // (String)
    const val NEXT_TARGET = 8  // ()
}

/** Remote side of [HostRouter] in the privileged process: forwards events to the app. */
class CallbackSink(private val app: IBinder) : InputSink {
    override fun start(): String? = null
    override fun stop() {}

    fun focus(remote: Boolean) = send(HostCallbackProtocol.FOCUS) { it.writeInt(if (remote) 1 else 0) }

    fun log(msg: String) = send(HostCallbackProtocol.LOG) { it.writeString(msg) }

    fun nextTarget() = send(HostCallbackProtocol.NEXT_TARGET) {}

    override fun key(usage: Int, down: Boolean, repeat: Boolean) = send(HostCallbackProtocol.KEY) {
        it.writeInt(usage)
        it.writeInt(if (down) 1 else 0)
    }

    override fun move(dx: Int, dy: Int) = send(HostCallbackProtocol.MOVE) {
        it.writeInt(dx)
        it.writeInt(dy)
    }

    override fun button(button: Int, down: Boolean) = send(HostCallbackProtocol.BUTTON) {
        it.writeInt(button)
        it.writeInt(if (down) 1 else 0)
    }

    override fun wheel(v: Int, h: Int) = send(HostCallbackProtocol.WHEEL) {
        it.writeInt(v)
        it.writeInt(h)
    }

    override fun releaseAll() = send(HostCallbackProtocol.RELEASE_ALL) {}

    private fun send(code: Int, write: (Parcel) -> Unit) {
        val p = Parcel.obtain()
        try {
            p.writeInterfaceToken(HostCallbackProtocol.DESCRIPTOR)
            write(p)
            app.transact(code, p, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            // App died; EvdevCapture's death recipient returns focus to local.
        } finally {
            p.recycle()
        }
    }
}
