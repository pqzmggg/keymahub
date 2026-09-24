package dev.keymahub.poc.host

import java.math.BigInteger

/** One entry of `/proc/bus/input/devices`. */
data class InputDeviceInfo(
    val name: String,
    /** `eventN` handler, i.e. `/dev/input/eventN`. */
    val node: String,
    val ev: BigInteger,
    val key: BigInteger,
    val rel: BigInteger,
) {
    private fun has(bits: BigInteger, bit: Int) = bits.testBit(bit)

    /** A full keyboard (has letters and space), not volume/power buttons. */
    val isKeyboard get() = has(ev, EV_KEY) && has(key, KEY_A) && has(key, KEY_Z) && has(key, KEY_SPACE)

    /** A relative pointer with a left button. */
    val isMouse get() = has(ev, EV_REL) && has(rel, REL_X) && has(rel, REL_Y) && has(key, BTN_LEFT)

    /** Our own UHID devices must never be captured (that would loop). */
    val isOurs get() = name.startsWith("keymahub")

    companion object {
        const val EV_KEY = 0x01
        const val EV_REL = 0x02
        const val KEY_A = 30
        const val KEY_Z = 44
        const val KEY_SPACE = 57
        const val BTN_LEFT = 0x110
        const val REL_X = 0
        const val REL_Y = 1

        /**
         * Parses the text of `/proc/bus/input/devices`. Bitmaps are printed as hex words,
         * most significant first, each word [wordBits] wide (the kernel's `long`).
         */
        fun parse(text: String, wordBits: Int): List<InputDeviceInfo> = text.split("\n\n").mapNotNull { block ->
            var name = ""
            var node: String? = null
            var ev = BigInteger.ZERO
            var key = BigInteger.ZERO
            var rel = BigInteger.ZERO
            for (line in block.lines()) {
                when {
                    line.startsWith("N: Name=") -> name = line.removePrefix("N: Name=").trim().trim('"')
                    line.startsWith("H: Handlers=") ->
                        node = line.removePrefix("H: Handlers=").split(' ').firstOrNull { it.startsWith("event") }
                    line.startsWith("B: EV=") -> ev = bitmap(line.removePrefix("B: EV="), wordBits)
                    line.startsWith("B: KEY=") -> key = bitmap(line.removePrefix("B: KEY="), wordBits)
                    line.startsWith("B: REL=") -> rel = bitmap(line.removePrefix("B: REL="), wordBits)
                }
            }
            node?.let { InputDeviceInfo(name, it, ev, key, rel) }
        }

        private fun bitmap(words: String, wordBits: Int): BigInteger {
            var acc = BigInteger.ZERO
            for (w in words.trim().split(' ').filter { it.isNotEmpty() }) {
                acc = acc.shiftLeft(wordBits).or(BigInteger(w, 16))
            }
            return acc
        }
    }
}
