package dev.keymanc.poc.priv

import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.view.InputEvent
import dev.keymanc.poc.input.HidDescriptors
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs in a separate process as the shell user (uid 2000), started by Shizuku.
 * Only thin wrappers live here; all input logic stays in the app process.
 */
class PrivilegedService : Binder() {
    private var keyboard: UhidDevice? = null
    private var mouse: UhidDevice? = null

    private val inject: Method? by lazy {
        runCatching {
            val (cls, getInstance) = if (Build.VERSION.SDK_INT >= 34) {
                Class.forName("android.hardware.input.InputManagerGlobal").let { it to it.getMethod("getInstance") }
            } else {
                Class.forName("android.hardware.input.InputManager").let { it to it.getMethod("getInstance") }
            }
            injectTarget = getInstance.invoke(null)
            cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    private var injectTarget: Any? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == PrivProtocol.SHIZUKU_DESTROY) {
            destroyUhid()
            exitProcess(0)
        }
        if (code !in PrivProtocol.INFO..PrivProtocol.EVDEV_PROBE) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(PrivProtocol.DESCRIPTOR)
        when (code) {
            PrivProtocol.INFO -> {
                reply?.writeNoException()
                reply?.writeString(
                    "uid=${Process.myUid()} pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT} " +
                        "inject=${if (inject != null) "ok" else "unavailable"}"
                )
            }
            PrivProtocol.UHID_CREATE -> {
                val err = runCatching {
                    destroyUhid()
                    keyboard = UhidDevice("keymanc Keyboard", HidDescriptors.KEYBOARD, VENDOR, PRODUCT_KEYBOARD)
                    mouse = UhidDevice("keymanc Mouse", HidDescriptors.MOUSE, VENDOR, PRODUCT_MOUSE)
                }.exceptionOrNull()
                reply?.writeNoException()
                reply?.writeString(err?.toString())
            }
            PrivProtocol.UHID_DESTROY -> {
                destroyUhid()
                reply?.writeNoException()
            }
            PrivProtocol.UHID_INPUT -> {
                val device = data.readInt()
                val report = data.createByteArray() ?: return true
                val dev = if (device == PrivProtocol.DEVICE_KEYBOARD) keyboard else mouse
                runCatching { dev?.input(report) }
            }
            PrivProtocol.INJECT -> {
                val event = InputEvent.CREATOR.createFromParcel(data)
                runCatching { inject?.invoke(injectTarget, event, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */) }
            }
            PrivProtocol.EVDEV_PROBE -> {
                val seconds = data.readInt().coerceIn(1, 60)
                val report = runCatching { EvdevProbe.run(seconds) }.getOrElse { "probe failed: $it" }
                reply?.writeNoException()
                reply?.writeString(report)
            }
        }
        return true
    }

    private fun destroyUhid() {
        runCatching { keyboard?.close() }
        runCatching { mouse?.close() }
        keyboard = null
        mouse = null
    }

    private companion object {
        // pid.codes open-source vendor ID; product IDs are placeholders for the PoC.
        const val VENDOR = 0x1209
        const val PRODUCT_KEYBOARD = 0x4B01
        const val PRODUCT_MOUSE = 0x4B02
    }
}
