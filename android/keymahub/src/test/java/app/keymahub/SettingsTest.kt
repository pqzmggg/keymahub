package app.keymahub

import app.keymahub.core.ActivationMode
import app.keymahub.core.Device
import app.keymahub.core.Hotkey
import app.keymahub.core.Hotkeys
import app.keymahub.core.Mods
import app.keymahub.core.Profile
import app.keymahub.core.Settings
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
    fun moveReceiverShiftsTheOthersLikeAList() {
        var s = Settings().deviceConnected("A", "a").deviceConnected("B", "b").deviceConnected("C", "c")
        val id = s.activeId
        assertEquals(mapOf(1 to "A", 2 to "B", 3 to "C"), s.profile(id)!!.receivers)
        s = s.moveReceiver(id, 3, 1) // C to the top: A and B move down
        assertEquals(mapOf(1 to "C", 2 to "A", 3 to "B"), s.profile(id)!!.receivers)
        s = s.moveReceiver(id, 1, 5) // C down past the empty slots 4 and 5
        assertEquals(mapOf(1 to "A", 2 to "B", 5 to "C"), s.profile(id)!!.receivers)
        s = s.moveReceiver(id, 4, 1) // an empty slot moves too
        assertEquals(mapOf(2 to "A", 3 to "B", 5 to "C"), s.profile(id)!!.receivers)
        assertEquals(s, s.moveReceiver(id, 2, 2))
    }

    @Test
    fun excludeReceiverPullsTheRestUp() {
        var s = Settings().deviceConnected("A", "a").deviceConnected("B", "b").deviceConnected("C", "c")
        val id = s.activeId
        s = s.moveReceiver(id, 3, 5) // A, B on 1-2, C on 5
        s = s.excludeReceiver(id, 1) // everything after 1 moves up one, the gap too
        assertEquals(mapOf(1 to "B", 4 to "C"), s.profile(id)!!.receivers)
        assertEquals(3, s.devices.size) // still paired
        assertEquals(s, s.excludeReceiver(id, 2)) // an empty slot: nothing to exclude
        s = s.excludeReceiver(id, 4) // the last one: nothing moves
        assertEquals(mapOf(1 to "B"), s.profile(id)!!.receivers)
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
        s = s.setWhenConnected(first, setOf("A"))
        val (next, home) = s.addProfile("Home")
        s = next
        assertEquals(listOf(home, first), s.profiles.map { it.id })
        assertEquals("A", s.profile(home)!!.addressOf(1)) // copied receivers
        assertTrue(s.profile(home)!!.whenConnected.isEmpty()) // but not the conditions
        assertEquals(first, s.resolve(setOf("A")).activeId) // no conditions: not used until activated
        assertEquals(home, s.resolve(setOf("A")).activate(home).resolve(setOf("A")).activeId) // overrides the match
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
    fun conditionsByPriorityElseTheTopProfile() {
        var s = Settings(mode = ActivationMode.RULES)
            .deviceConnected("OFFICE", "Office PC").deviceConnected("HOME", "Home PC").deviceConnected("TAB", "Tablet")
        val home = s.activeId // no conditions
        var work = ""
        var tablet = ""
        s = s.addProfile("Work").let { (n, id) -> work = id; n }.setWhenConnected(work, setOf("OFFICE"))
        s = s.addProfile("Tablet").let { (n, id) -> tablet = id; n }.setWhenConnected(tablet, setOf("TAB"))
        // order: Tablet, Work, Home
        assertEquals(work, s.resolve(setOf("OFFICE")).activeId)
        assertEquals(tablet, s.resolve(setOf("HOME")).activeId) // no rule for it: the top profile
        assertEquals(tablet, s.resolve(none).activeId) // nothing connected: the top profile
        assertEquals(tablet, s.resolve(setOf("OFFICE", "TAB")).activeId) // both match: higher wins
        s = s.moveProfile(tablet, 1) // Work above Tablet now
        assertEquals(work, s.resolve(setOf("OFFICE", "TAB")).activeId)
        assertEquals(work, s.resolve(none).activeId) // the new top profile
        assertEquals(listOf(work, tablet, home), s.profiles.map { it.id })
        assertEquals(s, s.moveProfile(work, -1)) // already on top

        // Activated by hand while nothing matches: used until something does.
        s = s.activate(tablet).resolve(none)
        assertEquals(tablet, s.activeId)
        assertEquals(work, s.resolve(setOf("OFFICE")).activeId)
    }

    @Test
    fun anyChosenDeviceMatchesAndForgottenDevicesDrop() {
        var s = Settings().deviceConnected("A", "Desk").deviceConnected("B", "Laptop")
        val id = s.activeId
        s = s.setWhenConnected(id, setOf("A", "B", "UNKNOWN"))
        assertEquals(setOf("A", "B"), s.active.whenConnected) // unknown devices are not kept
        assertTrue(s.active.matches(setOf("B")))
        assertFalse(s.active.matches(none))
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
        s = s.resolve(setOf("A", "B"))
        assertEquals(office, s.activeId) // 2 each: 2 of 2 beats 2 of 3
        assertEquals(home, s.resolve(setOf("A", "B", "C")).activeId) // 3 beats 2
        assertEquals(office, s.resolve(setOf("A")).activeId) // 1 each: 1 of 2 beats 1 of 3
        // Nothing connected: the top profile; one activated by hand then stays until a device connects.
        val none0 = s.resolve(none)
        assertEquals(office, none0.activeId)
        val byHand = none0.activate(home)
        assertEquals(home, byHand.resolve(none).activeId)
        assertEquals(office, byHand.resolve(none).resolve(setOf("A")).activeId)
        // Conditions do not matter in this mode.
        val ruled = s.setWhenConnected(home, setOf("C")).resolve(setOf("A", "B"))
        assertEquals(office, ruled.activeId)
    }

    @Test
    fun fullyAutomaticPicksTheMostThenTheLargestShareThenTheHigher() {
        var s = Settings().deviceConnected("A", "a").deviceConnected("B", "b")
            .deviceConnected("C", "c").deviceConnected("D", "d")
        val home = s.activeId // A, B, C, D
        val (next, office) = s.addProfile("Office") // on top, A, B, C, D too
        s = next.excludeReceiver(home, 3).excludeReceiver(home, 3) // home: A, B
        // 2 connected each: home has 2 of 2, office 2 of 4: home, though lower.
        assertEquals(home, s.resolve(setOf("A", "B")).activeId)
        // Count first: office's 3 of 4 beats home's 2 of 2.
        assertEquals(office, s.resolve(setOf("A", "B", "C")).activeId)
        // Same count and share (1 of 2 each): the higher profile.
        s = s.excludeReceiver(office, 1).excludeReceiver(office, 1) // office: C, D
        assertEquals(office, s.resolve(setOf("A", "C")).activeId)
    }

    @Test
    fun manualIgnoresEverythingButTheChoice() {
        var s = Settings(mode = ActivationMode.MANUAL).deviceConnected("A", "Desk")
        val first = s.activeId
        val (next, other) = s.addProfile("Other")
        s = next.setWhenConnected(other, setOf("A"))
        assertEquals(first, s.resolve(setOf("A")).activeId)
        assertEquals(other, s.activate(other).resolve(none).activeId)
        // Switching modes drops a pending override and re-picks.
        val auto = s.activate(first).setMode(ActivationMode.RULES).resolve(setOf("A"))
        assertEquals(other, auto.activeId)
    }

    @Test
    fun activatingOverridesTheMatchUntilItChanges() {
        var s = Settings(mode = ActivationMode.RULES).deviceConnected("W", "Work PC")
        val home = s.activeId
        var work = ""
        s = s.addProfile("Work").let { (n, id) -> work = id; n }.setWhenConnected(work, setOf("W"))
        s = s.resolve(setOf("W"))
        assertEquals(work, s.activeId)

        s = s.activate(home).resolve(setOf("W")) // chosen by hand while Work matches
        assertEquals(home, s.activeId)
        assertEquals(work, s.overriddenId)
        assertEquals(work, s.automatic().resolve(setOf("W")).activeId) // "automatic" gives it back

        s = s.resolve(none) // Work stops matching: the override ends, and the top profile (Work) is used
        assertEquals(work, s.activeId)
        assertNull(s.overriddenId)
        assertEquals(work, s.resolve(setOf("W")).activeId) // it matches again later

        // Activating the matching profile itself is no override.
        val t = s.resolve(setOf("W"))
        assertNull(t.activate(t.activeId).overriddenId)
    }

    @Test
    fun devicesRememberUseAndBlocking() {
        var s = Settings().deviceConnected("A", "Desk").touch("A", 1234L).setBlocked("A", true)
        assertEquals(Device("A", "Desk", 1234L, true), s.device("A"))
        s = s.setBlocked("A", false)
        assertFalse(s.device("A")!!.blocked)
    }

    @Test
    fun forgetPullsTheDevicesAfterItUpInEveryProfile() {
        var s = Settings().deviceConnected("A", "a").deviceConnected("B", "b").deviceConnected("C", "c")
        val (next, two) = s.addProfile("Two") // same numbers: A 1, B 2, C 3
        s = next.moveReceiver(two, 1, 3) // Two: B 1, C 2, A 3
        val one = s.profiles.last().id
        s = s.forgetDevice("A")
        assertEquals(mapOf(1 to "B", 2 to "C"), s.profile(one)!!.receivers) // B and C moved up
        assertEquals(mapOf(1 to "B", 2 to "C"), s.profile(two)!!.receivers) // A was last: nothing moved
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
