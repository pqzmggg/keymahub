package com.kemahub.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kemahub.KeyLabels
import com.kemahub.Locales
import com.kemahub.R
import com.kemahub.core.ActivationMode
import com.kemahub.core.Hotkeys
import com.kemahub.core.Mods
import com.kemahub.core.Settings
import com.kemahub.core.ThemeMode
import com.kemahub.core.UiPrefs

/** App-wide settings: hotkey modifiers, language, theme, colors, switch popup. */
@Composable
fun SettingsScreen(
    ui: UiPrefs,
    mode: ActivationMode,
    onMode: (ActivationMode) -> Unit,
    /** Hotkey modifiers ([Mods] bits). */
    mods: Int,
    onMods: (Int) -> Unit,
    language: String,
    onBack: () -> Unit,
    onUi: ((UiPrefs) -> UiPrefs) -> Unit,
    onLanguage: (String) -> Unit,
) {
    var pickingLanguage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val languageName = Locales.SUPPORTED.firstOrNull { it.first == language }?.second ?: stringResource(R.string.language_system)

    Page {
        TopBar(stringResource(R.string.settings_title), onBack)

        SectionTitle(stringResource(R.string.settings_activation))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                for ((m, label, hint) in listOf(
                    Triple(ActivationMode.AUTO, R.string.mode_auto, R.string.mode_auto_hint),
                    Triple(ActivationMode.RULES, R.string.mode_rules, R.string.mode_rules_hint),
                    Triple(ActivationMode.MANUAL, R.string.mode_manual, R.string.mode_manual_hint),
                )) {
                    Choice(stringResource(label), stringResource(hint), selected = mode == m) { onMode(m) }
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_hotkeys))
        HotkeyCard(mods, onMods)

        SectionTitle(stringResource(R.string.settings_appearance))
        Card(Modifier.fillMaxWidth()) {
            Item(stringResource(R.string.language), languageName) { pickingLanguage = true }
            HorizontalDivider()
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
                for ((mode, label) in listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                )) {
                    Choice(stringResource(label), null, selected = ui.theme == mode) { onUi { it.copy(theme = mode) } }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                HorizontalDivider()
                Toggle(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_hint), ui.dynamicColor) { on ->
                    onUi { it.copy(dynamicColor = on) }
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_behavior))
        Card(Modifier.fillMaxWidth()) {
            Toggle(stringResource(R.string.show_hud), stringResource(R.string.show_hud_hint), ui.showHud) { on ->
                onUi { it.copy(showHud = on) }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                HorizontalDivider()
                Toggle(stringResource(R.string.touch_keyboard), stringResource(R.string.touch_keyboard_hint), ui.touchKeyboard) { on ->
                    onUi { it.copy(touchKeyboard = on) }
                }
            }
        }

        Text(
            stringResource(R.string.version, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (pickingLanguage) {
        AlertDialog(
            onDismissRequest = { pickingLanguage = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Choice(stringResource(R.string.language_system), null, selected = language.isEmpty()) {
                        pickingLanguage = false
                        onLanguage("")
                    }
                    for ((tag, name) in Locales.SUPPORTED) {
                        Choice(name, null, selected = language == tag) {
                            pickingLanguage = false
                            onLanguage(tag)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingLanguage = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun Item(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Toggle(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** The modifier keys held with a number; the numbers themselves are fixed. */
@Composable
private fun HotkeyCard(mods: Int, onMods: (Int) -> Unit) {
    var pending by remember(mods) { mutableIntStateOf(mods) }
    val valid = Hotkeys.validMods(pending)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hotkeys_mods_hint), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((bit, label) in listOf(Mods.CTRL to "Ctrl", Mods.ALT to "Alt", Mods.SHIFT to "Shift", Mods.META to "Meta")) {
                    FilterChip(
                        selected = pending and bit != 0,
                        onClick = {
                            pending = pending xor bit
                            if (Hotkeys.validMods(pending)) onMods(pending)
                        },
                        label = { Text(label) },
                    )
                }
            }
            if (valid) {
                val one = Settings(mods = pending)
                Text("${KeyLabels.hotkey(one.hotkey(0))}   ${stringResource(R.string.this_phone)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${KeyLabels.hotkey(one.hotkey(1))} … ${KeyLabels.key(Hotkeys.code(9))}   ${stringResource(R.string.hotkeys_devices)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(stringResource(R.string.hotkeys_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
