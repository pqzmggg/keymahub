package com.kemahub.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the UI shows; written by the service, read by the UI. */
data class HubStatus(
    val running: Boolean = false,
    /** 0 = this phone, 1..9 = target slot with focus. */
    val slot: Int = 0,
    /** Addresses of targets connected and ready for input. */
    val ready: Set<String> = emptySet(),
    val targets: List<Target> = emptyList(),
    /** Last problem worth showing (null when fine). */
    val problem: String? = null,
)

object Hub {
    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status.asStateFlow()

    fun update(f: (HubStatus) -> HubStatus) = _status.update(f)

    // ---------------------------------------------------------------- targets (persisted)

    private const val PREFS = "kemahub"
    private const val KEY_TARGETS = "targets"
    private var table: SlotTable? = null

    private fun load(context: Context): SlotTable = table ?: SlotTable.decode(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TARGETS, null),
    ).also {
        table = it
        _status.update { s -> s.copy(targets = it.all) }
    }

    fun targets(context: Context): SlotTable = synchronized(this) { load(context) }

    /** Changes the slot table, saves it and publishes it. */
    fun editTargets(context: Context, edit: (SlotTable) -> Unit) = synchronized(this) {
        val t = load(context)
        edit(t)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TARGETS, t.encode()).apply()
        _status.update { it.copy(targets = t.all) }
    }

    // ---------------------------------------------------------------- log (diagnostics screen)

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    fun log(msg: String) {
        Log.i("KemaHub", msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > 300) lines.removeFirst()
            _log.value = lines.joinToString("\n")
        }
    }
}
