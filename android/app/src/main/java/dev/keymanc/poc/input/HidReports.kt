package dev.keymanc.poc.input

/**
 * HID report descriptors and report builders for the UHID backend. The kernel turns
 * these into a real keyboard and mouse, so Android shows its own pointer and applies the
 * user's physical-keyboard layout exactly as with a USB/Bluetooth device.
 */
object HidDescriptors {
    /** Boot-style keyboard, but the key array covers usages 0x00..0xFF (includes LANG1/2). */
    val KEYBOARD = bytes(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01,             // Usage Page (Desktop), Usage (Keyboard), Collection (App)
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7,             //   Usage Page (Keys), Usage Min/Max (modifiers)
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, //   Logical 0..1, 8 x 1 bit
        0x81, 0x02,                                     //   Input (Data, Var, Abs)
        0x75, 0x08, 0x95, 0x01, 0x81, 0x01,             //   1 x 8 bit reserved (Const)
        0x05, 0x08, 0x19, 0x01, 0x29, 0x05,             //   Usage Page (LEDs), 1..5
        0x75, 0x01, 0x95, 0x05, 0x91, 0x02,             //   Output (Data, Var, Abs)
        0x75, 0x03, 0x95, 0x01, 0x91, 0x01,             //   Output padding
        0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00,       //   Usage Page (Keys), Usage 0..255
        0x15, 0x00, 0x26, 0xFF, 0x00,                   //   Logical 0..255
        0x75, 0x08, 0x95, 0x06, 0x81, 0x00,             //   6 x 8 bit, Input (Data, Array)
        0xC0,
    )

    /** 5 buttons, 16-bit relative X/Y, 8-bit wheel and horizontal pan. */
    val MOUSE = bytes(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01,             // Usage Page (Desktop), Usage (Mouse), Collection (App)
        0x09, 0x01, 0xA1, 0x00,                         //   Usage (Pointer), Collection (Physical)
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05,             //     Usage Page (Buttons), 1..5
        0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, //     5 x 1 bit
        0x81, 0x02,                                     //     Input (Data, Var, Abs)
        0x95, 0x01, 0x75, 0x03, 0x81, 0x01,             //     3 bit padding
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31,             //     Usage (X), Usage (Y)
        0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F,             //     Logical -32767..32767
        0x75, 0x10, 0x95, 0x02, 0x81, 0x06,             //     2 x 16 bit, Input (Data, Var, Rel)
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F,             //     Usage (Wheel), -127..127
        0x75, 0x08, 0x95, 0x01, 0x81, 0x06,             //     Input (Data, Var, Rel)
        0x05, 0x0C, 0x0A, 0x38, 0x02,                   //     Usage Page (Consumer), Usage (AC Pan)
        0x15, 0x81, 0x25, 0x7F,                         //     -127..127
        0x75, 0x08, 0x95, 0x01, 0x81, 0x06,             //     Input (Data, Var, Rel)
        0xC0, 0xC0,
    )

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}

/** Tracks pressed keys and produces 8-byte boot keyboard reports. */
class KeyboardReport {
    var modifiers = 0
        private set
    private val keys = ArrayList<Int>(6)

    /** Returns the new report, or null if nothing changed (e.g. repeat, 7th key). */
    fun update(usage: Int, down: Boolean): ByteArray? {
        if (usage !in 0..0xFF) return null
        if (HidKeycodes.isModifier(usage)) {
            val bit = 1 shl (usage - 0xE0)
            val next = if (down) modifiers or bit else modifiers and bit.inv()
            if (next == modifiers) return null
            modifiers = next
            return report()
        }
        if (down) {
            if (usage in keys || keys.size >= 6) return null
            keys.add(usage)
        } else if (!keys.remove(usage)) {
            return null
        }
        return report()
    }

    fun clear(): ByteArray {
        modifiers = 0
        keys.clear()
        return report()
    }

    fun report(): ByteArray {
        val r = ByteArray(8)
        r[0] = modifiers.toByte()
        keys.forEachIndexed { i, k -> r[2 + i] = k.toByte() }
        return r
    }
}

/** Mouse state → 7-byte reports matching [HidDescriptors.MOUSE]. */
class MouseReport {
    var buttons = 0
        private set
    private var wheelAcc = 0
    private var panAcc = 0

    fun setButton(button: Int, down: Boolean): ByteArray? {
        if (button !in 1..5) return null
        val bit = 1 shl (button - 1)
        val next = if (down) buttons or bit else buttons and bit.inv()
        if (next == buttons) return null
        buttons = next
        return report(0, 0, 0, 0)
    }

    /** Large deltas are split so each report stays within the 16-bit logical range. */
    fun move(dx: Int, dy: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(1)
        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-32767, 32767)
            val sy = y.coerceIn(-32767, 32767)
            out += report(sx, sy, 0, 0)
            x -= sx
            y -= sy
        }
        return out
    }

    /** [v]/[h] in 1/120 notch units; fractions accumulate until a whole notch is reached. */
    fun wheel(v: Int, h: Int): ByteArray? {
        wheelAcc += v
        panAcc += h
        val nv = (wheelAcc / 120).coerceIn(-127, 127)
        val nh = (panAcc / 120).coerceIn(-127, 127)
        if (nv == 0 && nh == 0) return null
        wheelAcc -= nv * 120
        panAcc -= nh * 120
        return report(0, 0, nv, nh)
    }

    fun clear(): ByteArray {
        buttons = 0
        wheelAcc = 0
        panAcc = 0
        return report(0, 0, 0, 0)
    }

    private fun report(dx: Int, dy: Int, wheel: Int, pan: Int) = byteArrayOf(
        buttons.toByte(),
        (dx and 0xFF).toByte(), (dx shr 8).toByte(),
        (dy and 0xFF).toByte(), (dy shr 8).toByte(),
        wheel.toByte(),
        pan.toByte(),
    )
}
