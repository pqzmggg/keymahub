package com.kemahub.hid

import android.view.KeyEvent

/**
 * HID keyboard usage (page 0x07) ↔ Android `KeyEvent.KEYCODE_*`. Capture maps keys by
 * scan code ([EvdevKeymap]); this table is the fallback for keys without a scan code.
 */
object HidKeycodes {
    const val LANG1_HANGUL = 0x90
    const val LANG2_HANJA = 0x91
    const val LEFT_CTRL = 0xE0
    const val LEFT_SHIFT = 0xE1
    const val SPACE = 0x2C

    private val table: Map<Int, Int> = buildMap {
        for (i in 0 until 26) put(0x04 + i, KeyEvent.KEYCODE_A + i)
        for (i in 0 until 9) put(0x1E + i, KeyEvent.KEYCODE_1 + i)
        put(0x27, KeyEvent.KEYCODE_0)
        put(0x28, KeyEvent.KEYCODE_ENTER)
        put(0x29, KeyEvent.KEYCODE_ESCAPE)
        put(0x2A, KeyEvent.KEYCODE_DEL)
        put(0x2B, KeyEvent.KEYCODE_TAB)
        put(0x2C, KeyEvent.KEYCODE_SPACE)
        put(0x2D, KeyEvent.KEYCODE_MINUS)
        put(0x2E, KeyEvent.KEYCODE_EQUALS)
        put(0x2F, KeyEvent.KEYCODE_LEFT_BRACKET)
        put(0x30, KeyEvent.KEYCODE_RIGHT_BRACKET)
        put(0x31, KeyEvent.KEYCODE_BACKSLASH)
        put(0x33, KeyEvent.KEYCODE_SEMICOLON)
        put(0x34, KeyEvent.KEYCODE_APOSTROPHE)
        put(0x35, KeyEvent.KEYCODE_GRAVE)
        put(0x36, KeyEvent.KEYCODE_COMMA)
        put(0x37, KeyEvent.KEYCODE_PERIOD)
        put(0x38, KeyEvent.KEYCODE_SLASH)
        put(0x39, KeyEvent.KEYCODE_CAPS_LOCK)
        for (i in 0 until 12) put(0x3A + i, KeyEvent.KEYCODE_F1 + i)
        put(0x46, KeyEvent.KEYCODE_SYSRQ)
        put(0x47, KeyEvent.KEYCODE_SCROLL_LOCK)
        put(0x48, KeyEvent.KEYCODE_BREAK)
        put(0x49, KeyEvent.KEYCODE_INSERT)
        put(0x4A, KeyEvent.KEYCODE_MOVE_HOME)
        put(0x4B, KeyEvent.KEYCODE_PAGE_UP)
        put(0x4C, KeyEvent.KEYCODE_FORWARD_DEL)
        put(0x4D, KeyEvent.KEYCODE_MOVE_END)
        put(0x4E, KeyEvent.KEYCODE_PAGE_DOWN)
        put(0x4F, KeyEvent.KEYCODE_DPAD_RIGHT)
        put(0x50, KeyEvent.KEYCODE_DPAD_LEFT)
        put(0x51, KeyEvent.KEYCODE_DPAD_DOWN)
        put(0x52, KeyEvent.KEYCODE_DPAD_UP)
        put(0x53, KeyEvent.KEYCODE_NUM_LOCK)
        put(0x54, KeyEvent.KEYCODE_NUMPAD_DIVIDE)
        put(0x55, KeyEvent.KEYCODE_NUMPAD_MULTIPLY)
        put(0x56, KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
        put(0x57, KeyEvent.KEYCODE_NUMPAD_ADD)
        put(0x58, KeyEvent.KEYCODE_NUMPAD_ENTER)
        for (i in 0 until 9) put(0x59 + i, KeyEvent.KEYCODE_NUMPAD_1 + i)
        put(0x62, KeyEvent.KEYCODE_NUMPAD_0)
        put(0x63, KeyEvent.KEYCODE_NUMPAD_DOT)
        put(0x65, KeyEvent.KEYCODE_MENU)
        put(0x66, KeyEvent.KEYCODE_POWER)
        put(0x67, KeyEvent.KEYCODE_NUMPAD_EQUALS)
        put(0x7F, KeyEvent.KEYCODE_VOLUME_MUTE)
        put(0x80, KeyEvent.KEYCODE_VOLUME_UP)
        put(0x81, KeyEvent.KEYCODE_VOLUME_DOWN)
        put(0x85, KeyEvent.KEYCODE_NUMPAD_COMMA)
        put(0x87, KeyEvent.KEYCODE_RO)
        put(0x88, KeyEvent.KEYCODE_KATAKANA_HIRAGANA)
        put(0x89, KeyEvent.KEYCODE_YEN)
        put(0x8A, KeyEvent.KEYCODE_HENKAN)
        put(0x8B, KeyEvent.KEYCODE_MUHENKAN)
        put(LANG1_HANGUL, KeyEvent.KEYCODE_LANGUAGE_SWITCH)
        put(0xE0, KeyEvent.KEYCODE_CTRL_LEFT)
        put(0xE1, KeyEvent.KEYCODE_SHIFT_LEFT)
        put(0xE2, KeyEvent.KEYCODE_ALT_LEFT)
        put(0xE3, KeyEvent.KEYCODE_META_LEFT)
        put(0xE4, KeyEvent.KEYCODE_CTRL_RIGHT)
        put(0xE5, KeyEvent.KEYCODE_SHIFT_RIGHT)
        put(0xE6, KeyEvent.KEYCODE_ALT_RIGHT)
        put(0xE7, KeyEvent.KEYCODE_META_RIGHT)
    }

    fun toKeycode(usage: Int): Int? = table[usage]

    private val reverse: Map<Int, Int> by lazy { table.entries.associate { (usage, code) -> code to usage } }

    fun fromKeycode(keycode: Int): Int? = reverse[keycode]

    fun isModifier(usage: Int) = usage in 0xE0..0xE7

    /** Android meta state for a HID modifier byte (bit 0 = LCtrl … bit 7 = RGUI). */
    fun metaState(modifiers: Int): Int {
        var m = 0
        if (modifiers and 0x01 != 0) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (modifiers and 0x02 != 0) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (modifiers and 0x04 != 0) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (modifiers and 0x08 != 0) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (modifiers and 0x10 != 0) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        if (modifiers and 0x20 != 0) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        if (modifiers and 0x40 != 0) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        if (modifiers and 0x80 != 0) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_RIGHT_ON
        return m
    }
}
