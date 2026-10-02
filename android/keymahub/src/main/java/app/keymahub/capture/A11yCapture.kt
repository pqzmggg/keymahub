package app.keymahub.capture

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.keymahub.core.Buttons
import app.keymahub.core.Hotkey
import app.keymahub.core.InputSink
import app.keymahub.core.SlotRouter
import app.keymahub.core.Hub
import app.keymahub.hid.ConsumerKeys
import app.keymahub.hid.EvdevKeymap
import app.keymahub.hid.HidKeycodes

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
    /**
     * A mouse button was pressed in [onMotionEvent] while a target has focus: pointer capture was
     * lost (a touch gave focus to a phone app) and the mouse is back at work on the target.
     */
    private val onUncapturedClick: () -> Unit,
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
        subX = 0f
        subY = 0f
        onSelect(it)
    }, onUnavailable = onUnavailable)
    private var buttons = 0
    /** When 한/영 (Lang1) last went to a target from the key itself ([onKeyEvent]), uptime millis. */
    private var lastLang1 = 0L
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
        val dev = e.device
        val mouse = dev != null && !dev.isVirtual && dev.supportsSource(InputDevice.SOURCE_MOUSE)
        // A mouse's back and forward buttons also come as BACK / FORWARD keys, which would act on
        // this device: on a target they are its buttons 4 and 5. Routed as buttons, so a release goes
        // where its press went (a press on this device is let through, and so is its release). The
        // button state may carry them as well (onCapturedPointer): the router ignores a second
        // press, and a release with no press while on a target.
        val mouseButton = if (mouse) MOUSE_KEYS[e.keyCode] else null
        if (mouseButton != null) synchronized(router) {
            if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return false
            if (e.repeatCount > 0) return router.isRemote
            local.pass = false
            router.onButton(mouseButton, e.action == KeyEvent.ACTION_DOWN)
            return !local.pass
        }
        val keyboard = dev != null && !dev.isVirtual && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        // The language switch key handed over without a scan code or from a virtual device (the 한/영
        // key on some tablets), which would otherwise stay on this device: to the target as 한/영
        // (Lang1). Keys that come with their scan code are sent as that key, as before.
        val language = e.keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH && (!keyboard || e.scanCode == 0)
        // Otherwise only physical full keyboards; leave volume/power buttons and virtual keys alone.
        if (!language && !keyboard) {
            // Not the phone's own buttons (volume, power): those are not worth a line. A mouse's other
            // keys are (its special buttons, to tell which ones Android passes on at all).
            if (dev == null || dev.isVirtual) unrouted(e, "virtual device")
            else if (mouse) unrouted(e, "mouse ${dev.name}")
            return false
        }
        val code = if (language) LANGUAGE_SWITCH_CODE else e.scanCode.takeIf { it != 0 } ?: run {
            unrouted(e, "no scan code")
            return false
        }
        synchronized(router) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                return !router.isHeldLocally(code)
            }
            if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return false
            val hid = if (language) {
                HidKeycodes.LANG1_HANGUL
            } else {
                ConsumerKeys.fromKeycode(e.keyCode) ?: EvdevKeymap.toHid(code) ?: HidKeycodes.fromKeycode(e.keyCode)
            }
            if (hid == null) {
                // Not sendable: leave local use alone; on a target, drop it (and say so for diagnosis).
                if (router.isRemote && e.action == KeyEvent.ACTION_DOWN) {
                    Hub.log("key not sent: scan $code ${KeyEvent.keyCodeToString(e.keyCode)}")
                }
                return router.isRemote
            }
            local.pass = false
            router.onKey(code, hid, e.action == KeyEvent.ACTION_DOWN)
            if (hid == HidKeycodes.LANG1_HANGUL && router.isRemote) lastLang1 = SystemClock.uptimeMillis()
            return !local.pass
        }
    }

    /**
     * This device's input language changed (its keyboard app's language). Some devices (a Lenovo
     * tablet) switch it on the 한/영 key themselves, before the key reaches accessibility, so the
     * key never reaches [onKeyEvent]: while a target has control, 한/영 (Lang1) goes to it instead.
     * Not when the key itself was just sent (devices that pass it on switch nothing here, but just in case).
     */
    fun onInputLanguageChanged() = synchronized(router) {
        if (!router.isRemote) return@synchronized
        if (SystemClock.uptimeMillis() - lastLang1 < LANG1_ECHO_MS) return@synchronized
        Hub.log("input language changed on this device: 한/영 sent to the target")
        router.onKey(LANGUAGE_SWITCH_CODE, HidKeycodes.LANG1_HANGUL, true)
        router.onKey(LANGUAGE_SWITCH_CODE, HidKeycodes.LANG1_HANGUL, false)
    }

    /**
     * A key left to this device while a target has control, for diagnosis (e.g. a 한/영 key the
     * system hands over in an unexpected form). Not typing: printing keys are not logged.
     */
    private fun unrouted(e: KeyEvent, why: String) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0 || e.isPrintingKey) return
        if (synchronized(router) { router.isRemote }) {
            Hub.log("key left to this device ($why): ${KeyEvent.keyCodeToString(e.keyCode)} scan ${e.scanCode}")
        }
    }

    /** Mouse events intercepted by the accessibility service while remote (Android 14+ fallback). */
    fun onMotionEvent(e: MotionEvent) = synchronized(router) {
        if (!router.isRemote) return@synchronized
        // Only a click takes focus back for capture, not motion: a phone app the user touched keeps
        // focus, so its on-screen keyboard stays up while the mouse still goes to the target.
        if (e.buttonState and buttons.inv() != 0) onUncapturedClick()
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
        /** Stands for the language switch key where a scan code would (above the evdev range). */
        const val LANGUAGE_SWITCH_CODE = 0x10000 + KeyEvent.KEYCODE_LANGUAGE_SWITCH

        /** A language change this soon after sending 한/영 is that key's own doing. */
        const val LANG1_ECHO_MS = 1000L

        /** Keys a mouse sends for its side buttons, and those buttons. */
        val MOUSE_KEYS = mapOf(
            KeyEvent.KEYCODE_BACK to Buttons.BACK,
            KeyEvent.KEYCODE_FORWARD to Buttons.FORWARD,
        )

        val BUTTON_BITS = listOf(
            MotionEvent.BUTTON_PRIMARY to Buttons.LEFT,
            MotionEvent.BUTTON_SECONDARY to Buttons.RIGHT,
            MotionEvent.BUTTON_TERTIARY to Buttons.MIDDLE,
            MotionEvent.BUTTON_BACK to Buttons.BACK,
            MotionEvent.BUTTON_FORWARD to Buttons.FORWARD,
        )
    }
}
