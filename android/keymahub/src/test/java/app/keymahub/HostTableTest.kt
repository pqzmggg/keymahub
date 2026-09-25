package app.keymahub

import app.keymahub.ble.HostTable
import app.keymahub.ble.HostTable.Change
import app.keymahub.ble.HostTable.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTableTest {
    private val pc = "AA:AA:AA:AA:AA:01"
    private val tab = "AA:AA:AA:AA:AA:02"

    private fun subscribed(address: String) = mapOf(address to setOf(Report.KEYBOARD, Report.MOUSE))

    @Test
    fun firstConnectionIsReadyWhenKeyboardIsEnabled() {
        val t = HostTable()
        assertEquals(Change.NONE, t.linkUp(pc, bonded = true))
        assertTrue(t.useHid(pc, bonded = true, pairing = false))
        assertEquals(Change.NONE, t.subscribe(pc, Report.MOUSE, true))
        assertEquals(Change.READY, t.subscribe(pc, Report.KEYBOARD, true))
        assertEquals(setOf(pc), t.ready())
        // Writing it again (Android hosts sometimes do) is not a new ready.
        assertEquals(Change.NONE, t.subscribe(pc, Report.KEYBOARD, true))
    }

    @Test
    fun pairedHostThatSubscribedBeforeIsReadyWhenItsLinkComesBack() {
        // Windows reconnects without writing the CCCDs again.
        val t = HostTable(subscribed(pc))
        assertEquals(Change.READY, t.linkUp(pc, bonded = true))
        assertTrue(t.isSubscribed(pc, Report.MOUSE))
        // An Android host writes them again: nothing new.
        assertEquals(Change.NONE, t.subscribe(pc, Report.KEYBOARD, true))
    }

    @Test
    fun subscriptionsSurviveTheLinkGoingDown() {
        val t = HostTable()
        t.linkUp(pc, bonded = true)
        t.useHid(pc, bonded = true, pairing = false)
        t.subscribe(pc, Report.KEYBOARD, true)
        assertEquals(Change.GONE, t.linkDown(pc))
        assertTrue(t.ready().isEmpty())
        assertTrue(t.knows(pc))
        assertEquals(Change.READY, t.linkUp(pc, bonded = true))
    }

    @Test
    fun savedSubscriptionsNeedTheBond() {
        val t = HostTable(subscribed(pc))
        assertEquals(Change.NONE, t.linkUp(pc, bonded = false))
        assertFalse(t.isTarget(pc))
    }

    @Test
    fun unsubscribingKeyboardIsGone() {
        val t = HostTable(subscribed(pc))
        t.linkUp(pc, bonded = true)
        assertEquals(Change.GONE, t.subscribe(pc, Report.KEYBOARD, false))
        assertFalse(t.knows(pc))
        // Still a target: enabling it again makes it ready again.
        assertEquals(Change.READY, t.subscribe(pc, Report.KEYBOARD, true))
    }

    @Test
    fun unknownDeviceIsNoTargetUnlessPairing() {
        val t = HostTable()
        t.linkUp(tab, bonded = false)
        assertFalse(t.useHid(tab, bonded = false, pairing = false))
        assertEquals(Change.NONE, t.subscribe(tab, Report.KEYBOARD, true))
        assertFalse(t.knows(tab))
        assertTrue(t.useHid(tab, bonded = false, pairing = true))
        assertEquals(Change.READY, t.subscribe(tab, Report.KEYBOARD, true))
    }

    @Test
    fun hidUseCountsTheLinkEvenIfItWasNeverReported() {
        val t = HostTable()
        t.useHid(pc, bonded = true, pairing = false)
        assertTrue(t.isLinked(pc))
        assertEquals(Change.READY, t.subscribe(pc, Report.KEYBOARD, true))
    }

    @Test
    fun droppingLinksKeepsSubscriptions() {
        val t = HostTable(subscribed(pc) + subscribed(tab))
        t.linkUp(pc, bonded = true)
        t.linkUp(tab, bonded = true)
        assertEquals(listOf(pc, tab), t.dropLinks())
        assertTrue(t.ready().isEmpty() && t.linked().isEmpty())
        assertEquals(Change.READY, t.linkUp(tab, bonded = true))
    }

    @Test
    fun forgettingAndUnpairingDropSubscriptions() {
        val persisted = mutableListOf<Map<String, Set<Report>>>()
        val t = HostTable(subscribed(pc) + subscribed(tab)) { persisted += it }
        t.linkUp(pc, bonded = true)
        assertEquals(Change.GONE, t.forget(pc))
        assertFalse(t.knows(pc))
        t.retainPaired(setOf(pc))
        assertFalse(t.knows(tab))
        assertEquals(emptyMap<String, Set<Report>>(), persisted.last())
    }

    @Test
    fun changesArePersisted() {
        val persisted = mutableListOf<Map<String, Set<Report>>>()
        val t = HostTable { persisted += it }
        t.useHid(pc, bonded = true, pairing = false)
        t.subscribe(pc, Report.KEYBOARD, true)
        t.subscribe(pc, Report.KEYBOARD, true) // no change: not persisted again
        assertEquals(listOf(mapOf(pc to setOf(Report.KEYBOARD))), persisted)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val saved = mapOf(pc to setOf(Report.KEYBOARD, Report.MOUSE), tab to setOf(Report.BATTERY))
        assertEquals(saved, HostTable.decode(HostTable.encode(saved)))
        assertEquals(emptyMap<String, Set<Report>>(), HostTable.decode(null))
        assertEquals(mapOf(pc to setOf(Report.MOUSE)), HostTable.decode("$pc=MOUSE,BOGUS\n=KEYBOARD\ngarbage"))
    }
}
