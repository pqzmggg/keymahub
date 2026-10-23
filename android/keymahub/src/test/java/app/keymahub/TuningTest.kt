package app.keymahub

import app.keymahub.core.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningTest {
    @Test
    fun recommendedValuesAreTheDefaultsAndInRange() {
        val t = Tuning()
        assertEquals(t, t.clamped())
        for (spec in listOf(Tuning.MOTION, Tuning.WINDOW, Tuning.CONFIRM, Tuning.BUSY_RETRY, Tuning.SLOW_LOG, Tuning.KEY_EXTRA, Tuning.ADAPTIVE_MAX)) {
            assertTrue(spec.recommended in spec)
        }
    }

    @Test
    fun clampedKeepsEveryNumberInItsRange() {
        val t = Tuning(motionMs = 0, window = 99, confirmMs = 5, busyRetryMs = 1000, slowLogMs = -1, adaptiveMaxMs = 1).clamped()
        assertEquals(Tuning.MOTION.min, t.motionMs)
        assertEquals(Tuning.WINDOW.max, t.window)
        assertEquals(Tuning.CONFIRM.min, t.confirmMs)
        assertEquals(Tuning.BUSY_RETRY.max, t.busyRetryMs)
        assertEquals(Tuning.SLOW_LOG.min, t.slowLogMs)
        assertEquals(Tuning.ADAPTIVE_MAX.min, t.adaptiveMaxMs)
    }

    @Test
    fun defaultsAreTheValuesTunedInUse() {
        val t = Tuning()
        assertEquals(listOf(10, 2, 150, 10, 70), listOf(t.motionMs, t.window, t.confirmMs, t.busyRetryMs, t.slowLogMs))
        assertEquals(true, t.le2m)
        assertEquals(false, t.unbufferedMouse)
        assertEquals(true, t.keysFirst)
        assertEquals(1, t.keyExtra)
        assertEquals(true, t.adaptiveMotion)
        assertEquals(40, t.adaptiveMaxMs)
    }
}
