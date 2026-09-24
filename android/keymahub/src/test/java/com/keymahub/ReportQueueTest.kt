package com.keymahub

import com.keymahub.ble.ReportQueue
import com.keymahub.hid.HidDescriptors.REPORT_ID_KEYBOARD
import com.keymahub.hid.HidDescriptors.REPORT_ID_MOUSE
import com.keymahub.hid.MouseReport
import com.keymahub.core.Buttons
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportQueueTest {
    private val mouse = MouseReport()
    private fun move(dx: Int, dy: Int) = mouse.move(dx, dy).single()
    private fun key(usage: Int) = byteArrayOf(0, 0, usage.toByte(), 0, 0, 0, 0, 0)

    private fun drain(q: ReportQueue) = generateSequence { q.poll() }.map { it.reportId to it.data.toList() }.toList()

    @Test
    fun consecutiveMotionMerges() {
        val q = ReportQueue()
        repeat(10) { q.push(REPORT_ID_MOUSE, move(3, -2)) }
        assertEquals(listOf(REPORT_ID_MOUSE to move(30, -20).toList()), drain(q))
    }

    @Test
    fun keysAndButtonsKeepOrderAndSplitMotion() {
        val q = ReportQueue()
        q.push(REPORT_ID_MOUSE, move(5, 0))
        q.push(REPORT_ID_KEYBOARD, key(0x04))
        q.push(REPORT_ID_MOUSE, move(1, 0))
        val press = mouse.setButton(Buttons.LEFT, true)!!
        q.push(REPORT_ID_MOUSE, press)
        q.push(REPORT_ID_MOUSE, move(2, 2)) // dragging: must not merge into the press itself
        q.push(REPORT_ID_MOUSE, move(2, 2))
        val release = mouse.setButton(Buttons.LEFT, false)!!
        q.push(REPORT_ID_MOUSE, release)
        q.push(REPORT_ID_MOUSE, move(7, 7)) // after the release: must not merge into it either
        assertEquals(
            listOf(
                REPORT_ID_MOUSE to listOf<Byte>(0, 5, 0, 0, 0, 0, 0),
                REPORT_ID_KEYBOARD to key(0x04).toList(),
                REPORT_ID_MOUSE to listOf<Byte>(0, 1, 0, 0, 0, 0, 0),
                REPORT_ID_MOUSE to listOf<Byte>(1, 0, 0, 0, 0, 0, 0), // press where the cursor was
                REPORT_ID_MOUSE to listOf<Byte>(1, 4, 0, 4, 0, 0, 0),
                REPORT_ID_MOUSE to listOf<Byte>(0, 0, 0, 0, 0, 0, 0), // release
                REPORT_ID_MOUSE to listOf<Byte>(0, 7, 0, 7, 0, 0, 0),
            ),
            drain(q),
        )
        assertEquals(press.toList(), listOf<Byte>(1, 0, 0, 0, 0, 0, 0))
        assertEquals(release.toList(), listOf<Byte>(0, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun keyboardIsNeverDroppedAndMotionBacklogCollapses() {
        val q = ReportQueue(motionCapacity = 4)
        repeat(100) {
            q.push(REPORT_ID_KEYBOARD, key(0x04))
            q.push(REPORT_ID_MOUSE, move(1, 1))
        }
        val items = drain(q)
        assertEquals(100, items.count { it.first == REPORT_ID_KEYBOARD })
        val motion = items.filter { it.first == REPORT_ID_MOUSE }.sumOf { it.second[1].toInt() }
        assertEquals(100, motion) // all movement delivered, in few reports
    }

    @Test
    fun fullQueueNeverMovesMotionAcrossAClick() {
        val q = ReportQueue(motionCapacity = 3)
        q.push(REPORT_ID_MOUSE, move(1, 0))
        q.push(REPORT_ID_MOUSE, mouse.setButton(Buttons.LEFT, true)!!)
        q.push(REPORT_ID_MOUSE, mouse.setButton(Buttons.LEFT, false)!!)
        q.push(REPORT_ID_KEYBOARD, key(0x04))
        q.push(REPORT_ID_MOUSE, move(9, 0)) // full: may not merge into the first move
        val items = drain(q)
        assertEquals(listOf<Byte>(0, 1, 0, 0, 0, 0, 0), items.first().second)
        assertEquals(listOf<Byte>(0, 9, 0, 0, 0, 0, 0), items.last().second)
    }

    @Test
    fun wheelIsNotMergedAndUnpollRestoresOrder() {
        val q = ReportQueue()
        val wheel = mouse.wheel(120, 0)!!
        q.push(REPORT_ID_MOUSE, wheel)
        q.push(REPORT_ID_MOUSE, move(1, 0))
        val first = q.poll()!!
        assertArrayEquals(wheel, first.data)
        q.unpoll(first)
        assertEquals(2, q.size)
        assertArrayEquals(wheel, q.poll()!!.data)
        assertArrayEquals(move(1, 0), q.poll()!!.data)
        assertNull(q.poll())
    }
}
