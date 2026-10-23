package app.keymahub

import app.keymahub.ble.MotionPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPaceTest {
    @Test
    fun followsTheSettingUntilTheLinkIsSlow() {
        val p = MotionPace()
        p.linkMs = 15 // a known interval is no floor
        assertEquals(10, p.interval(base = 10, now = 0))
    }

    @Test
    fun eachSignDoublesUpToTheMost() {
        val p = MotionPace()
        assertEquals(20, p.slowDown(base = 10, max = 40, now = 1_000))
        assertEquals(40, p.slowDown(base = 10, max = 40, now = 1_300))
        assertNull(p.slowDown(base = 10, max = 40, now = 1_600)) // already at the most
        assertEquals(40, p.interval(base = 10, now = 1_700))
    }

    @Test
    fun signsCloseTogetherCountOnce() {
        val p = MotionPace()
        assertEquals(20, p.slowDown(base = 10, max = 40, now = 1_000))
        assertNull(p.slowDown(base = 10, max = 40, now = 1_000 + MotionPace.SIGN_GAP_MS - 1))
        assertEquals(20, p.interval(base = 10, now = 1_100))
    }

    @Test
    fun recoversStepByStepAfterQuiet() {
        val p = MotionPace()
        p.slowDown(base = 10, max = 40, now = 1_000)
        p.slowDown(base = 10, max = 40, now = 1_300) // 40
        var recovered = false
        val t = 1_300 + MotionPace.RECOVER_MS
        assertEquals(40, p.interval(base = 10, now = t - 1) { recovered = true })
        assertEquals(20, p.interval(base = 10, now = t) { recovered = true })
        assertFalse(recovered)
        assertEquals(20, p.interval(base = 10, now = t + MotionPace.RECOVER_MS - 1) { recovered = true })
        assertEquals(10, p.interval(base = 10, now = t + MotionPace.RECOVER_MS) { recovered = true })
        assertTrue(recovered)
        assertEquals(0, p.slowMs)
    }

    @Test
    fun aSignWhileRecoveringStopsTheRecovery() {
        val p = MotionPace()
        p.slowDown(base = 10, max = 40, now = 1_000)
        p.slowDown(base = 10, max = 40, now = 1_300) // 40
        p.slowDown(base = 10, max = 40, now = 2_000) // at the most, but the sign is new
        assertEquals(40, p.interval(base = 10, now = 1_300 + MotionPace.RECOVER_MS))
    }

    @Test
    fun aMostBelowTheSettingNeverSlows() {
        assertNull(MotionPace().slowDown(base = 50, max = 40, now = 1_000))
    }

    @Test
    fun lateDependsOnTheLinkInterval() {
        val p = MotionPace()
        assertFalse(p.isLate(MotionPace.LATE_CONFIRM_MS - 1))
        assertTrue(p.isLate(MotionPace.LATE_CONFIRM_MS))
        p.linkMs = 30
        assertFalse(p.isLate(80)) // two or so intervals is normal
        assertTrue(p.isLate(90))
    }
}
