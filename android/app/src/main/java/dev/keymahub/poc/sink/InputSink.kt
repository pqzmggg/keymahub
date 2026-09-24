package dev.keymahub.poc.sink

import dev.keymahub.poc.HangulKey
import dev.keymahub.poc.input.HidKeycodes

/**
 * Where decoded input goes. Calls come from the network thread, one at a time.
 * Usages are HID keyboard usages; buttons are `Wire.BUTTON_*`; wheel is in 1/120 notches.
 */
interface InputSink {
    /** Returns null when ready, or a human-readable reason it cannot run. */
    fun start(): String?
    fun stop()
    fun enter() {}
    fun leave() {}
    fun key(usage: Int, down: Boolean, repeat: Boolean)
    fun move(dx: Int, dy: Int)
    fun button(button: Int, down: Boolean)
    fun wheel(v: Int, h: Int)
    fun releaseAll()
}

/**
 * Backend-independent processing in front of a sink: pointer speed (with sub-pixel
 * remainder so slow movements are not lost) and the Hangul/English key mapping.
 */
class Pipeline(
    private val inner: InputSink,
    private val hangul: HangulKey,
    private val speed: Float,
) : InputSink by inner {
    private var remX = 0f
    private var remY = 0f

    override fun move(dx: Int, dy: Int) {
        val fx = dx * speed + remX
        val fy = dy * speed + remY
        val ix = fx.toInt()
        val iy = fy.toInt()
        remX = fx - ix
        remY = fy - iy
        if (ix != 0 || iy != 0) inner.move(ix, iy)
    }

    override fun key(usage: Int, down: Boolean, repeat: Boolean) {
        if (usage != HidKeycodes.LANG1_HANGUL || hangul == HangulKey.PASSTHROUGH) {
            inner.key(usage, down, repeat)
            return
        }
        // Toggle on press only; the release and auto-repeat of the Hangul key are dropped.
        if (!down || repeat) return
        val modifier = if (hangul == HangulKey.CTRL_SPACE) HidKeycodes.LEFT_CTRL else HidKeycodes.LEFT_SHIFT
        inner.key(modifier, true, false)
        inner.key(HidKeycodes.SPACE, true, false)
        inner.key(HidKeycodes.SPACE, false, false)
        inner.key(modifier, false, false)
    }
}
