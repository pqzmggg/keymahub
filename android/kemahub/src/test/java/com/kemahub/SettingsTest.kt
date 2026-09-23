package com.kemahub

import com.kemahub.core.ActivationMode
import com.kemahub.core.Device
import com.kemahub.core.Hotkey
import com.kemahub.core.Hotkeys
import com.kemahub.core.Mods
import com.kemahub.core.Profile
import com.kemahub.core.Settings
import com.kemahub.core.TimeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    private val none = emptySet<String>()
    private val ctrlAlt = Mods.CTRL or Mods.ALT

    @Test
    fun hotkeysAreShiftAltPlusFixedNumbers() {
        val s = Settings()
        val shiftAlt = Mods.SHIFT or Mods.ALT
        assertEquals(Hotkey(shiftAlt, 2), s.hotkey(0)) // evdev KEY_1: this phone
        assertEquals(Hotkey(shiftAlt, 3), s.hotkey(1)) // KEY_2: receiver 1
        assertEquals(Hotkey(shiftAlt, 11), s.hotkey(9)) // KEY_0: receiver 9
        assertEquals(0, s.slotFor(Hotkey(shiftAlt, 2)))
        assertEquals(9, s.slotFor(Hotkey(shiftAlt, 11)))
        assertNull(s.slotFor(Hotkey(Mods.SHIFT, 2)))
        assertNull(s.slotFor(Hotkey(shiftAlt, 30))) // not a number key
    }

    @Test
    fun onlyTheModifiersChange() {
        var s = Settings()
        s = s.setMods(Mods.CTRL or Mods.META)
        assertEquals(Hotkey(Mods.CTRL or Mods.META, 4), s.hotkey(2))
        assertEquals(s, s.setMods(Mods.SHIFT)) // Shift alone would steal typing
        assertEquals(s, s.setMods(0))
        assertEquals(s, s.setMods(0x10))
        assertTrue(Hotkeys.validMods(Mods.ALT))
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
    fun newProfilesGoOnTopAsACopyWithoutConditions() {
        var s = Settings(mode = ActivationMode.RULES).deviceConnected("A", "Desk")
        val first = s.activeId
        s = s.setTime(first, TimeRule())
        val (next, home) = s.addProfile("Home")
        s = next
        assertEquals(listOf(home, first), s.profiles.map { it.id })
        assertEquals("A", s.profile(home)!!.addressOf(1)) // copied receivers
        assertNull(s.profile(home)!!.time) // but not the conditions
        assertEquals(first, s.resolve(1, 12 * 60, none).activeId) // no conditions: not used until activated
        assertEquals(home, s.resolve(1, 12 * 60, none).activate(home).resolve(1, 12 * 60, none).activeId) // overrides the match
    }

    @Test
    fun renameAndDeleteProfiles() {
        var s = Settings()
        val first = s.activeId
        val home = s.addProfile("Home").second
        s = s.addProfile("Home").first.renameProfile(home, "  Home\tPC ").activate(home).deleteProfile(first)
        assertEquals(listOf("Home PC"), s.profiles.map { it.name })
        assertEquals(s, s.deleteProfile(home)) // the last one stays
        val (more, x) = s.addProfile("x")
        s = more.activate(x).deleteProfile(x)
        assertEquals(home, s.chosenId) // a deleted choice falls back to what is left
    }

    @Test
    fun devicesLandOnlyInTheActiveProfile() {
        var s = Settings()
        val first = s.activeId
        val (next, office) = s.addProfile("Office")
        s = next.activate(office).deviceConnected("B", "Office PC")
        assertEquals(1, s.profile(office)!!.slotOf("B"))
        assertNull(s.profile(first)!!.slotOf("B"))
    }

    @Test
    fun conditionsByPriorityElseTheLastActivated() {
        var s = Settings(mode = ActivationMode.RULES).deviceConnected("OFFICE", "Office PC").deviceConnected("HOME", "Home PC")
        val home = s.activeId // no conditions
        var work = ""
        var late = ""
        s = s.addProfile("Work").let { (n, id) -> work = id; n }
            .setTime(work, TimeRule(TimeRule.WEEKDAYS, 9 * 60, 18 * 60))
            .setWhenConnected(work, setOf("OFFICE"))
        s = s.addProfile("Late").let { (n, id) -> late = id; n }
            .setTime(late, TimeRule(TimeRule.ALL, 17 * 60, 17 * 60 + 30))
        // order: Late, Work, Home
        val office = setOf("OFFICE")
        assertEquals(work, s.resolve(1, 10 * 60, office).activeId) // Monday 10:00, office PC connected
        assertEquals(home, s.resolve(1, 10 * 60, setOf("HOME")).activeId) // another device: last activated
        assertEquals(home, s.resolve(1, 10 * 60, none).activeId) // nothing connected
        assertEquals(home, s.resolve(6, 10 * 60, office).activeId) // Saturday
        assertEquals(late, s.resolve(1, 17 * 60 + 10, office).activeId) // both match: higher wins
        s = s.moveProfile(late, 1) // Work above Late now
        assertEquals(work, s.resolve(1, 17 * 60 + 10, office).activeId)
        assertEquals(listOf(work, late, home), s.profiles.map { it.id })
        assertEquals(s, s.moveProfile(work, -1)) // already on top

        // The fallback is whatever was activated last, conditions or not.
        s = s.activate(late).resolve(6, 10 * 60, none)
        assertEquals(late, s.activeId)
    }

    @Test
    fun anyChosenDeviceMatchesAndForgottenDevicesDrop() {
        var s = Settings().deviceConnected("A", "Desk").deviceConnected("B", "Laptop")
        val id = s.activeId
        s = s.setWhenConnected(id, setOf("A", "B", "UNKNOWN"))
        assertEquals(setOf("A", "B"), s.active.whenConnected) // unknown devices are not kept
        assertTrue(s.active.matches(1, 0, setOf("B")))
        assertFalse(s.active.matches(1, 0, none))
        s = s.forgetDevice("A")
        assertEquals(setOf("B"), s.active.whenConnected)
    }

    @Test
    fun fullyAutomaticPicksTheProfileWithTheMostConnectedDevices() {
        var s = Settings().deviceConnected("A", "Desk").deviceConnected("B", "Laptop").deviceConnected("C", "iPad")
        assertEquals(ActivationMode.AUTO, s.mode) // the default
        val home = s.activeId // A=1, B=2, C=3
        var office = ""
        s = s.addProfile("Office").let { (n, id) -> office = id; n }
            .assign(office, 3, null) // Office: A, B (and it is on top)
        s = s.resolve(1, 0, setOf("A", "B"))
        assertEquals(office, s.activeId) // tie 2-2: the higher profile wins
        assertEquals(home, s.resolve(1, 0, setOf("A", "B", "C")).activeId) // 3 beats 2
        assertEquals(office, s.resolve(1, 0, setOf("A")).activeId) // tie 1-1
        // Nothing connected: the last activated one.
        assertEquals(home, s.activate(home).resolve(1, 0, none).activeId)
        // Conditions do not matter in this mode.
        val timed = s.setTime(office, TimeRule(TimeRule.WEEKEND)).resolve(1, 0, setOf("A", "B"))
        assertEquals(office, timed.activeId)
    }

    @Test
    fun manualIgnoresEverythingButTheChoice() {
        var s = Settings(mode = ActivationMode.MANUAL).deviceConnected("A", "Desk")
        val first = s.activeId
        val (next, other) = s.addProfile("Other")
        s = next.setWhenConnected(other, setOf("A")).setTime(other, TimeRule(TimeRule.ALL, 0, 0))
        assertEquals(first, s.resolve(1, 0, setOf("A")).activeId)
        assertEquals(other, s.activate(other).resolve(1, 0, none).activeId)
        // Switching modes drops a pending override and re-picks.
        val auto = s.activate(first).setMode(ActivationMode.RULES).resolve(1, 0, setOf("A"))
        assertEquals(other, auto.activeId)
    }

    @Test
    fun activatingOverridesTheMatchUntilItChanges() {
        var s = Settings(mode = ActivationMode.RULES)
        val home = s.activeId
        var work = ""
        s = s.addProfile("Work").let { (n, id) -> work = id; n }.setTime(work, TimeRule(TimeRule.ALL, 9 * 60, 18 * 60))
        s = s.resolve(1, 10 * 60, none)
        assertEquals(work, s.activeId)

        s = s.activate(home).resolve(1, 11 * 60, none) // chosen by hand while Work matches
        assertEquals(home, s.activeId)
        assertEquals(work, s.overriddenId)
        assertEquals(work, s.automatic().resolve(1, 11 * 60, none).activeId) // "automatic" gives it back

        s = s.resolve(1, 19 * 60, none) // Work stops matching: the override ends
        assertEquals(home, s.activeId)
        assertNull(s.overriddenId)
        assertEquals(work, s.resolve(2, 10 * 60, none).activeId) // next morning Work applies again

        // Activating the matching profile itself is no override.
        val t = Settings(mode = ActivationMode.RULES).let { base -> base.setTime(base.activeId, TimeRule(TimeRule.ALL, 0, 0)) }.resolve(1, 0, none)
        assertNull(t.activate(t.activeId).overriddenId)
    }

    @Test
    fun timeRules() {
        val night = TimeRule(1, 22 * 60, 6 * 60) // Monday night into Tuesday morning
        assertTrue(night.matches(1, 23 * 60))
        assertTrue(night.matches(2, 5 * 60)) // after midnight still counts for Monday
        assertFalse(night.matches(2, 23 * 60))
        assertFalse(night.matches(1, 5 * 60)) // Sunday night was not selected
        val sundayNight = TimeRule(1 shl 6, 22 * 60, 2 * 60)
        assertTrue(sundayNight.matches(1, 60)) // wraps from Sunday to Monday
        val allDay = TimeRule(TimeRule.WEEKEND, 0, 0)
        assertTrue(allDay.matches(7, 0) && allDay.matches(6, 23 * 60 + 59))
        assertFalse(allDay.matches(5, 12 * 60))
        assertEquals(TimeRule.WEEKDAYS or TimeRule.WEEKEND, TimeRule.ALL)
        assertFalse(TimeRule(0).isValid)
        assertEquals(s0, s0.setTime("p1", TimeRule(0)))
    }

    private val s0 = Settings()

    @Test
    fun devicesRememberUseAndBlocking() {
        var s = Settings().deviceConnected("A", "Desk").touch("A", 1234L).setBlocked("A", true)
        assertEquals(Device("A", "Desk", 1234L, true), s.device("A"))
        s = s.setBlocked("A", false)
        assertFalse(s.device("A")!!.blocked)
    }

    @Test
    fun forgetRemovesTheDeviceEverywhere() {
        var s = Settings().deviceConnected("A", "Desk").addProfile("Two").first
        s = s.forgetDevice("A")
        assertTrue(s.devices.isEmpty())
        assertTrue(s.profiles.all { it.receivers.isEmpty() })
    }

    @Test
    fun encodeDecodeRoundTrips() {
        var s = Settings().deviceConnected("AA:BB", "My\tDesk\n").deviceConnected("CC:DD", "Tab")
        s = s.renameDevice("CC:DD", "  ") // blank names are ignored
        s = s.touch("AA:BB", 1_700_000_000_000L).setBlocked("CC:DD", true)
        s = s.setMods(Mods.META or Mods.SHIFT)
        val (next, office) = s.addProfile("Office")
        s = next.setMode(ActivationMode.RULES).assign(office, 1, null)
            .setTime(office, TimeRule(0b0000101, 22 * 60, 6 * 60))
            .setWhenConnected(office, setOf("AA:BB", "CC:DD"))
            .activate(office)
        val back = Settings.decode(s.encode())
        assertEquals(s, back)
        assertEquals(listOf(Device("AA:BB", "My Desk", 1_700_000_000_000L), Device("CC:DD", "Tab", 0, true)), back.devices)
        assertEquals(office, back.activeId)
        assertEquals(setOf("AA:BB", "CC:DD"), back.profile(office)!!.whenConnected)
    }

    @Test
    fun decodeToleratesJunk() {
        assertEquals(Settings(), Settings.decode(null))
        assertEquals(Settings(), Settings.decode("garbage\nprofile\t\tx"))
        val s = Settings.decode(
            "active\tnope\ndevice\tA\tDesk\nprofile\tp1\tWork\t1:2,oops\t1=A,2=GONE,12=A\n",
        )
        assertEquals("p1", s.activeId)
        assertEquals(Hotkeys.DEFAULT_MODS, s.mods)
        assertEquals(mapOf(1 to "A"), s.active.receivers)
    }
}
