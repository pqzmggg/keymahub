package com.kemahub

import com.kemahub.core.Device
import com.kemahub.core.GeoPoint
import com.kemahub.core.Hotkey
import com.kemahub.core.Mods
import com.kemahub.core.PlaceRule
import com.kemahub.core.Profile
import com.kemahub.core.Settings
import com.kemahub.core.TimeRule
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
    fun newProfilesGoOnTopAsACopyWithoutConditions() {
        var s = Settings().deviceConnected("A", "Desk")
        val first = s.activeId
        s = s.setTime(first, TimeRule())
        val (next, home) = s.addProfile("Home")
        s = next
        assertEquals(listOf(home, first), s.profiles.map { it.id })
        assertEquals("A", s.profile(home)!!.addressOf(1)) // copied receivers
        assertNull(s.profile(home)!!.time) // but not the conditions
        assertEquals(first, s.resolve(1, 12 * 60, null).activeId) // no conditions: not used until activated
        assertEquals(home, s.resolve(1, 12 * 60, null).activate(home).resolve(1, 12 * 60, null).activeId) // overrides the match
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
        val office = PlaceRule(37.5665, 126.9780, 200, "Office")
        var s = Settings()
        val home = s.activeId // no conditions
        var work = ""
        var late = ""
        s = s.addProfile("Work").let { (n, id) -> work = id; n }
            .setTime(work, TimeRule(TimeRule.WEEKDAYS, 9 * 60, 18 * 60))
            .setPlace(work, office)
        s = s.addProfile("Late").let { (n, id) -> late = id; n }
            .setTime(late, TimeRule(TimeRule.ALL, 17 * 60, 17 * 60 + 30))
        // order: Late, Work, Home
        val atOffice = GeoPoint(37.5666, 126.9781, 20f)
        val elsewhere = GeoPoint(37.60, 127.00, 20f)
        assertEquals(work, s.resolve(1, 10 * 60, atOffice).activeId) // Monday 10:00 at the office
        assertEquals(home, s.resolve(1, 10 * 60, elsewhere).activeId) // wrong place: last activated
        assertEquals(home, s.resolve(1, 10 * 60, null).activeId) // place unknown
        assertEquals(home, s.resolve(6, 10 * 60, atOffice).activeId) // Saturday
        assertEquals(late, s.resolve(1, 17 * 60 + 10, atOffice).activeId) // both match: higher wins
        s = s.moveProfile(late, 1) // Work above Late now
        assertEquals(work, s.resolve(1, 17 * 60 + 10, atOffice).activeId)
        assertEquals(listOf(work, late, home), s.profiles.map { it.id })
        assertEquals(s, s.moveProfile(work, -1)) // already on top

        // The fallback is whatever was activated last, conditions or not.
        s = s.activate(late).resolve(6, 10 * 60, null)
        assertEquals(late, s.activeId)
    }

    @Test
    fun activatingOverridesTheMatchUntilItChanges() {
        var s = Settings()
        val home = s.activeId
        var work = ""
        s = s.addProfile("Work").let { (n, id) -> work = id; n }.setTime(work, TimeRule(TimeRule.ALL, 9 * 60, 18 * 60))
        s = s.resolve(1, 10 * 60, null)
        assertEquals(work, s.activeId)

        s = s.activate(home).resolve(1, 11 * 60, null) // chosen by hand while Work matches
        assertEquals(home, s.activeId)
        assertEquals(work, s.overriddenId)
        assertEquals(work, s.automatic().resolve(1, 11 * 60, null).activeId) // "automatic" gives it back

        s = s.resolve(1, 19 * 60, null) // Work stops matching: the override ends
        assertEquals(home, s.activeId)
        assertNull(s.overriddenId)
        assertEquals(work, s.resolve(2, 10 * 60, null).activeId) // next morning Work applies again

        // Activating the matching profile itself is no override.
        val t = Settings().let { base -> base.setTime(base.activeId, TimeRule(TimeRule.ALL, 0, 0)) }.resolve(1, 0, null)
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
    fun placeRules() {
        val p = PlaceRule(0.0, 0.0, 100)
        assertTrue(p.contains(GeoPoint(0.0, 0.0008))) // ~89 m
        assertFalse(p.contains(GeoPoint(0.0, 0.0020))) // ~222 m
        assertTrue(p.contains(GeoPoint(0.0, 0.0015, 80f))) // ~167 m, within radius + accuracy
        assertFalse(p.contains(GeoPoint(0.0, 0.0030, 5000f))) // accuracy counts at most the radius
    }

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
        s = s.setHotkey(s.activeId, 2, Hotkey(Mods.META or Mods.SHIFT, 59))
        val (next, office) = s.addProfile("Office")
        s = next.assign(office, 1, null)
            .setTime(office, TimeRule(0b0000101, 22 * 60, 6 * 60))
            .setPlace(office, PlaceRule(37.123456789, -122.5, 500, "HQ, 3rd floor"))
            .activate(office)
        val back = Settings.decode(s.encode())
        assertEquals(s, back)
        assertEquals(listOf(Device("AA:BB", "My Desk", 1_700_000_000_000L), Device("CC:DD", "Tab", 0, true)), back.devices)
        assertEquals(office, back.activeId)
        assertEquals("HQ, 3rd floor", back.profile(office)!!.place!!.label)
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
