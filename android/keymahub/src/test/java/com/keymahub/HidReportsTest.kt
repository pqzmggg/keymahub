package com.keymahub

import com.keymahub.hid.ConsumerReport
import com.keymahub.hid.HidDescriptors
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
}
