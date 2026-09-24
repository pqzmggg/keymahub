package dev.keymahub.poc.input

/**
 * Receiver-owned pointer position for backends that have to draw their own cursor
 * (inject and accessibility). Deltas are scaled by [speed] and clamped to the display.
 */
class Cursor(var width: Int, var height: Int, var speed: Float = 1f) {
    var x = width / 2f
        private set
    var y = height / 2f
        private set

    fun resize(w: Int, h: Int) {
        width = w
        height = h
        x = x.coerceIn(0f, (w - 1).toFloat())
        y = y.coerceIn(0f, (h - 1).toFloat())
    }

    fun move(dx: Int, dy: Int) {
        x = (x + dx * speed).coerceIn(0f, (width - 1).toFloat())
        y = (y + dy * speed).coerceIn(0f, (height - 1).toFloat())
    }

    fun center() {
        x = width / 2f
        y = height / 2f
    }
}
