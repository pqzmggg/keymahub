package dev.keymanc.poc.wire

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * PoC wire v0 — mirror of `core/proto` (Rust). Frame: `u16 len | u8 type | payload`, big endian.
 * Unknown types are skipped.
 */
sealed interface Msg {
    data class Hello(val version: Int, val name: String) : Msg
    data class Ping(val tUs: Long) : Msg
    data class Pong(val tUs: Long) : Msg
    data object Enter : Msg
    data object Leave : Msg
    data class Key(val usage: Int, val down: Boolean, val repeat: Boolean) : Msg
    data class MouseMove(val dx: Int, val dy: Int) : Msg
    data class MouseButton(val button: Int, val down: Boolean) : Msg
    /** 120 = one notch. Positive [v] = scroll up (away from user), positive [h] = right. */
    data class Wheel(val v: Int, val h: Int) : Msg
    data object ReleaseAll : Msg
}

object Wire {
    const val DEFAULT_PORT = 45877
    const val VERSION = 0
    const val WHEEL_NOTCH = 120
    const val MAX_FRAME = 1024

    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_MIDDLE = 3
    const val BUTTON_BACK = 4
    const val BUTTON_FORWARD = 5

    private const val HELLO = 0x01
    private const val PING = 0x02
    private const val PONG = 0x03
    private const val ENTER = 0x10
    private const val LEAVE = 0x11
    private const val KEY = 0x20
    private const val MOUSE_MOVE = 0x21
    private const val MOUSE_BUTTON = 0x22
    private const val WHEEL = 0x23
    private const val RELEASE_ALL = 0x24

    /** Returns null for unknown message types. */
    fun decodeBody(body: ByteArray, len: Int = body.size): Msg? {
        if (len < 1) throw IOException("empty frame")
        val b = ByteBuffer.wrap(body, 1, len - 1)
        try {
            return when (body[0].toInt() and 0xFF) {
                HELLO -> {
                    val version = b.short.toInt() and 0xFFFF
                    val n = b.get().toInt() and 0xFF
                    val name = ByteArray(n).also { b.get(it) }
                    Msg.Hello(version, String(name, Charsets.UTF_8))
                }
                PING -> Msg.Ping(b.long)
                PONG -> Msg.Pong(b.long)
                ENTER -> Msg.Enter
                LEAVE -> Msg.Leave
                KEY -> {
                    val usage = b.short.toInt() and 0xFFFF
                    val flags = b.get().toInt()
                    Msg.Key(usage, flags and 0x01 != 0, flags and 0x02 != 0)
                }
                MOUSE_MOVE -> Msg.MouseMove(b.short.toInt(), b.short.toInt())
                MOUSE_BUTTON -> {
                    val button = b.get().toInt() and 0xFF
                    if (button !in BUTTON_LEFT..BUTTON_FORWARD) throw IOException("bad button $button")
                    Msg.MouseButton(button, b.get().toInt() != 0)
                }
                WHEEL -> Msg.Wheel(b.short.toInt(), b.short.toInt())
                RELEASE_ALL -> Msg.ReleaseAll
                else -> null
            }
        } catch (e: java.nio.BufferUnderflowException) {
            throw IOException("truncated frame", e)
        }
    }

    fun encode(m: Msg): ByteArray {
        val b = ByteBuffer.allocate(3 + 255 + 3)
        b.position(2)
        when (m) {
            is Msg.Hello -> {
                b.put(HELLO.toByte()).putShort(m.version.toShort())
                var name = m.name.toByteArray(Charsets.UTF_8)
                if (name.size > 255) name = name.copyOf(255)
                b.put(name.size.toByte()).put(name)
            }
            is Msg.Ping -> b.put(PING.toByte()).putLong(m.tUs)
            is Msg.Pong -> b.put(PONG.toByte()).putLong(m.tUs)
            Msg.Enter -> b.put(ENTER.toByte())
            Msg.Leave -> b.put(LEAVE.toByte())
            is Msg.Key -> {
                val flags = (if (m.down) 1 else 0) or (if (m.repeat) 2 else 0)
                b.put(KEY.toByte()).putShort(m.usage.toShort()).put(flags.toByte())
            }
            is Msg.MouseMove -> b.put(MOUSE_MOVE.toByte()).putShort(m.dx.toShort()).putShort(m.dy.toShort())
            is Msg.MouseButton -> b.put(MOUSE_BUTTON.toByte()).put(m.button.toByte()).put(if (m.down) 1 else 0)
            is Msg.Wheel -> b.put(WHEEL.toByte()).putShort(m.v.toShort()).putShort(m.h.toShort())
            Msg.ReleaseAll -> b.put(RELEASE_ALL.toByte())
        }
        val end = b.position()
        b.putShort(0, (end - 2).toShort())
        return b.array().copyOf(end)
    }
}

class FrameReader(input: InputStream) {
    private val din = DataInputStream(input)
    private val buf = ByteArray(Wire.MAX_FRAME)

    /** Next known message, or null on clean EOF. */
    fun next(): Msg? {
        while (true) {
            val len = try {
                din.readUnsignedShort()
            } catch (_: EOFException) {
                return null
            }
            if (len > Wire.MAX_FRAME) throw IOException("frame too long: $len")
            din.readFully(buf, 0, len)
            Wire.decodeBody(buf, len)?.let { return it }
        }
    }
}

fun OutputStream.writeMsg(m: Msg) {
    write(Wire.encode(m))
    flush()
}
