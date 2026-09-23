package com.kemahub

import com.kemahub.core.SlotTable
import com.kemahub.core.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotTableTest {
    @Test
    fun newTargetsTakeTheLowestFreeSlot() {
        val t = SlotTable()
        assertEquals(1, t.ensure("A", "Desk").slot)
        assertEquals(2, t.ensure("B", "Laptop").slot)
        t.remove("A")
        assertEquals(1, t.ensure("C", "iPad").slot)
        assertEquals(2, t.ensure("B", "renamed?").slot) // known target keeps slot and name
        assertEquals("Laptop", t.get("B")!!.name)
    }

    @Test
    fun tenthTargetGetsNoSlot() {
        val t = SlotTable()
        for (i in 1..9) t.ensure("T$i", "n$i")
        assertEquals(0, t.ensure("X", "extra").slot)
        assertNull(t.addressOf(0))
        assertEquals("X", t.all.last().address)
    }

    @Test
    fun setSlotSwaps() {
        val t = SlotTable()
        t.ensure("A", "a")
        t.ensure("B", "b")
        t.setSlot("B", 1)
        assertEquals(1, t.get("B")!!.slot)
        assertEquals(2, t.get("A")!!.slot)
        assertEquals("B", t.addressOf(1))
        t.setSlot("A", 7)
        assertEquals(listOf("B", "A"), t.all.map { it.address })
    }

    @Test
    fun encodeDecodeRoundTripsAndSkipsJunk() {
        val t = SlotTable()
        t.ensure("AA:BB", "My\tDesk\n")
        t.ensure("CC:DD", "Tab")
        t.rename("CC:DD", "  ")
        val back = SlotTable.decode(t.encode() + "\nbroken line\nEE\t12\tbad slot")
        assertEquals(listOf(Target("AA:BB", 1, "My Desk"), Target("CC:DD", 2, "Tab")), back.all)
        assertEquals(emptyList<Target>(), SlotTable.decode(null).all)
    }
}
