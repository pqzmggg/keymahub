package app.keymahub

import app.keymahub.hid.ConsumerReport
import app.keymahub.hid.HidDescriptors
import app.keymahub.hid.KeyboardReport
import app.keymahub.hid.MouseReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidReportsTest {
    @Test
    fun consumerReportHoldsOneUsageLittleEndian() {
        val r = ConsumerReport()
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0), r.update(0xE9, true)) // volume up
        assertNull(r.update(0xE9, true)) // repeat
        assertNull(r.update(0xEA, false)) // release of a key that is not held
        assertArrayEquals(byteArrayOf(0x21, 0x02), r.update(0x221, true)) // search replaces it
        assertArrayEquals(byteArrayOf(0, 0), r.update(0x221, false))
        r.update(0xCD, true)
        assertArrayEquals(byteArrayOf(0, 0), r.clear())
    }

    @Test
    fun comboHasThreeReportIds() {
        val d = HidDescriptors.COMBO
        val ids = (0 until d.size - 1).filter { d[it] == 0x85.toByte() }.map { d[it + 1].toInt() }
        assertEquals(listOf(HidDescriptors.REPORT_ID_KEYBOARD, HidDescriptors.REPORT_ID_MOUSE, HidDescriptors.REPORT_ID_CONSUMER), ids)
    }

    @Test
    fun keyboardReportHoldsModifiersAndSixKeys() {
        val r = KeyboardReport()
        assertArrayEquals(byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0), r.update(0xE1, true)) // left shift
        assertNull(r.update(0xE1, true)) // repeat
        for (k in 4..9) r.update(k, true) // a..f
        assertNull(r.update(10, true)) // a 7th key does not fit
        assertArrayEquals(byteArrayOf(0x02, 0, 5, 6, 7, 8, 9, 0), r.update(4, false))
        assertNull(r.update(4, false)) // not held any more
        assertArrayEquals(ByteArray(8), r.clear())
    }

    @Test
    fun mouseReportSplitsLargeMotionAndAccumulatesWheel() {
        val r = MouseReport()
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0), r.setButton(1, true))
        assertNull(r.setButton(1, true))
        assertNull(r.setButton(6, true)) // no such button
        val moves = r.move(40000, -2)
        assertEquals(2, moves.size)
        assertArrayEquals(byteArrayOf(0x01, 0xFF.toByte(), 0x7F, 0xFE.toByte(), 0xFF.toByte(), 0, 0), moves[0]) // 32767, -2
        assertArrayEquals(byteArrayOf(0x01, 0x41, 0x1C, 0, 0, 0, 0), moves[1]) // 7233, 0
        assertNull(r.wheel(60, 0)) // half a notch
        assertArrayEquals(byteArrayOf(0x01, 0, 0, 0, 0, 1, 0), r.wheel(60, 0))
        assertArrayEquals(ByteArray(7), r.clear())
    }
}
