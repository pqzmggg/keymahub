package com.kemahub

import com.kemahub.core.Device
import com.kemahub.core.Hotkey
import com.kemahub.core.Mods
import com.kemahub.core.Profile
import com.kemahub.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    private val ctrlAlt = Mods.CTRL or Mods.ALT

    @Test
    fun defaultsAreCtrlAlt1ForThePhoneAnd2To0ForReceivers() {
        val p = Settings().active
        assertEquals(Hotkey(ctrlAlt, 2), p.hotkeys[0]) // evdev KEY_1
        assertEquals(Hotkey(ctrlAlt, 3), p.hotkeys[1]) // KEY_2
        assertEquals(Hotkey(ctrlAlt, 11), p.hotkeys[9]) // KEY_0
        assertEquals(0, p.slotFor(Hotkey(ctrlAlt, 2)))
        assertNull(p.slotFor(Hotkey(Mods.CTRL, 2)))
    }

    @Test
    fun connectedDevicesTakeTheFirstFreeReceiverSlot() {
        var s = Settings().deviceConnected("A", "Desk").deviceConnected("B", "Laptop")
        assertEquals(1, s.active.slotOf("A"))
        assertEquals(2, s.active.slotOf("B"))
        s = s.assign(s.activeId, 1, null).deviceConnected("C", "iPad")
        assertEquals(1, s.active.slotOf("C"))
        assertEquals(s, s.deviceConnected("B", "renamed?")) // known and placed: nothing changes
        assertEquals(listOf("Desk", "Laptop", "iPad"), s.devices.map { it.name })
    }

    @Test
    fun fullProfileRemembersTheDeviceWithoutASlot() {
        var s = Settings()
        for (i in 1..9) s = s.deviceConnected("T$i", "n$i")
        s = s.deviceConnected("X", "extra")
        assertNull(s.active.slotOf("X"))
        assertTrue(s.device("X") != null)
    }

    @Test
    fun assignMovesAndSwaps() {
        var s = Settings().deviceConnected("A", "a").deviceConnected("B", "b")
        val id = s.activeId
        s = s.assign(id, 2, "A") // A was on 1, B on 2: they swap
        assertEquals("A", s.active.addressOf(2))
        assertEquals("B", s.active.addressOf(1))
        s = s.assign(id, 5, "B") // 5 was empty: B just moves
        assertEquals("B", s.active.addressOf(5))
        assertNull(s.active.addressOf(1))
    }

    @Test
    fun setHotkeySwapsOnConflictAndRejectsInvalidChords() {
        var s = Settings()
        val id = s.activeId
        s = s.setHotkey(id, 1, Hotkey(ctrlAlt, 2)) // the phone's chord: swap
        assertEquals(Hotkey(ctrlAlt, 2), s.active.hotkeys[1])
        assertEquals(Hotkey(ctrlAlt, 3), s.active.hotkeys[0])
        assertEquals(s, s.setHotkey(id, 3, Hotkey(Mods.SHIFT, 30))) // Shift alone
        assertEquals(s, s.setHotkey(id, 3, Hotkey(Mods.CTRL, 29))) // modifier as key
        assertFalse(Hotkey(0, 30).isValid)
        s = s.setHotkey(id, 3, Hotkey(Mods.META, 59))
        assertEquals(3, s.active.slotFor(Hotkey(Mods.META, 59)))
        assertEquals(Profile.DEFAULT_HOTKEYS, s.resetHotkeys(id).active.hotkeys)
    }

    @Test
    fun profilesCopySelectAndDelete() {
        var s = Settings().deviceConnected("A", "Desk")
        val first = s.activeId
        s = s.addProfile("Home")
        val home = s.activeId
        assertTrue(home != first)
        assertEquals("A", s.active.addressOf(1)) // copied
        s = s.assign(home, 1, null).deviceConnected("B", "Home PC")
        assertEquals("B", s.active.addressOf(1))
        assertEquals("A", s.select(first).active.addressOf(1)) // the other profile is untouched
        assertNull(s.profile(first)!!.slotOf("B")) // devices land only in the active profile

        s = s.renameProfile(home, "  Home\tPC ").deleteProfile(first)
        assertEquals(listOf("Home PC"), s.profiles.map { it.name })
        assertEquals(s, s.deleteProfile(home)) // the last one stays
    }

    @Test
    fun forgetRemovesTheDeviceEverywhere() {
        var s = Settings().deviceConnected("A", "Desk").addProfile("Two")
        s = s.forgetDevice("A")
        assertTrue(s.devices.isEmpty())
        assertTrue(s.profiles.all { it.receivers.isEmpty() })
    }

    @Test
    fun encodeDecodeRoundTrips() {
        var s = Settings().deviceConnected("AA:BB", "My\tDesk\n").deviceConnected("CC:DD", "Tab")
        s = s.renameDevice("CC:DD", "  ") // blank names are ignored
        s = s.setHotkey(s.activeId, 2, Hotkey(Mods.META or Mods.SHIFT, 59))
        s = s.addProfile("Office").assign("p2", 1, null)
        val back = Settings.decode(s.encode())
        assertEquals(s, back)
        assertEquals(listOf(Device("AA:BB", "My Desk"), Device("CC:DD", "Tab")), back.devices)
        assertEquals("p2", back.activeId)
    }

    @Test
    fun decodeToleratesJunk() {
        assertEquals(Settings(), Settings.decode(null))
        assertEquals(Settings(), Settings.decode("garbage\nprofile\t\tx"))
        val s = Settings.decode(
            "active\tnope\ndevice\tA\tDesk\nprofile\tp1\tWork\t1:2,oops\t1=A,2=GONE,12=A\n",
        )
        assertEquals("p1", s.activeId)
        assertEquals(Profile.DEFAULT_HOTKEYS, s.active.hotkeys)
        assertEquals(mapOf(1 to "A"), s.active.receivers)
    }
}
