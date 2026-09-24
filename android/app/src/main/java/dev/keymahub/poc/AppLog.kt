package dev.keymahub.poc

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-app log shown on the main screen (and mirrored to logcat under tag "keymahub"). */
object AppLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(msg: String) {
        Log.i("keymahub", msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > MAX) lines.removeFirst()
        }
        listeners.forEach { it() }
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun listen(l: () -> Unit) = listeners.add(l)

    fun unlisten(l: () -> Unit) = listeners.remove(l)
}
