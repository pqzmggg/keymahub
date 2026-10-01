package app.keymahub

import android.view.KeyEvent
import app.keymahub.core.Hotkey
import app.keymahub.core.Mods
import app.keymahub.hid.EvdevKeymap
import app.keymahub.hid.HidKeycodes

/** Display names for hotkeys ("Ctrl + Alt + 2"): keys joined by [SEP]. Key names are the same in every language. */
object KeyLabels {
    /** Modifier names as printed on PC / Mac keyboards. */
    private const val CTRL = "Ctrl/⌃"
    private const val ALT = "Alt/⌥"

    /** The Meta modifier: the Windows key on PC keyboards, Command on Mac ones. */
    private const val META = "Win/⌘"

    /** Between the keys of a combination, spaced so the combination reads easily. */
    private const val SEP = " + "

    /** Each modifier ([Mods] bit) and its name, in the order they are written. */
    val MODS = listOf(Mods.CTRL to CTRL, Mods.ALT to ALT, Mods.SHIFT to "Shift", Mods.META to META)

    fun hotkey(h: Hotkey): String = listOf(mods(h.mods), key(h.code)).filter { it.isNotEmpty() }.joinToString(SEP)

    /** The modifier keys of [mods] ([Mods] bits), e.g. "Alt/⌥ + Shift". */
    fun mods(mods: Int): String = MODS.filter { (bit, _) -> mods and bit != 0 }.joinToString(SEP) { it.second }

    /** Short name of evdev key [code], e.g. "2", "F5", "Num 3". */
    fun key(code: Int): String {
        val keycode = EvdevKeymap.toHid(code)?.let { HidKeycodes.toKeycode(it) } ?: return "#$code"
        val name = KeyEvent.keyCodeToString(keycode).removePrefix("KEYCODE_")
        return if (name.startsWith("NUMPAD_")) "Num " + pretty(name.removePrefix("NUMPAD_")) else pretty(name)
    }

    private fun pretty(name: String) =
        if (name.length <= 3) name else name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
