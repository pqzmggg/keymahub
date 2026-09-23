package dev.keymanc.poc.bt

import dev.keymanc.poc.input.HidDescriptors

/**
 * Reports waiting for a slow link (BLE sends only a few per connection interval).
 *
 * - Keyboard reports and button changes are never dropped or merged: each is a state
 *   transition, and losing one means a missed or stuck key.
 * - Motion-only mouse reports are merged into the queued mouse report before them, so a
 *   backlog turns into one bigger movement instead of latency (or dropped input).
 */
class ReportQueue(private val motionCapacity: Int = 32) {
    /** [mergeable]: pure motion with unchanged buttons; a button press/release is never merged into. */
    class Item(val reportId: Int, val data: ByteArray, val mergeable: Boolean = false)

    private val q = ArrayDeque<Item>()
    private var lastButtons = 0

    val size get() = q.size

    fun push(reportId: Int, data: ByteArray) {
        if (reportId != HidDescriptors.REPORT_ID_MOUSE) {
            q.addLast(Item(reportId, data.copyOf()))
            return
        }
        val buttons = data[0].toInt()
        val motion = isMotionOnly(data) && buttons == lastButtons
        lastButtons = buttons
        if (motion) {
            // Merge with the tail when possible (keeps ordering). When the queue is full, merge
            // into any earlier motion since the last button change: moving motion ahead of key
            // reports is harmless, moving it across a click would shift where the click lands.
            val tail = q.lastOrNull()
            if (tail != null && mergeInto(tail, data)) return
            if (q.size >= motionCapacity) {
                for (item in q.asReversed()) {
                    if (item.reportId != HidDescriptors.REPORT_ID_MOUSE) continue
                    if (!item.mergeable) break
                    if (mergeInto(item, data)) return
                }
            }
        }
        q.addLast(Item(reportId, data.copyOf(), mergeable = motion))
    }

    fun poll(): Item? = q.removeFirstOrNull()

    /** Puts back an item that could not be sent yet. */
    fun unpoll(item: Item) = q.addFirst(item)

    fun clear() = q.clear()

    private fun mergeInto(item: Item, add: ByteArray): Boolean {
        val d = item.data
        if (!item.mergeable || d[0] != add[0]) return false
        val x = s16(d, 1) + s16(add, 1)
        val y = s16(d, 3) + s16(add, 3)
        if (x !in -32767..32767 || y !in -32767..32767) return false
        put16(d, 1, x)
        put16(d, 3, y)
        return true
    }

    companion object {
        /** Mouse report layout: buttons, x (s16 LE), y (s16 LE), wheel, pan. */
        fun isMotionOnly(r: ByteArray) = r.size >= 7 && r[5].toInt() == 0 && r[6].toInt() == 0

        private fun s16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()

        private fun put16(b: ByteArray, i: Int, v: Int) {
            b[i] = (v and 0xFF).toByte()
            b[i + 1] = (v shr 8).toByte()
        }
    }
}
