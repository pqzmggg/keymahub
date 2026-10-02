package app.keymahub.core

import android.content.Context
import android.content.SharedPreferences
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

/** Personalization (Settings screen). The language lives in [app.keymahub.Locales]. */
data class UiPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val showHud: Boolean = true,
    /** Show the on-screen keyboard while the physical one controls another device (Android 10+). */
    val touchKeyboard: Boolean = true,
    /** Cover the phone's screen in black while another device is controlled (capture.ScreenCover). */
    val coverScreen: Boolean = true,
    /** Cover it again after the phone goes untouched for [recoverSeconds], once a touch uncovered it. */
    val recoverScreen: Boolean = true,
    val recoverSeconds: Int = 10,
    /** The setup's optional battery step was skipped: setup no longer waits for it. */
    val batterySkipped: Boolean = false,
    /** The tutorial version last seen or skipped (0: never; see ui.TUTORIAL_VERSION). */
    val tutorialSeen: Int = 0,
    /** Advanced settings (sending over Bluetooth). */
    val tuning: Tuning = Tuning(),
)

/** This app's version name (null if unknown). */
internal fun Context.appVersion(): String? = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

/** The app's one preferences file (settings, personalization and the language). */
internal fun Context.keymaPrefs(): SharedPreferences = getSharedPreferences("keymahub", Context.MODE_PRIVATE)

object Hub {
    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status.asStateFlow()

    fun update(f: (HubStatus) -> HubStatus) = _status.update(f)

    /** Called (on the editing thread) after the active profile changed. */
    @Volatile
    var onProfileChanged: (() -> Unit)? = null

    // ---------------------------------------------------------------- settings (persisted)

    private const val KEY_SETTINGS = "settings"
    private var loaded = false

    fun settings(context: Context): Settings = synchronized(this) {
        if (!loaded) {
            val text = context.keymaPrefs().getString(KEY_SETTINGS, null)
            _status.update { it.copy(settings = Settings.decode(text)) }
            loaded = true
        }
        _status.value.settings
    }

    /** Re-picks the active profile for the devices connected now. */
    fun resolve(context: Context) = edit(context) { it }

    /** Changes the settings (then re-picks the active profile), saves and publishes them. */
    fun edit(context: Context, change: (Settings) -> Settings) {
        val before: Settings
        val after: Settings
        synchronized(this) {
            before = settings(context)
            after = change(before).resolve(_status.value.ready)
            if (after == before) return
            context.keymaPrefs().edit().putString(KEY_SETTINGS, after.encode()).apply()
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
            val p = context.keymaPrefs()
            val d = UiPrefs()
            _ui.value = UiPrefs(
                theme = ThemeMode.entries.firstOrNull { it.name == p.getString(K_THEME, null) } ?: d.theme,
                showHud = p.getBoolean(K_SHOW_HUD, d.showHud),
                touchKeyboard = p.getBoolean(K_TOUCH_KEYBOARD, d.touchKeyboard),
                coverScreen = p.getBoolean(K_COVER_SCREEN, d.coverScreen),
                recoverScreen = p.getBoolean(K_RECOVER_SCREEN, d.recoverScreen),
                recoverSeconds = p.getInt(K_RECOVER_SECONDS, d.recoverSeconds),
                batterySkipped = p.getBoolean(K_BATTERY_SKIPPED, d.batterySkipped),
                tutorialSeen = p.getInt(K_TUTORIAL_SEEN, d.tutorialSeen),
                tuning = Tuning(
                    motionMs = p.getInt(K_MOTION_MS, d.tuning.motionMs),
                    window = p.getInt(K_WINDOW, d.tuning.window),
                    confirmMs = p.getInt(K_CONFIRM_MS, d.tuning.confirmMs),
                    busyRetryMs = p.getInt(K_BUSY_RETRY_MS, d.tuning.busyRetryMs),
                    slowLogMs = p.getInt(K_SLOW_LOG_MS, d.tuning.slowLogMs),
                    le2m = p.getBoolean(K_LE_2M, d.tuning.le2m),
                    unbufferedMouse = p.getBoolean(K_UNBUFFERED_MOUSE, d.tuning.unbufferedMouse),
                ).clamped(),
            )
            uiLoaded = true
        }
        _ui.value
    }

    /** Called (on the editing thread) after the UI preferences changed, with the old and new ones. */
    @Volatile
    var onUiChanged: ((before: UiPrefs, after: UiPrefs) -> Unit)? = null

    fun editUi(context: Context, change: (UiPrefs) -> UiPrefs) {
        val before: UiPrefs
        val after: UiPrefs
        synchronized(this) {
            before = loadUi(context)
            after = saveUi(context, change(before).let { it.copy(tuning = it.tuning.clamped()) })
        }
        if (after != before) onUiChanged?.invoke(before, after)
    }

    /** Holds the lock. */
    private fun saveUi(context: Context, u: UiPrefs): UiPrefs {
        context.keymaPrefs().edit()
            .putString(K_THEME, u.theme.name)
            .putBoolean(K_SHOW_HUD, u.showHud)
            .putBoolean(K_TOUCH_KEYBOARD, u.touchKeyboard)
            .putBoolean(K_COVER_SCREEN, u.coverScreen)
            .putBoolean(K_RECOVER_SCREEN, u.recoverScreen)
            .putInt(K_RECOVER_SECONDS, u.recoverSeconds)
            .putBoolean(K_BATTERY_SKIPPED, u.batterySkipped)
            .putInt(K_TUTORIAL_SEEN, u.tutorialSeen)
            .putInt(K_MOTION_MS, u.tuning.motionMs)
            .putInt(K_WINDOW, u.tuning.window)
            .putInt(K_CONFIRM_MS, u.tuning.confirmMs)
            .putInt(K_BUSY_RETRY_MS, u.tuning.busyRetryMs)
            .putInt(K_SLOW_LOG_MS, u.tuning.slowLogMs)
            .putBoolean(K_LE_2M, u.tuning.le2m)
            .putBoolean(K_UNBUFFERED_MOUSE, u.tuning.unbufferedMouse)
            .apply()
        _ui.value = u
        return u
    }

    private const val K_THEME = "theme"
    private const val K_SHOW_HUD = "show_hud"
    private const val K_TOUCH_KEYBOARD = "touch_keyboard"
    private const val K_COVER_SCREEN = "cover_screen"
    private const val K_RECOVER_SCREEN = "recover_screen"
    private const val K_RECOVER_SECONDS = "recover_seconds"
    private const val K_BATTERY_SKIPPED = "battery_skipped"
    private const val K_TUTORIAL_SEEN = "tutorial_seen"
    private const val K_MOTION_MS = "tuning_motion_ms"
    private const val K_WINDOW = "tuning_window"
    private const val K_CONFIRM_MS = "tuning_confirm_ms"
    private const val K_BUSY_RETRY_MS = "tuning_busy_retry_ms"
    private const val K_SLOW_LOG_MS = "tuning_slow_log_ms"
    private const val K_LE_2M = "tuning_le_2m"
    private const val K_UNBUFFERED_MOUSE = "tuning_unbuffered_mouse"

    // ---------------------------------------------------------------- log (diagnostics screen)

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    fun log(msg: String) {
        Log.i("KeymaHub", msg)
        synchronized(lines) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > 300) lines.removeFirst()
            _log.value = lines.joinToString("\n")
        }
    }

    /** The app was closed. The process lives on with the accessibility service, so the log would too. */
    fun clearLog() {
        synchronized(lines) {
            lines.clear()
            _log.value = ""
        }
    }
}
