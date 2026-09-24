package dev.keymahub.poc.host

import android.os.IBinder
import android.os.Process
import android.system.Os
import android.system.OsConstants
import dev.keymahub.poc.input.EvdevKeymap
import dev.keymahub.poc.input.HidDescriptors
import dev.keymahub.poc.priv.UhidDevice
import dev.keymahub.poc.wire.Wire
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android host capture, running in the privileged (shell) process.
 *
 * Every physical keyboard and mouse is grabbed (EVIOCGRAB) for as long as capture runs,
 * so the phone itself only sees input through the UHID passthrough devices. That makes
 * switching exact: Os.ioctlInt cannot ungrab, and grabbing mid-press would leave keys
 * stuck locally, but with passthrough the router decides where each press and release go.
 *
 * New devices (e.g. a Bluetooth keyboard reconnecting) are picked up by a periodic rescan.
 * If this process dies, the kernel drops the grabs and the devices work normally again.
 */
class EvdevCapture(private val app: IBinder) {
    private val remote = CallbackSink(app)
    private val local = UhidPassthrough(
        UhidDevice("keymahub Passthrough Keyboard", HidDescriptors.KEYBOARD, 0x1209, 0x4B03),
        UhidDevice("keymahub Passthrough Mouse", HidDescriptors.MOUSE, 0x1209, 0x4B04),
    )
    private val router = HostRouter(local, remote, onFocus = { remote.focus(it) }, onNextTarget = remote::nextTarget)
    private val devices = HashMap<String, FileDescriptor>() // node -> fd
    @Volatile private var running = true

    private val death = IBinder.DeathRecipient { stop() }

    fun start() {
        app.linkToDeath(death, 0)
        Thread({
            while (running) {
                runCatching { rescan() }.onFailure { remote.log("rescan failed: $it") }
                Thread.sleep(RESCAN_MS)
            }
        }, "evdev-scan").apply { isDaemon = true }.start()
    }

    fun setRemoteAvailable(available: Boolean) = synchronized(router) { router.setRemoteAvailable(available) }

    fun stop() {
        if (!running) return
        running = false
        runCatching { app.unlinkToDeath(death, 0) }
        synchronized(router) { router.releaseAll() }
        synchronized(devices) {
            devices.values.forEach { runCatching { Os.close(it) } } // releases the grabs
            devices.clear()
        }
        local.stop()
    }

    private fun rescan() {
        val infos = InputDeviceInfo.parse(File("/proc/bus/input/devices").readText(), if (Process.is64Bit()) 64 else 32)
        for (d in infos) {
            if (d.isOurs || !(d.isKeyboard || d.isMouse)) continue
            val known = synchronized(devices) { d.node in devices || !running }
            if (known) continue
            val fd = try {
                Os.open("/dev/input/${d.node}", OsConstants.O_RDONLY, 0)
            } catch (e: Exception) {
                remote.log("open ${d.node} (${d.name}) failed: $e")
                continue
            }
            try {
                grab(fd)
            } catch (e: Exception) {
                remote.log("grab ${d.node} (${d.name}) failed: ${e.cause ?: e}")
                Os.close(fd)
                continue
            }
            synchronized(devices) { devices[d.node] = fd }
            remote.log("captured ${d.node} \"${d.name}\" (${if (d.isKeyboard) "keyboard" else ""}${if (d.isMouse) " mouse" else ""})")
            Thread({ read(d, fd) }, "evdev-${d.node}").apply { isDaemon = true }.start()
        }
    }

    private fun read(d: InputDeviceInfo, fd: FileDescriptor) {
        val size = if (Process.is64Bit()) 24 else 16
        val buf = ByteArray(size * 64)
        val input = FileInputStream(fd)
        var dx = 0
        var dy = 0
        var wheelV = 0
        var wheelH = 0
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) break
                val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                synchronized(router) {
                    while (bb.remaining() >= size) {
                        bb.position(bb.position() + size - 8) // skip timestamp
                        val type = bb.short.toInt() and 0xFFFF
                        val code = bb.short.toInt() and 0xFFFF
                        val value = bb.int
                        when (type) {
                            EV_KEY -> if (value != 2) { // 2 = auto-repeat; both sides repeat on their own
                                val button = buttonFor(code)
                                if (button != null) router.onButton(button, value == 1)
                                else router.onKey(code, EvdevKeymap.toHid(code), value == 1)
                            }
                            EV_REL -> when (code) {
                                REL_X -> dx += value
                                REL_Y -> dy += value
                                REL_WHEEL -> wheelV += value * Wire.WHEEL_NOTCH
                                REL_HWHEEL -> wheelH += value * Wire.WHEEL_NOTCH
                            }
                            EV_SYN -> if (code == SYN_REPORT) {
                                router.onMove(dx, dy)
                                router.onWheel(wheelV, wheelH)
                                dx = 0; dy = 0; wheelV = 0; wheelH = 0
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Device unplugged or capture stopped.
        }
        val removed = synchronized(devices) { devices.remove(d.node) }
        if (removed != null) {
            runCatching { Os.close(removed) }
            remote.log("released ${d.node} \"${d.name}\"")
        }
    }

    private fun buttonFor(code: Int): Int? = when (code) {
        BTN_LEFT -> Wire.BUTTON_LEFT
        BTN_RIGHT -> Wire.BUTTON_RIGHT
        BTN_MIDDLE -> Wire.BUTTON_MIDDLE
        BTN_SIDE, BTN_BACK -> Wire.BUTTON_BACK
        BTN_EXTRA, BTN_FORWARD -> Wire.BUTTON_FORWARD
        else -> null
    }

    companion object {
        private const val RESCAN_MS = 2000L
        private const val EV_SYN = 0x00
        private const val EV_KEY = 0x01
        private const val EV_REL = 0x02
        private const val SYN_REPORT = 0
        private const val REL_X = 0x00
        private const val REL_Y = 0x01
        private const val REL_HWHEEL = 0x06
        private const val REL_WHEEL = 0x08
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112
        private const val BTN_SIDE = 0x113
        private const val BTN_EXTRA = 0x114
        private const val BTN_FORWARD = 0x115
        private const val BTN_BACK = 0x116
        private const val EVIOCGRAB = 0x40044590

        /**
         * EVIOCGRAB through Os.ioctlInt: (fd, cmd) on recent releases, (fd, cmd, Int32Ref/
         * MutableInt) on older ones. Either way the kernel receives a non-null pointer as the
         * argument, which means "grab". Closing the fd releases it.
         */
        fun grab(fd: FileDescriptor) {
            val m = Os::class.java.methods.firstOrNull { it.name == "ioctlInt" }
                ?: error("Os.ioctlInt not available")
            if (m.parameterTypes.size == 2) {
                m.invoke(null, fd, EVIOCGRAB)
            } else {
                val ref = m.parameterTypes[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                m.invoke(null, fd, EVIOCGRAB, ref)
            }
        }
    }
}
