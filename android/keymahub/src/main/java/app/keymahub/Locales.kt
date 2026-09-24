package app.keymahub

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app language. Android 13+ keeps it as the per-app language (also in system settings);
 * older versions keep it here and every screen/service wraps its context with [wrap].
 */
object Locales {
    /** Language tag and its name in that language ("" = follow the system). */
    val SUPPORTED = listOf(
        "en" to "English",
        "ko" to "한국어",
        "ja" to "日本語",
        "zh" to "中文",
        "es" to "Español",
        "fr" to "Français",
    )

    private const val PREFS = "keymahub"
    private const val KEY = "language"

    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        val tags = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: LocaleList.getEmptyLocaleList()
        if (tags.isEmpty) "" else tags[0].language
    } else {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system restarts the activity in the new language.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** For attachBaseContext before Android 13. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}
