package com.kemahub

import com.kemahub.core.Hotkey
import com.kemahub.core.InputSink
import com.kemahub.core.Mods
import com.kemahub.core.Profile
import com.kemahub.core.SlotRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotRouterTest {
    private class Rec(private val name: String, private val out: MutableList<String>) : InputSink {
        override fun enter() { out += "$name enter" }
        override fun leave() { out += "$name leave" }
        override fun key(usage: Int, down: Boolean) { out += "$name key $usage ${if (down) "down" else "up"}" }
        override fun move(dx: Int, dy: Int) { out += "$name move $dx $dy" }
        override fun button(button: Int, down: Boolean) { out += "$name button $button ${if (down) "down" else "up"}" }
        override fun wheel(v: Int, h: Int) { out += "$name wheel $v $h" }
        override fun releaseAll() { out += "$name releaseAll" }
    }

    private val out = mutableListOf<String>()
    private var available = setOf(1, 2)
    private var hotkeys = Profile.DEFAULT_HOTKEYS
    private val router = SlotRouter(
        local = Rec("local", out),
        remote = Rec("remote", out),
        hotkey = { h -> hotkeys.indexOf(h).takeIf { it >= 0 } },
        isAvailable = { it in available },
        onSelect = { out += "select $it" },
        onUnavailable = { out += "unavailable $it" },
    )

    // evdev codes and HID usages used below
    private val ctrl = 29 to 0xE0
    private val alt = 56 to 0xE2
    private val rightCtrl = 97 to 0xE4
    private val rightAlt = 100 to 0xE6
    private val shift = 42 to 0xE1
    private val a = 30 to 0x04
    private fun digit(n: Int) = (if (n == 0) 11 else n + 1) to (if (n == 0) 0x27 else 0x1D + n)

    /** Default hotkeys: key 1 = this phone, keys 2..9, 0 = receivers 1..9. */
    private fun keyFor(slot: Int) = if (slot == 9) 0 else slot + 1

    private fun down(k: Pair<Int, Int>) = router.onKey(k.first, k.second, true)
    private fun up(k: Pair<Int, Int>) = router.onKey(k.first, k.second, false)

    /** Presses the default hotkey of [slot]. */
    private fun hotkey(slot: Int, c: Pair<Int, Int> = ctrl, l: Pair<Int, Int> = alt) {
        val d = digit(keyFor(slot))
        down(c); down(l); down(d); up(d); up(l); up(c)
    }

    @Test
    fun ctrlAlt2SelectsTheFirstReceiverAndSwallowsTheDigit() {
        hotkey(1)
        assertEquals(1, router.slot)
        assertTrue(router.isRemote)
        assertEquals(
            listOf(
                "local key ${0xE0} down", "local key ${0xE2} down",
                "remote enter", "select 1",
                "local key ${0xE2} up", "local key ${0xE0} up",
            ),
            out,
        )
    }

    @Test
    fun ctrlAlt1ReturnsToThePhoneAfterReleasingTheTarget() {
        hotkey(1)
        out.clear()
        down(ctrl); down(alt); down(digit(1))
        assertEquals(0, router.slot)
        assertEquals(listOf("remote key ${0xE0} down", "remote key ${0xE2} down"), out.take(2))
        // held modifiers are released on the target (any order) before focus moves
        assertEquals(setOf("remote key ${0xE0} up", "remote key ${0xE2} up"), out.subList(2, 4).toSet())
        assertEquals(listOf("remote releaseAll", "remote leave", "select 0"), out.drop(4))
        out.clear()
        up(digit(1)); up(alt); up(ctrl)
        assertEquals(emptyList<String>(), out) // physical releases already delivered: swallowed
    }

    @Test
    fun rightModifiersWorkToo() {
        hotkey(2, rightCtrl, rightAlt)
        assertEquals(2, router.slot)
    }

    @Test
    fun digitsWithoutBothModifiersAreTyped() {
        down(ctrl); down(digit(1)); up(digit(1)); up(ctrl)
        down(alt); down(digit(1)); up(digit(1)); up(alt)
        assertEquals(0, router.slot)
        assertTrue(out.contains("local key ${0x1E} down"))
    }

    @Test
    fun unavailableSlotStaysPut() {
        hotkey(5)
        assertEquals(0, router.slot)
        assertTrue("unavailable 5" in out)
        assertFalse(out.any { it.startsWith("select") })
    }

    @Test
    fun switchingBetweenTargetsReleasesTheOldOne() {
        hotkey(1)
        down(a)
        out.clear()
        down(ctrl); down(alt); down(digit(3))
        assertEquals(2, router.slot)
        assertTrue("remote key ${0x04} up" in out)
        assertFalse("remote enter" in out) // still remote, only the target changes
        assertEquals("select 2", out.last())
        out.clear()
        up(a) // released on the old target already
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun keyUpFollowsKeyDownAcrossASwitch() {
        down(a) // typed on the phone
        hotkey(1)
        out.clear()
        up(a)
        assertEquals(listOf("local key ${0x04} up"), out)
        assertFalse(router.isHeldLocally(a.first))
    }

    @Test
    fun mouseFollowsFocusAndButtonsAreReleasedOnLeave() {
        router.onMove(1, 1)
        hotkey(1)
        router.onMove(2, 3)
        router.onWheel(1, 0)
        router.onButton(1, true)
        router.select(0)
        router.onButton(1, false)
        router.onMove(0, 0)
        assertEquals("local move 1 1", out.first())
        assertTrue("remote move 2 3" in out)
        assertTrue("remote wheel 1 0" in out)
        val release = out.indexOf("remote button 1 up")
        assertTrue(release in 0 until out.indexOf("select 0"))
        assertFalse("local button 1 up" in out)
    }

    @Test
    fun lostTargetReturnsToThePhone() {
        hotkey(1)
        router.targetLost(2)
        assertEquals(1, router.slot)
        router.targetLost(1)
        assertEquals(0, router.slot)
    }

    @Test
    fun ctrlAlt0IsTheNinthReceiver() {
        available = setOf(9)
        down(ctrl); down(alt); down(digit(0))
        assertEquals(9, router.slot)
    }

    @Test
    fun extraModifiersDoNotMatch() {
        down(ctrl); down(alt); down(shift); down(digit(2))
        assertEquals(0, router.slot)
        assertTrue("local key ${0x1F} down" in out)
    }

    @Test
    fun customHotkeysAndPause() {
        hotkeys = hotkeys.toMutableList().also { it[1] = Hotkey(Mods.META, 59) } // Meta+F1 = receiver 1
        down(125 to 0xE3); down(59 to 0x3A)
        assertEquals(1, router.slot)
        up(59 to 0x3A); up(125 to 0xE3)
        hotkey(0) // Ctrl+Alt+1 still returns to the phone
        assertEquals(0, router.slot)

        hotkeys = emptyList() // e.g. while a new hotkey is being recorded
        down(125 to 0xE3); down(59 to 0x3A)
        assertEquals(0, router.slot)
        assertTrue("local key ${0x3A} down" in out)
    }
}
