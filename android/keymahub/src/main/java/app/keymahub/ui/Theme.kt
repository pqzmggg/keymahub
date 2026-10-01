package app.keymahub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import app.keymahub.core.ThemeMode
import app.keymahub.core.UiPrefs

/** Light or dark as [ui] says. */
@Composable
internal fun KeymaTheme(ui: UiPrefs, content: @Composable () -> Unit) {
    val dark = when (ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // The app's own colors, not the wallpaper's (dynamic color): the same look on every phone.
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}
