package dev.keymanc.poc.host

import dev.keymanc.poc.sink.InputSink

/**
 * Android host routing, same rules as the Windows router (poc/win-capture/src/router.rs):
 * physical keyboards/mice are always grabbed; every event goes either to [local] (UHID
 * passthrough back into this phone) or to [remote] (the Bluetooth target).
 *
 * A key-up always follows its key-down, so switching never leaves a key stuck on either
 * side. Hotkeys: Ctrl+Alt+→ remote, Ctrl+Alt+← local, Ctrl+Alt+Shift+Esc emergency local.
 *
 * Not thread-safe: callers serialize access.
 */
class HostRouter(
    private val local: InputSink,
    private val remote: InputSink,
    private val onFocus: (remote: Boolean) -> Unit,
) {
    private enum class Dest { LOCAL, REMOTE, SWALLOWED }

    var isRemote = false
        private set
    private var remoteAvailable = false
    private val keys = HashMap<Int, Pair<Dest, Int?>>() // evdev code -> (dest, HID usage)
    private val buttons = HashMap<Int, Dest>()

    fun setRemoteAvailable(available: Boolean) {
        remoteAvailable = available
        if (!available) goLocal()
    }

    /** [code] is the evdev key code, [hid] its HID usage if mapped. Auto-repeat is not passed in. */
    fun onKey(code: Int, hid: Int?, down: Boolean) {
        if (!down) {
            val (dest, usage) = keys.remove(code) ?: ((if (isRemote) Dest.SWALLOWED else Dest.LOCAL) to hid)
            if (usage != null) when (dest) {
                Dest.LOCAL -> local.key(usage, false, false)
                Dest.REMOTE -> remote.key(usage, false, false)
                Dest.SWALLOWED -> {}
            }
            return
        }
        if (code in keys) return // duplicate down

        val ctrl = held(KEY_LEFTCTRL, KEY_RIGHTCTRL)
        val alt = held(KEY_LEFTALT, KEY_RIGHTALT)
        val shift = held(KEY_LEFTSHIFT, KEY_RIGHTSHIFT)
        if (ctrl && alt) {
            val target = when {
                code == KEY_ESC && shift -> false
                code == KEY_RIGHT && !shift -> true
                code == KEY_LEFT && !shift -> false
                else -> null
            }
            if (target != null) {
                keys[code] = Dest.SWALLOWED to null
                if (target) goRemote() else goLocal()
                return
            }
        }

        val dest = when {
            hid == null -> Dest.SWALLOWED
            isRemote -> Dest.REMOTE.also { remote.key(hid, true, false) }
            else -> Dest.LOCAL.also { local.key(hid, true, false) }
        }
        keys[code] = dest to hid
    }

    /** [button] is a `Wire.BUTTON_*` value. */
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

    /** Releases everything on both sides (capture stopping). */
    fun releaseAll() {
        goLocal()
        local.releaseAll()
        keys.clear()
        buttons.clear()
    }

    private fun held(vararg codes: Int) = codes.any { it in keys }

    private fun goRemote() {
        if (isRemote || !remoteAvailable) return
        isRemote = true
        remote.enter()
        onFocus(true)
    }

    private fun goLocal() {
        if (!isRemote) return
        isRemote = false
        for ((code, entry) in keys.entries.toList()) {
            val (dest, usage) = entry
            if (dest == Dest.REMOTE) {
                if (usage != null) remote.key(usage, false, false)
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
        remote.leave()
        onFocus(false)
    }

    companion object {
        const val KEY_ESC = 1
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTSHIFT = 42
        const val KEY_RIGHTSHIFT = 54
        const val KEY_LEFTALT = 56
        const val KEY_RIGHTCTRL = 97
        const val KEY_RIGHTALT = 100
        const val KEY_LEFT = 105
        const val KEY_RIGHT = 106
    }
}
