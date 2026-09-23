package com.kemahub.capture

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.kemahub.core.Buttons
import com.kemahub.core.Hotkey
import com.kemahub.core.InputSink
import com.kemahub.core.SlotRouter
import com.kemahub.hid.EvdevKeymap
import com.kemahub.hid.HidKeycodes

/**
 * Input from the phone's physical keyboard and mouse, taken through the accessibility
 * service and routed by [SlotRouter].
 *
 * - Keyboard: key filtering sees every hardware key before apps do. "This phone" simply
 *   means not consuming the event.
 * - Mouse: while a target has focus, [PointerCaptureOverlay] captures the pointer (phone
 *   pointer hidden and frozen) and feeds [onCapturedPointer]. If capture is refused, the
 *   accessibility interception (Android 14+) feeds [onMotionEvent] instead.
 *
 * Thread-safe: all entry points synchronize on the router.
 */
class A11yCapture(
    remote: InputSink,
    hotkey: (Hotkey) -> Int?,
    isAvailable: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
    onUnavailable: (Int) -> Unit,
) {
    /** Local side of the router: records "let this event through" instead of emitting it. */
    private class PassThrough : InputSink {
        var pass = false
        override fun key(usage: Int, down: Boolean) { pass = true }
        override fun move(dx: Int, dy: Int) { pass = true }
        override fun button(button: Int, down: Boolean) { pass = true }
        override fun wheel(v: Int, h: Int) { pass = true }
        override fun releaseAll() {}
    }

    private val local = PassThrough()
    private val router = SlotRouter(local, remote, hotkey, isAvailable, onSelect = {
        buttons = 0
        lastX = Float.NaN
        lastY = Float.NaN
        onSelect(it)
    }, onUnavailable = onUnavailable)
    private var buttons = 0
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var subX = 0f
    private var subY = 0f

    val slot get() = synchronized(router) { router.slot }

    fun select(slot: Int) = synchronized(router) { router.select(slot) }

    fun targetLost(slot: Int) = synchronized(router) { router.targetLost(slot) }

    fun stop() = synchronized(router) { router.reset() }

    /** Returns true to consume the key (it went to a target or was a hotkey). */
    fun onKeyEvent(e: KeyEvent): Boolean {
        val dev = e.device ?: return false
        // Only physical full keyboards; leave volume/power buttons and virtual keys alone.
        if (dev.isVirtual || dev.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        val code = e.scanCode.takeIf { it != 0 } ?: return false
        synchronized(router) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                return !router.isHeldLocally(code)
            }
            if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return false
            val hid = EvdevKeymap.toHid(code) ?: HidKeycodes.fromKeycode(e.keyCode)
                ?: return router.isRemote // unmapped (e.g. media keys): leave local use alone
            local.pass = false
            router.onKey(code, hid, e.action == KeyEvent.ACTION_DOWN)
            return !local.pass
        }
    }

    /** Mouse events intercepted by the accessibility service while remote (Android 14+ fallback). */
    fun onMotionEvent(e: MotionEvent) = synchronized(router) {
        if (!router.isRemote) return@synchronized
        var dx = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        var dy = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (dx == 0f && dy == 0f && !lastX.isNaN()) {
            // Some devices do not report relative axes; fall back to absolute deltas.
            dx = e.x - lastX
            dy = e.y - lastY
        }
        lastX = e.x
        lastY = e.y
        pointer(dx, dy, e)
    }

    /** Pointer-capture events: x/y are already relative motion (SOURCE_MOUSE_RELATIVE). */
    fun onCapturedPointer(e: MotionEvent) = synchronized(router) {
        if (!router.isRemote || !e.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) return@synchronized
        var dx = 0f
        var dy = 0f
        for (i in 0 until e.historySize) {
            dx += e.getHistoricalX(i)
            dy += e.getHistoricalY(i)
        }
        pointer(dx + e.x, dy + e.y, e)
    }

    /** Holds the router lock. */
    private fun pointer(dx: Float, dy: Float, e: MotionEvent) {
        subX += dx
        subY += dy
        val ix = subX.toInt()
        val iy = subY.toInt()
        subX -= ix
        subY -= iy
        router.onMove(ix, iy)

        val state = e.buttonState
        for ((bit, button) in BUTTON_BITS) {
            val now = state and bit != 0
            if (now != (buttons and bit != 0)) router.onButton(button, now)
        }
        buttons = state

        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            router.onWheel(
                (e.getAxisValue(MotionEvent.AXIS_VSCROLL) * Buttons.WHEEL_NOTCH).toInt(),
                (e.getAxisValue(MotionEvent.AXIS_HSCROLL) * Buttons.WHEEL_NOTCH).toInt(),
            )
        }
    }

    private companion object {
        val BUTTON_BITS = listOf(
            MotionEvent.BUTTON_PRIMARY to Buttons.LEFT,
            MotionEvent.BUTTON_SECONDARY to Buttons.RIGHT,
            MotionEvent.BUTTON_TERTIARY to Buttons.MIDDLE,
            MotionEvent.BUTTON_BACK to Buttons.BACK,
            MotionEvent.BUTTON_FORWARD to Buttons.FORWARD,
        )
    }
}
