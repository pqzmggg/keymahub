package app.keymahub.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drag-to-reorder for a column of rows of any height, [gap] apart: a row dragged by its handle
 * follows the finger, the rows it passes make room, and the move is reported on release.
 * Reads live state only, so it is always in step with the frame being drawn.
 */
class Reorder<K>(private val gap: Float) {
    private val heights = mutableStateMapOf<K, Int>()
    private var dragged by mutableStateOf<K?>(null)

    /** How far the dragged row is from its place (px). */
    var offset by mutableFloatStateOf(0f)
        private set

    fun isDragged(key: K) = key == dragged

    /** Index of the dragged row in [keys], or -1 while nothing is dragged. */
    fun from(keys: List<K>) = keys.indexOf(dragged)

    /** Where the dragged row would land in [keys] now, or -1 while nothing is dragged. */
    fun target(keys: List<K>): Int {
        val from = from(keys)
        if (from < 0) return -1
        var y = 0f
        val tops = keys.map { k -> y.also { y += height(k) + gap } }
        val center = tops[from] + offset + height(keys[from]) / 2
        return keys.indices.count { it != from && tops[it] + height(keys[it]) / 2 < center }
    }

    /** How far the row at [index] moves (px) to make room for the dragged one. */
    fun shift(keys: List<K>, index: Int): Float {
        val from = from(keys)
        val to = target(keys)
        val room = if (from < 0) 0f else height(keys[from]) + gap
        return when {
            from < 0 || index == from -> 0f
            index in (from + 1)..to -> -room
            index in to until from -> room
            else -> 0f
        }
    }

    /** Records the row's height; put it on every row. */
    fun measure(key: K) = Modifier.onSizeChanged { heights[key] = it.height }

    /** The drag handle of row [key]; [onMove] gets the old and new index in [keys]. */
    fun handle(key: K, keys: List<K>, onMove: (from: Int, to: Int) -> Unit) = Modifier.pointerInput(key, keys) {
        detectDragGestures(
            onDragStart = { dragged = key; offset = 0f },
            onDragEnd = {
                val from = from(keys)
                val to = target(keys)
                if (to >= 0 && to != from) onMove(from, to)
                dragged = null
                offset = 0f
            },
            onDragCancel = { dragged = null; offset = 0f },
        ) { change, amount ->
            change.consume()
            offset += amount.y
        }
    }

    private fun height(key: K) = (heights[key] ?: 0).toFloat()
}

@Composable
fun <K> rememberReorder(gap: Dp = 0.dp): Reorder<K> {
    val px = with(LocalDensity.current) { gap.toPx() }
    return remember(px) { Reorder(px) }
}
