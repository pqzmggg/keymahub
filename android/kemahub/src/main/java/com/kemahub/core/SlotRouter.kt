package com.kemahub.core

/**
 * Decides where every key, button and motion from the phone's keyboard/mouse goes:
 * [local] (this phone) or [remote] (the Bluetooth target in the current slot).
 *
 * Hotkeys: Ctrl+Alt+1..9 selects target slot 1..9, Ctrl+Alt+0 returns to this phone.
 * Either Ctrl and either Alt count; Shift and Meta are ignored.
 *
 * Invariant: a key-up (or button-up) goes where its key-down went, so switching never leaves
 * a key held on either side. When leaving a target, whatever is still held there is
 * released on that target, and the physical releases are swallowed.
 *
 * Not thread-safe: callers serialize access.
 */
class SlotRouter(
    private val local: InputSink,
    private val remote: InputSink,
    /** Whether a ready target is assigned to [slot] (1..9). */
    private val isAvailable: (slot: Int) -> Boolean,
    /** Focus changed to [slot] (0 = this phone). Called after the old target's keys were released. */
    private val onSelect: (slot: Int) -> Unit,
    /** Ctrl+Alt+[slot] was pressed but nothing is connected in that slot. */
    private val onUnavailable: (slot: Int) -> Unit = {},
) {
    private enum class Dest { LOCAL, REMOTE, SWALLOWED }

    /** 0 = this phone, 1..9 = target slot. */
    var slot = 0
        private set
    val isRemote get() = slot != 0

    private val keys = HashMap<Int, Pair<Dest, Int?>>() // evdev code -> (dest, HID usage)
    private val buttons = HashMap<Int, Dest>()

    /** [code] is the evdev key code, [hid] its HID usage if mapped. Auto-repeat is not passed in. */
    fun onKey(code: Int, hid: Int?, down: Boolean) {
        if (!down) {
            val (dest, usage) = keys.remove(code) ?: ((if (isRemote) Dest.SWALLOWED else Dest.LOCAL) to hid)
            if (usage != null) when (dest) {
                Dest.LOCAL -> local.key(usage, false)
                Dest.REMOTE -> remote.key(usage, false)
                Dest.SWALLOWED -> {}
            }
            return
        }
        if (code in keys) return // duplicate down

        val digit = DIGITS[code]
        if (digit != null && held(KEY_LEFTCTRL, KEY_RIGHTCTRL) && held(KEY_LEFTALT, KEY_RIGHTALT)) {
            keys[code] = Dest.SWALLOWED to null
            select(digit)
            return
        }

        val dest = when {
            hid == null -> Dest.SWALLOWED
            isRemote -> Dest.REMOTE.also { remote.key(hid, true) }
            else -> Dest.LOCAL.also { local.key(hid, true) }
        }
        keys[code] = dest to hid
    }

    /** Whether the held key [code] went to this phone (its auto-repeat should too). */
    fun isHeldLocally(code: Int) = keys[code]?.first == Dest.LOCAL

    fun onButton(button: Int, down: Boolean) {
        if (!down) {
            when (buttons.remove(button) ?: if (isRemote) Dest.SWALLOWED else Dest.LOCAL) {
                Dest.LOCAL -> local.button(button, false)
                Dest.REMOTE -> remote.button(button, false)
                Dest.SWALLOWED -> {}
            }
            return
        }
        if (button in buttons) return
        buttons[button] = if (isRemote) {
            remote.button(button, true)
            Dest.REMOTE
        } else {
            local.button(button, true)
            Dest.LOCAL
        }
    }

    fun onMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        if (isRemote) remote.move(dx, dy) else local.move(dx, dy)
    }

    fun onWheel(v: Int, h: Int) {
        if (v == 0 && h == 0) return
        if (isRemote) remote.wheel(v, h) else local.wheel(v, h)
    }

    /** Switches focus as if Ctrl+Alt+[target] was pressed. */
    fun select(target: Int) {
        when {
            target == slot -> {}
            target == 0 -> {
                releaseRemote()
                remote.leave()
                slot = 0
                onSelect(0)
            }
            !isAvailable(target) -> onUnavailable(target)
            else -> {
                if (isRemote) releaseRemote() else remote.enter()
                slot = target
                onSelect(target)
            }
        }
    }

    /** The target in [lost] went away: return to the phone if it had focus. */
    fun targetLost(lost: Int) {
        if (lost == slot) select(0)
    }

    /** Capture is stopping: return to the phone and forget held state. */
    fun reset() {
        select(0)
        keys.clear()
        buttons.clear()
    }

    private fun held(vararg codes: Int) = codes.any { it in keys }

    /** Releases on the current target whatever is held there; their physical releases get swallowed. */
    private fun releaseRemote() {
        for ((code, entry) in keys.entries.toList()) {
            val (dest, usage) = entry
            if (dest == Dest.REMOTE) {
                if (usage != null) remote.key(usage, false)
                keys[code] = Dest.SWALLOWED to usage
            }
        }
        for ((button, dest) in buttons.entries.toList()) {
            if (dest == Dest.REMOTE) {
                remote.button(button, false)
                buttons[button] = Dest.SWALLOWED
            }
        }
        remote.releaseAll()
    }

    companion object {
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTALT = 56
        const val KEY_RIGHTCTRL = 97
        const val KEY_RIGHTALT = 100

        /** evdev KEY_1..KEY_9 = 2..10, KEY_0 = 11 → slot. */
        private val DIGITS: Map<Int, Int> = (1..9).associateBy { it + 1 } + (11 to 0)
    }
}
