package app.keymahub.core

/**
 * Decides where every key, button and motion from the phone's keyboard/mouse goes:
 * [local] (this phone) or [remote] (the Bluetooth target in the current slot).
 *
 * Hotkeys come from [hotkey] (the active profile): a chord selects slot 0 (this phone) or a
 * receiver slot 1..9. The held modifiers must match exactly; left and right count the same.
 *
 * Invariant: a key-up (or button-up) goes where its key-down went, so switching never leaves
 * a key held on either side. When leaving a target, whatever is still held there is
 * released on that target, and the physical releases are swallowed.
 *
 * A release that never arrives would leave its key counted as held for good: a modifier makes
 * every chord miss its hotkey, a number key ignores its hotkey's press. So a key pressed again,
 * and a modifier the system no longer reports held, count as released ([onStale]).
 *
 * Not thread-safe: callers serialize access.
 */
class SlotRouter(
    private val local: InputSink,
    private val remote: InputSink,
    /** The slot a chord selects, or null if it is not a hotkey (right now). */
    private val hotkey: (Hotkey) -> Int?,
    /** Whether a ready receiver is on [slot] (1..9). */
    private val isAvailable: (slot: Int) -> Boolean,
    /** Focus changed to [slot] (0 = this phone). Called after the old target's keys were released. */
    private val onSelect: (slot: Int) -> Unit,
    /** The hotkey of [slot] was pressed but nothing is connected there. */
    private val onUnavailable: (slot: Int) -> Unit,
    /** Key [code] was still counted as held although it was up (its release never came); now released. */
    private val onStale: (code: Int) -> Unit = {},
) {
    private enum class Dest { LOCAL, REMOTE, SWALLOWED }

    /** 0 = this phone, 1..9 = target slot. */
    var slot = 0
        private set
    val isRemote get() = slot != 0

    private val keys = HashMap<Int, Pair<Dest, Int?>>() // evdev code -> (dest, HID usage)
    private val buttons = HashMap<Int, Dest>()

    /**
     * [code] is the evdev key code, [hid] its HID usage if mapped. Auto-repeat is not passed in.
     * [held]: the modifiers ([Mods] bits) the system reports held with this key, if known.
     */
    fun onKey(code: Int, hid: Int?, down: Boolean, held: Int? = null) {
        if (!down) {
            val (dest, usage) = keys.remove(code) ?: ((if (isRemote) Dest.SWALLOWED else Dest.LOCAL) to hid)
            if (usage != null) sink(dest)?.key(usage, false)
            return
        }
        // Pressed again without a release in between: the release was lost.
        if (code in keys) drop(code)
        if (held != null && Mods.of(code) == 0) {
            for (c in keys.keys.toList()) {
                val bit = Mods.of(c)
                if (bit != 0 && held and bit == 0) drop(c)
            }
        }

        val mods = heldMods()
        val target = if (mods != 0 && Mods.of(code) == 0) hotkey(Hotkey(mods, code)) else null
        if (target != null) {
            keys[code] = Dest.SWALLOWED to null
            // The modifiers already went to the target and will be released there with no other
            // key in between, which some systems act on (Windows: Alt+Shift switches the input
            // language, a lone Alt opens the menu bar). Tapping an unused key prevents that.
            if (keys.values.any { it.first == Dest.REMOTE }) {
                remote.key(MASK_KEY, true)
                remote.key(MASK_KEY, false)
            }
            select(target)
            return
        }

        val dest = if (hid == null) Dest.SWALLOWED else here
        if (hid != null) sink(dest)?.key(hid, true)
        keys[code] = dest to hid
    }

    /** Whether the held key [code] went to this phone (its auto-repeat should too). */
    fun isHeldLocally(code: Int) = keys[code]?.first == Dest.LOCAL

    fun onButton(button: Int, down: Boolean) {
        if (!down) {
            sink(buttons.remove(button) ?: if (isRemote) Dest.SWALLOWED else Dest.LOCAL)?.button(button, false)
            return
        }
        if (button in buttons) return
        sink(here)?.button(button, true)
        buttons[button] = here
    }

    fun onMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        sink(here)?.move(dx, dy)
    }

    fun onWheel(v: Int, h: Int) {
        if (v == 0 && h == 0) return
        sink(here)?.wheel(v, h)
    }

    /** Switches focus as if the hotkey of [target] was pressed. */
    fun select(target: Int) {
        when {
            target == slot -> {}
            target == 0 -> {
                releaseRemote()
                slot = 0
                onSelect(0)
            }
            !isAvailable(target) -> onUnavailable(target)
            else -> {
                if (isRemote) releaseRemote()
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

    /** Where new input goes now. */
    private val here get() = if (isRemote) Dest.REMOTE else Dest.LOCAL

    /** The side input for [dest] goes to (null: nowhere). */
    private fun sink(dest: Dest): InputSink? = when (dest) {
        Dest.LOCAL -> local
        Dest.REMOTE -> remote
        Dest.SWALLOWED -> null
    }

    private fun heldMods() = keys.keys.fold(0) { m, code -> m or Mods.of(code) }

    /**
     * Forgets held key [code] whose release was lost, releasing it on the target it went to. One
     * left to this phone needs nothing: the system has it up already (and a release passed on now
     * would let the key being pressed through to the phone).
     */
    private fun drop(code: Int) {
        val (dest, usage) = keys.remove(code) ?: return
        if (dest == Dest.REMOTE && usage != null) remote.key(usage, false)
        onStale(code)
    }

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
        /** HID F24: present on no keyboard and bound to nothing by default. */
        const val MASK_KEY = 0x73
    }
}
