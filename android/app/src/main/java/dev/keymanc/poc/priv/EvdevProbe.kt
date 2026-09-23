package dev.keymanc.poc.priv

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * P0-4: can the shell user read and exclusively grab physical keyboards/mice?
 * That decides whether an Android device can be a host (design §7.3).
 *
 * For each keyboard/mouse-like device: open it, EVIOCGRAB it, then count events for the
 * given time. While grabbed, the local UI should NOT react to that device.
 */
object EvdevProbe {
    private const val EV_KEY = 0x01
    private const val EV_REL = 0x02
    private const val EVIOCGRAB = 0x40044590 // _IOW('E', 0x90, int)

    private data class Dev(val name: String, val node: String, val ev: Long)

    fun run(seconds: Int): String {
        val out = StringBuilder()
        out.appendLine("uid=${Process.myUid()} 64bit=${Process.is64Bit()}")
        val devices = try {
            parseDevices(File("/proc/bus/input/devices").readText())
        } catch (e: Exception) {
            return out.appendLine("cannot read /proc/bus/input/devices: $e").toString()
        }
        val candidates = devices.filter { it.ev and (1L shl EV_KEY) != 0L || it.ev and (1L shl EV_REL) != 0L }
        out.appendLine("input devices: ${devices.size}, key/rel capable: ${candidates.size}")

        // Os.ioctlInt is (fd, cmd) on recent releases and (fd, cmd, Int32Ref/MutableInt) on
        // older ones. Either way the kernel receives a pointer as the ioctl argument, and for
        // EVIOCGRAB any non-null argument means "grab". Closing the fd releases the grab.
        val ioctlInt = Os::class.java.methods.firstOrNull { it.name == "ioctlInt" }
        val grabFd: ((FileDescriptor) -> Unit)? = ioctlInt?.let { m ->
            { fd ->
                if (m.parameterTypes.size == 2) {
                    m.invoke(null, fd, EVIOCGRAB)
                } else {
                    val ref = m.parameterTypes[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1)
                    m.invoke(null, fd, EVIOCGRAB, ref)
                }
            }
        }
        if (grabFd == null) out.appendLine("Os.ioctlInt unavailable (grab not testable)")

        class Open(val dev: Dev, val fd: FileDescriptor, val grabbed: Boolean, val count: AtomicInteger, val sample: StringBuffer)
        val opened = ArrayList<Open>()
        for (d in candidates) {
            val fd = try {
                Os.open("/dev/input/${d.node}", OsConstants.O_RDONLY, 0)
            } catch (e: Exception) {
                out.appendLine("  ${d.node} \"${d.name}\": open FAILED ($e)")
                continue
            }
            val grab = try {
                grabFd?.invoke(fd)
                grabFd != null
            } catch (e: Exception) {
                out.appendLine("  ${d.node} \"${d.name}\": grab FAILED (${e.cause ?: e})")
                false
            }
            out.appendLine("  ${d.node} \"${d.name}\": open ok, grab ${if (grab) "ok" else "no"}")
            opened += Open(d, fd, grab, AtomicInteger(), StringBuffer())
        }

        out.appendLine("reading for ${seconds}s — press keys / move the mouse now")
        val eventSize = if (Process.is64Bit()) 24 else 16
        val threads = opened.map { o ->
            Thread {
                val buf = ByteArray(eventSize * 64)
                val input = FileInputStream(o.fd)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                        while (bb.remaining() >= eventSize) {
                            bb.position(bb.position() + eventSize - 8)
                            val type = bb.short.toInt() and 0xFFFF
                            val code = bb.short.toInt() and 0xFFFF
                            val value = bb.int
                            if (type == EV_KEY || type == EV_REL) {
                                o.count.incrementAndGet()
                                if (o.sample.length < 200) o.sample.append("${evName(type)}:$code=$value ")
                            }
                        }
                    }
                } catch (_: Exception) {
                    // fd closed at the end of the probe
                }
            }.apply { isDaemon = true; start() }
        }
        Thread.sleep(seconds * 1000L)
        for (o in opened) runCatching { Os.close(o.fd) }
        threads.forEach { it.join(500) }

        out.appendLine("results:")
        for (o in opened) {
            out.appendLine("  ${o.dev.node} \"${o.dev.name}\" grabbed=${o.grabbed} events=${o.count.get()}")
            if (o.sample.isNotEmpty()) out.appendLine("    ${o.sample}")
        }
        out.appendLine("If a grabbed device produced events but the screen did not react, grab works.")
        return out.toString()
    }

    private fun evName(type: Int) = if (type == EV_KEY) "KEY" else "REL"

    private fun parseDevices(text: String): List<Dev> = text.split("\n\n").mapNotNull { block ->
        var name = ""
        var node: String? = null
        var ev = 0L
        for (line in block.lines()) {
            when {
                line.startsWith("N: Name=") -> name = line.removePrefix("N: Name=").trim('"')
                line.startsWith("H: Handlers=") ->
                    node = line.removePrefix("H: Handlers=").split(' ').firstOrNull { it.startsWith("event") }
                line.startsWith("B: EV=") -> ev = line.removePrefix("B: EV=").trim().toLongOrNull(16) ?: 0
            }
        }
        node?.let { Dev(name, it, ev) }
    }
}
