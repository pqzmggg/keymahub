package com.kemahub

import com.kemahub.core.InputSink
import com.kemahub.core.Mods
import com.kemahub.core.Settings
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
    private var settings = Settings()
    private var paused = false
    private val router = SlotRouter(
        local = Rec("local", out),
        remote = Rec("remote", out),
        hotkey = { h -> if (paused) null else settings.slotFor(h) },
        isAvailable = { it in available },
        onSelect = { out += "select $it" },
        onUnavailable = { out += "unavailable $it" },
    )

    // evdev codes and HID usages used below
    private val shift = 42 to 0xE1
    private val alt = 56 to 0xE2
    private val rightShift = 54 to 0xE5
    private val rightAlt = 100 to 0xE6
    private val ctrl = 29 to 0xE0
    private val a = 30 to 0x04
    private fun digit(n: Int) = (if (n == 0) 11 else n + 1) to (if (n == 0) 0x27 else 0x1D + n)
    private val mask = "${SlotRouter.MASK_KEY}"

    /** Fixed number keys: 1 = this phone, 2..9, 0 = receivers 1..9. */
    private fun keyFor(slot: Int) = if (slot == 9) 0 else slot + 1

    private fun down(k: Pair<Int, Int>) = router.onKey(k.first, k.second, true)
    private fun up(k: Pair<Int, Int>) = router.onKey(k.first, k.second, false)

    /** Presses the hotkey of [slot] with the default Shift+Alt. */
    private fun hotkey(slot: Int, m1: Pair<Int, Int> = shift, m2: Pair<Int, Int> = alt) {
        val d = digit(keyFor(slot))
        down(m1); down(m2); down(d); up(d); up(m2); up(m1)
    }

    @Test
    fun shiftAlt2SelectsTheFirstReceiverAndSwallowsTheDigit() {
        hotkey(1)
        assertEquals(1, router.slot)
        assertTrue(router.isRemote)
        assertEquals(
            listOf(
                "local key ${0xE1} down", "local key ${0xE2} down",
                "remote enter", "select 1",
                "local key ${0xE2} up", "local key ${0xE1} up",
            ),
            out,
        )
    }

    @Test
    fun shiftAlt1ReturnsToThePhoneAfterMaskingAndReleasingTheTarget() {
        hotkey(1)
        out.clear()
        down(shift); down(alt); down(digit(1))
        assertEquals(0, router.slot)
        assertEquals(listOf("remote key ${0xE1} down", "remote key ${0xE2} down"), out.take(2))
        // An unused key between the modifiers' press and release, so Windows does not switch language.
        assertEquals(listOf("remote key $mask down", "remote key $mask up"), out.subList(2, 4))
        assertEquals(setOf("remote key ${0xE1} up", "remote key ${0xE2} up"), out.subList(4, 6).toSet())
        assertEquals(listOf("remote releaseAll", "remote leave", "select 0"), out.drop(6))
        out.clear()
        up(digit(1)); up(alt); up(shift)
        assertEquals(emptyList<String>(), out) // physical releases already delivered: swallowed
    }

    @Test
    fun noMaskWhenNothingWasSentToTheTarget() {
        hotkey(1) // from the phone: the modifiers went to the phone
        assertFalse(out.any { it.contains("key $mask") })
    }

    @Test
    fun rightModifiersWorkToo() {
        hotkey(2, rightShift, rightAlt)
        assertEquals(2, router.slot)
    }

    @Test
    fun digitsWithoutTheExactModifiersAreTyped() {
        down(shift); down(digit(2)); up(digit(2)); up(shift) // Shift+2 = "@"
        down(alt); down(digit(2)); up(digit(2)); up(alt)
        down(ctrl); down(shift); down(alt); down(digit(2)) // extra Ctrl
        assertEquals(0, router.slot)
        assertEquals(3, out.count { it == "local key ${0x1F} down" })
    }

    @Test
    fun theModifierSettingIsUsed() {
        settings = settings.setMods(Mods.CTRL or Mods.ALT)
        hotkey(1) // Shift+Alt+2 no longer switches
        assertEquals(0, router.slot)
        hotkey(1, ctrl, alt)
        assertEquals(1, router.slot)
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
        down(shift); down(alt); down(digit(3))
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
    fun hotkeysCanBePaused() {
        paused = true
        hotkey(1)
        assertEquals(0, router.slot)
        assertTrue("local key ${0x1F} down" in out)
    }
}
