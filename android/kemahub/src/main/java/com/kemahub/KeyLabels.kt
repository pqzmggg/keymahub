package com.kemahub

import android.view.KeyEvent
import com.kemahub.core.Hotkey
import com.kemahub.core.Mods
import com.kemahub.hid.EvdevKeymap
import com.kemahub.hid.HidKeycodes

/** Display names for hotkeys ("Ctrl+Alt+2"). Key names are the same in every language. */
object KeyLabels {
    /** Modifier names as printed on PC / Mac keyboards. */
    const val CTRL = "Ctrl/⌃"
    const val ALT = "Alt/⌥"

    /** The Meta modifier: the Windows key on PC keyboards, Command on Mac ones. */
    const val META = "Win/⌘"

    fun hotkey(h: Hotkey): String = buildList {
        if (h.mods and Mods.CTRL != 0) add(CTRL)
        if (h.mods and Mods.ALT != 0) add(ALT)
        if (h.mods and Mods.SHIFT != 0) add("Shift")
        if (h.mods and Mods.META != 0) add(META)
        add(key(h.code))
    }.joinToString("+")

    /** Short name of evdev key [code], e.g. "2", "F5", "Num 3". */
    fun key(code: Int): String {
        val keycode = EvdevKeymap.toHid(code)?.let { HidKeycodes.toKeycode(it) } ?: return "#$code"
        val name = KeyEvent.keyCodeToString(keycode).removePrefix("KEYCODE_")
        return when {
            name.startsWith("NUMPAD_") -> "Num " + pretty(name.removePrefix("NUMPAD_"))
            else -> pretty(name)
        }
    }

    private fun pretty(name: String) =
        if (name.length <= 3) name else name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
