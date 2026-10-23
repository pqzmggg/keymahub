package app.keymahub.ble

/**
 * How often mouse motion may go to one target when the interval adapts to the link
 * ([app.keymahub.core.Tuning.adaptiveMotion]). Motion handed to the Bluetooth stack can no longer
 * be merged (see [ReportQueue]): sent faster than the link carries, it piles up there and the
 * host replays the path slowly until the stack drains. So while the link shows it is slow, motion
 * goes out less often and the rest merges in the app instead.
 *
 * - Each sign of a slow link ([slowDown]: a late confirmation, input waiting in the app,
 *   confirmations lost) doubles the interval, up to at most ([app.keymahub.core.Tuning.adaptiveMaxMs]);
 *   signs closer together than [SIGN_GAP_MS] count once.
 * - After [RECOVER_MS] without a sign it halves again, step by step, back to the setting.
 * - The link's connection interval ([linkMs]), when the host told it, says how late is late
 *   ([isLate]); it is no floor: a few reports fit in one connection event.
 *
 * Not thread-safe: the caller holds its lock. Times are uptime millis.
 */
class MotionPace {
    /** The link's connection interval in ms (0: not known), see [isLate]. */
    var linkMs = 0
    /** The slowed interval (0: not slowed). */
    var slowMs = 0
        private set
    private var lastSign = 0L
    private var lastStep = 0L

    /** The interval to use now, for the setting [base]; [onRecovered] is told when slowing ends. */
    fun interval(base: Int, now: Long, onRecovered: () -> Unit = {}): Int {
        if (slowMs != 0 && now - maxOf(lastSign, lastStep) >= RECOVER_MS) {
            lastStep = now
            slowMs /= 2
            if (slowMs <= base) {
                slowMs = 0
                onRecovered()
            }
        }
        return maxOf(base, slowMs)
    }

    /** A sign of a slow link, slowing to at most [max]; the new interval when it changed, else null. */
    fun slowDown(base: Int, max: Int, now: Long): Int? {
        if (slowMs != 0 && now - lastSign < SIGN_GAP_MS) return null
        lastSign = now
        val current = maxOf(base, slowMs)
        val next = (current * 2).coerceAtMost(max)
        if (next <= current) return null
        slowMs = next
        return next
    }

    /**
     * Whether a confirmation [ms] after the notification went out is late: past a few of the link's
     * connection intervals (one or two is normal), and at least [LATE_CONFIRM_MS].
     */
    fun isLate(ms: Long) = ms >= maxOf(LATE_CONFIRM_MS, 3L * linkMs)

    companion object {
        const val SIGN_GAP_MS = 200L
        const val RECOVER_MS = 1_500L
        const val LATE_CONFIRM_MS = 40L
    }
}
