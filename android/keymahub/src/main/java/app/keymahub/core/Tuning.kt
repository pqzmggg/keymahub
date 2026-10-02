package app.keymahub.core

/**
 * Advanced settings: how input goes over Bluetooth to the target. The sender reads them for every
 * report, so a change applies at once. Each number stays within its [Tuning.Spec] range.
 */
data class Tuning(
    /** At most one mouse movement report per this many ms; motion in between is added up. */
    val motionMs: Int = MOTION.recommended,
    /** Reports handed to the Bluetooth stack and not yet confirmed, per target, at most. */
    val window: Int = WINDOW.recommended,
    /** How long confirmations may be late before they count as lost and sending goes on (ms). */
    val confirmMs: Int = CONFIRM.recommended,
    /** How soon a send the Bluetooth stack was too busy for is tried again (ms). */
    val busyRetryMs: Int = BUSY_RETRY.recommended,
    /** Input waiting this long or longer is logged (ms). */
    val slowLogMs: Int = SLOW_LOG.recommended,
    /** Ask hosts for the LE 2M PHY. */
    val le2m: Boolean = true,
    /** Take captured mouse events one by one rather than once per display frame. */
    val unbufferedMouse: Boolean = true,
) {
    /** The same, with every number within its range. */
    fun clamped() = copy(
        motionMs = MOTION.clamp(motionMs),
        window = WINDOW.clamp(window),
        confirmMs = CONFIRM.clamp(confirmMs),
        busyRetryMs = BUSY_RETRY.clamp(busyRetryMs),
        slowLogMs = SLOW_LOG.clamp(slowLogMs),
    )

    /** A number's recommended value and the range it may take. */
    class Spec(val recommended: Int, val min: Int, val max: Int) {
        fun clamp(v: Int) = v.coerceIn(min, max)
        operator fun contains(v: Int) = v in min..max
    }

    companion object {
        /**
         * 10 ms (100 reports a second): about what a short connection interval (11-15 ms) carries,
         * so motion does not pile up in the Bluetooth stack, where it can no longer be merged.
         */
        val MOTION = Spec(recommended = 10, min = 4, max = 50)
        /** 2: one report on the air and the next ready, the least that can wait outside the app's queue. */
        val WINDOW = Spec(recommended = 2, min = 1, max = 8)
        /** 250 ms: well past a slow link's confirmations; giving up sooner overfills the stack. */
        val CONFIRM = Spec(recommended = 250, min = 30, max = 1000)
        val BUSY_RETRY = Spec(recommended = 5, min = 1, max = 50)
        val SLOW_LOG = Spec(recommended = 50, min = 10, max = 1000)
    }
}
