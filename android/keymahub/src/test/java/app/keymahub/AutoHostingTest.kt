package app.keymahub

import app.keymahub.core.AutoHostingMode
import app.keymahub.core.AutoHostingMode.ANY_INPUT
import app.keymahub.core.AutoHostingMode.CHOSEN
import app.keymahub.core.AutoHostingMode.OFF
import app.keymahub.core.AutoHostingRule
import app.keymahub.core.InputKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHostingTest {
    private val keyboard = InputKey("kb", "Keyboard")
    private val mouse = InputKey("ms", "Mouse")

    private fun rule(mode: AutoHostingMode, vararg first: InputKey, chosen: Set<String> = emptySet()) =
        AutoHostingRule().also { it.update(mode, chosen, first.toList()) }

    @Test
    fun theFirstLookNeverCounts() {
        assertNull(AutoHostingRule().update(ANY_INPUT, emptySet(), listOf(keyboard)).arrived)
    }

    @Test
    fun anyKeyboardOrMouseConnecting() {
        val r = rule(ANY_INPUT)
        assertEquals(keyboard, r.update(ANY_INPUT, emptySet(), listOf(keyboard)).arrived)
        assertEquals(mouse, r.update(ANY_INPUT, emptySet(), listOf(keyboard, mouse)).arrived)
    }

    @Test
    fun offNeverCounts() {
        val r = rule(OFF)
        assertNull(r.update(OFF, emptySet(), listOf(keyboard)).arrived)
    }

    @Test
    fun onlyChosenDevicesCount() {
        val r = rule(CHOSEN, chosen = setOf("ms"))
        assertNull(r.update(CHOSEN, setOf("ms"), listOf(keyboard)).arrived)
        assertEquals(mouse, r.update(CHOSEN, setOf("ms"), listOf(keyboard, mouse)).arrived)
    }

    @Test
    fun aDeviceStayingConnectedDoesNotCountAgain() {
        // Hosting turned off by hand while the keyboard stays: nothing until it connects again.
        val r = rule(ANY_INPUT)
        r.update(ANY_INPUT, emptySet(), listOf(keyboard))
        assertNull(r.update(ANY_INPUT, emptySet(), listOf(keyboard)).arrived)
        r.update(ANY_INPUT, emptySet(), emptyList())
        assertEquals(keyboard, r.update(ANY_INPUT, emptySet(), listOf(keyboard)).arrived)
    }

    @Test
    fun changingTheSettingsDoesNotCountAsConnecting() {
        val r = rule(OFF, keyboard)
        assertNull(r.update(ANY_INPUT, emptySet(), listOf(keyboard)).arrived)
        assertNull(r.update(CHOSEN, setOf("kb"), listOf(keyboard)).arrived)
    }

    @Test
    fun connectingRightAfterTheSettingsChangedCounts() {
        val r = rule(OFF)
        assertEquals(keyboard, r.update(ANY_INPUT, emptySet(), listOf(keyboard)).arrived)
    }

    @Test
    fun allGoneOnlyWhenTheLastOneThatCountsLeaves() {
        val r = rule(ANY_INPUT, keyboard, mouse)
        assertFalse(r.update(ANY_INPUT, emptySet(), listOf(mouse)).allGone)
        assertTrue(r.update(ANY_INPUT, emptySet(), emptyList()).allGone)
        assertFalse(r.update(ANY_INPUT, emptySet(), emptyList()).allGone)
    }

    @Test
    fun allGoneCountsOnlyChosenDevices() {
        val r = rule(CHOSEN, keyboard, mouse, chosen = setOf("kb"))
        assertTrue(r.update(CHOSEN, setOf("kb"), listOf(mouse)).allGone)
    }

    @Test
    fun keysSurviveEncoding() {
        val keys = listOf(keyboard, InputKey("x1", "Name\twith\ntabs"))
        val back = InputKey.decode(InputKey.encode(keys))
        assertEquals(listOf(keyboard, InputKey("x1", "Name with tabs")), back)
        assertEquals(emptyList<InputKey>(), InputKey.decode(null))
        assertEquals(emptyList<InputKey>(), InputKey.decode(""))
    }
}
