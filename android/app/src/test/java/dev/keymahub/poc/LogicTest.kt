package dev.keymahub.poc

import dev.keymahub.poc.input.HidKeycodes
import dev.keymahub.poc.input.KeyboardReport
import dev.keymahub.poc.input.MouseReport
import dev.keymahub.poc.sink.InputSink
import dev.keymahub.poc.sink.Pipeline
import dev.keymahub.poc.wire.FrameReader
import dev.keymahub.poc.wire.Msg
import dev.keymahub.poc.wire.Wire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class WireTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun matchesRustVectors() {
        // Same vectors as core/proto `known_bytes`.
        assertArrayEquals(bytes(0x00, 0x04, 0x20, 0x00, 0x04, 0x01), Wire.encode(Msg.Key(0x04, true, false)))
        assertArrayEquals(bytes(0x00, 0x05, 0x21, 0xFF, 0xFF, 0x00, 0x02), Wire.encode(Msg.MouseMove(-1, 2)))
        assertArrayEquals(bytes(0x00, 0x05, 0x23, 0xFF, 0x88, 0x00, 0x00), Wire.encode(Msg.Wheel(-120, 0)))
    }

    @Test
    fun roundTripAndSkipUnknown() {
        val msgs = listOf(
            Msg.Hello(0, "데스크톱"), Msg.Ping(0x0102030405060708), Msg.Pong(-1), Msg.Enter, Msg.Leave,
            Msg.Key(0x90, true, true), Msg.MouseMove(-300, 32767), Msg.MouseButton(Wire.BUTTON_FORWARD, true),
            Msg.Wheel(-240, 120), Msg.ReleaseAll,
        )
        val stream = bytes(0x00, 0x03, 0x7F, 0xAA, 0xBB) + msgs.map(Wire::encode).reduce(ByteArray::plus)
        val r = FrameReader(ByteArrayInputStream(stream))
        for (m in msgs) assertEquals(m, r.next())
        assertNull(r.next())
    }
}

class HidReportTest {
    @Test
    fun keyboardTracksModifiersAndKeys() {
        val k = KeyboardReport()
        assertArrayEquals(byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0), k.update(0xE1, true))
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), k.update(0x04, true))
        assertNull(k.update(0x04, true)) // repeat
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0x90.toByte(), 0, 0, 0, 0), k.update(0x90, true))
        assertArrayEquals(byteArrayOf(0x02, 0, 0x90.toByte(), 0, 0, 0, 0, 0), k.update(0x04, false))
        assertNull(k.update(0x05, false)) // never pressed
        for (u in 0x05..0x0A) k.update(u, true)
        assertNull(k.update(0x0B, true)) // 7th key ignored
        assertArrayEquals(ByteArray(8), k.clear())
    }

    @Test
    fun mouseSplitsLargeMotion() {
        val m = MouseReport()
        val r = m.move(40000, -2)
        assertEquals(2, r.size)
        assertArrayEquals(byteArrayOf(0, 0xFF.toByte(), 0x7F, 0xFE.toByte(), 0xFF.toByte(), 0, 0), r[0])
        assertArrayEquals(byteArrayOf(0, 0x41, 0x1C, 0, 0, 0, 0), r[1]) // 40000 - 32767 = 0x1C41
    }

    @Test
    fun mouseButtonsAndWheel() {
        val m = MouseReport()
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0), m.setButton(Wire.BUTTON_LEFT, true))
        assertArrayEquals(byteArrayOf(0x11, 0, 0, 0, 0, 0, 0), m.setButton(Wire.BUTTON_FORWARD, true))
        assertNull(m.setButton(Wire.BUTTON_LEFT, true))
        assertNull(m.wheel(60, 0))
        assertArrayEquals(byteArrayOf(0x11, 0, 0, 0, 0, 1, 0), m.wheel(60, 0))
        assertArrayEquals(byteArrayOf(0x11, 0, 0, 0, 0, 0xFE.toByte(), 1), m.wheel(-240, 120))
    }
}

class PipelineTest {
    private class Recorder : InputSink {
        val events = mutableListOf<String>()
        override fun start(): String? = null
        override fun stop() {}
        override fun key(usage: Int, down: Boolean, repeat: Boolean) {
            events += "key %02X %s".format(usage, if (down) "down" else "up")
        }
        override fun move(dx: Int, dy: Int) { events += "move $dx $dy" }
        override fun button(button: Int, down: Boolean) {}
        override fun wheel(v: Int, h: Int) {}
        override fun releaseAll() {}
    }

    @Test
    fun hangulBecomesShiftSpace() {
        val r = Recorder()
        val p = Pipeline(r, HangulKey.SHIFT_SPACE, 1f)
        p.key(HidKeycodes.LANG1_HANGUL, true, false)
        p.key(HidKeycodes.LANG1_HANGUL, true, true)
        p.key(HidKeycodes.LANG1_HANGUL, false, false)
        assertEquals(listOf("key E1 down", "key 2C down", "key 2C up", "key E1 up"), r.events)
    }

    @Test
    fun hangulPassthrough() {
        val r = Recorder()
        Pipeline(r, HangulKey.PASSTHROUGH, 1f).key(HidKeycodes.LANG1_HANGUL, true, false)
        assertEquals(listOf("key 90 down"), r.events)
    }

    @Test
    fun speedKeepsSubPixelRemainder() {
        val r = Recorder()
        val p = Pipeline(r, HangulKey.SHIFT_SPACE, 0.5f)
        p.move(1, 0)
        p.move(1, 0)
        p.move(3, -3)
        assertEquals(listOf("move 1 0", "move 1 -1"), r.events)
    }
}
