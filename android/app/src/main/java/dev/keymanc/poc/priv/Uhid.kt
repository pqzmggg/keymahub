package dev.keymanc.poc.priv

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A virtual HID device through `/dev/uhid` (the shell user may open it; this is how
 * scrcpy's UHID keyboard/mouse work). Layout follows `struct uhid_event` in linux/uhid.h.
 */
class UhidDevice(name: String, descriptor: ByteArray, vendor: Int, product: Int) {
    private val fd: FileDescriptor = Os.open("/dev/uhid", OsConstants.O_RDWR, 0)
    @Volatile private var closed = false

    init {
        val b = ByteBuffer.allocate(280 + descriptor.size).order(ByteOrder.nativeOrder())
        b.putInt(UHID_CREATE2)
        b.put(fixed(name, 128))
        b.put(fixed("keymanc", 64)) // phys
        b.put(fixed("", 64))        // uniq
        b.putShort(descriptor.size.toShort())
        b.putShort(BUS_VIRTUAL)
        b.putInt(vendor)
        b.putInt(product)
        b.putInt(0) // version
        b.putInt(0) // country
        b.put(descriptor)
        write(b.array())
        // The kernel queues START/OPEN/OUTPUT (LED) events for us; drain them so the
        // queue never fills up.
        Thread({
            val buf = ByteArray(UHID_EVENT_SIZE)
            try {
                while (!closed) Os.read(fd, buf, 0, buf.size)
            } catch (_: Exception) {
            }
        }, "uhid-reader").apply { isDaemon = true }.start()
    }

    fun input(report: ByteArray) {
        val b = ByteBuffer.allocate(6 + report.size).order(ByteOrder.nativeOrder())
        b.putInt(UHID_INPUT2)
        b.putShort(report.size.toShort())
        b.put(report)
        write(b.array())
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            write(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putInt(UHID_DESTROY).array())
        } finally {
            Os.close(fd)
        }
    }

    private fun write(bytes: ByteArray) {
        val n = Os.write(fd, bytes, 0, bytes.size)
        if (n != bytes.size) error("short uhid write $n/${bytes.size}")
    }

    private fun fixed(s: String, size: Int): ByteArray {
        val out = ByteArray(size)
        val src = s.toByteArray(Charsets.UTF_8)
        System.arraycopy(src, 0, out, 0, minOf(src.size, size - 1))
        return out
    }

    private companion object {
        const val UHID_DESTROY = 1
        const val UHID_CREATE2 = 11
        const val UHID_INPUT2 = 12
        const val BUS_VIRTUAL: Short = 0x06
        const val UHID_EVENT_SIZE = 4380
    }
}
