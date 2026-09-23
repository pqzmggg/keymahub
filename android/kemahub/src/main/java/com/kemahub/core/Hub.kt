package com.kemahub.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/** What the UI shows; written by the service, read by the UI. */
data class HubStatus(
    val running: Boolean = false,
    /** 0 = this phone, 1..9 = receiver slot with focus. */
    val slot: Int = 0,
    /** Addresses of receivers connected and ready for input. */
    val ready: Set<String> = emptySet(),
    val settings: Settings = Settings(),
    /** Last problem worth showing, as a string resource (null when fine). */
    val problem: Int? = null,
    /** Pairing mode ends at this uptime (SystemClock.elapsedRealtime), 0 = off. */
    val pairingUntil: Long = 0,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Personalization (Settings screen). The language lives in [com.kemahub.Locales]. */
data class UiPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showHud: Boolean = true,
    /** Show the on-screen keyboard while the physical one controls another device (Android 10+). */
    val touchKeyboard: Boolean = true,
)

object Hub {
    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status.asStateFlow()

    fun update(f: (HubStatus) -> HubStatus) = _status.update(f)

    /** Called (on the editing thread) after the active profile changed. */
    @Volatile
    var onProfileChanged: (() -> Unit)? = null

    // ---------------------------------------------------------------- settings (persisted)

    private const val PREFS = "kemahub"
    private const val KEY_SETTINGS = "settings"
    private var loaded = false

    fun settings(context: Context): Settings = synchronized(this) {
        if (!loaded) {
            val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SETTINGS, null)
            _status.update { it.copy(settings = Settings.decode(text)) }
            loaded = true
        }
        _status.value.settings
    }

    /** Re-picks the active profile for the current time and the devices connected now. */
    fun resolve(context: Context) = edit(context) { it }

    /** Changes the settings (then re-picks the active profile), saves and publishes them. */
    fun edit(context: Context, change: (Settings) -> Settings) {
        val before: Settings
        val after: Settings
        synchronized(this) {
            before = settings(context)
            val now = LocalDateTime.now()
            after = change(before).resolve(now.dayOfWeek.value, now.hour * 60 + now.minute, _status.value.ready)
            if (after == before) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SETTINGS, after.encode()).apply()
            _status.update { it.copy(settings = after) }
        }
        if (after.activeId != before.activeId) onProfileChanged?.invoke()
    }

    // ---------------------------------------------------------------- personalization

    private val _ui = MutableStateFlow(UiPrefs())
    val ui: StateFlow<UiPrefs> = _ui.asStateFlow()
    private var uiLoaded = false

    fun loadUi(context: Context): UiPrefs = synchronized(this) {
        if (!uiLoaded) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _ui.value = UiPrefs(
                theme = ThemeMode.entries.firstOrNull { it.name == p.getString("theme", null) } ?: ThemeMode.SYSTEM,
                dynamicColor = p.getBoolean("dynamic_color", true),
                showHud = p.getBoolean("show_hud", true),
                touchKeyboard = p.getBoolean("touch_keyboard", true),
            )
            uiLoaded = true
        }
        _ui.value
    }

    fun editUi(context: Context, change: (UiPrefs) -> UiPrefs) = synchronized(this) {
        val u = change(loadUi(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme", u.theme.name)
            .putBoolean("dynamic_color", u.dynamicColor)
            .putBoolean("show_hud", u.showHud)
            .putBoolean("touch_keyboard", u.touchKeyboard)
            .apply()
        _ui.value = u
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
