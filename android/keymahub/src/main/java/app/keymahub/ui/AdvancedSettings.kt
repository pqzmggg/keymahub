package app.keymahub.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.keymahub.R
import app.keymahub.core.Tuning
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon

/**
 * Advanced settings: the numbers and switches of sending over Bluetooth ([Tuning]), each with
 * its recommended value, its range and a "?" that says what it does. Folded until opened. A
 * value within range applies as soon as it is typed.
 */
@Composable
fun AdvancedCard(tuning: Tuning, onTuning: ((Tuning) -> Tuning) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        // Opens and closes like a list group (a chevron, not a switch: nothing is turned on here).
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_advanced_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!open) return@Card

        val ms = stringResource(R.string.unit_ms)
        HorizontalDivider()
        NumberItem(R.string.tuning_motion, R.string.tuning_motion_help, ms, Tuning.MOTION, tuning.motionMs) { v ->
            onTuning { it.copy(motionMs = v) }
        }
        HorizontalDivider()
        NumberItem(R.string.tuning_window, R.string.tuning_window_help, stringResource(R.string.unit_reports), Tuning.WINDOW, tuning.window) { v ->
            onTuning { it.copy(window = v) }
        }
        HorizontalDivider()
        NumberItem(R.string.tuning_confirm, R.string.tuning_confirm_help, ms, Tuning.CONFIRM, tuning.confirmMs) { v ->
            onTuning { it.copy(confirmMs = v) }
        }
        HorizontalDivider()
        NumberItem(R.string.tuning_busy_retry, R.string.tuning_busy_retry_help, ms, Tuning.BUSY_RETRY, tuning.busyRetryMs) { v ->
            onTuning { it.copy(busyRetryMs = v) }
        }
        HorizontalDivider()
        NumberItem(R.string.tuning_slow_log, R.string.tuning_slow_log_help, ms, Tuning.SLOW_LOG, tuning.slowLogMs) { v ->
            onTuning { it.copy(slowLogMs = v) }
        }
        HorizontalDivider()
        SwitchItem(R.string.tuning_le2m, R.string.tuning_le2m_help, tuning.le2m) { on -> onTuning { it.copy(le2m = on) } }
        if (Build.VERSION.SDK_INT >= 30) {
            HorizontalDivider()
            SwitchItem(R.string.tuning_unbuffered, R.string.tuning_unbuffered_help, tuning.unbufferedMouse) { on ->
                onTuning { it.copy(unbufferedMouse = on) }
            }
        }
        HorizontalDivider()
        TextButton(onClick = { onTuning { Tuning() } }, modifier = Modifier.padding(8.dp)) {
            Text(stringResource(R.string.tuning_reset))
        }
    }
}

/** A number of [spec]'s range, in [unit]: applied (via [onValue]) as soon as what is typed is within it. */
@Composable
private fun NumberItem(title: Int, help: Int, unit: String, spec: Tuning.Spec, value: Int, onValue: (Int) -> Unit) {
    // What is typed; follows the value when it changes elsewhere (reset).
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in spec } == true
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            TitleWithHelp(stringResource(title), stringResource(help))
            Text(
                stringResource(R.string.tuning_range, spec.recommended, spec.min, spec.max, unit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!valid) {
                Text(stringResource(R.string.tuning_out_of_range), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { typed: String ->
                text = typed.filter(Char::isDigit).take(4)
                text.toIntOrNull()?.takeIf { it in spec && it != value }?.let(onValue)
            },
            modifier = Modifier.width(96.dp),
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** An on/off setting (recommended: on). */
@Composable
private fun SwitchItem(title: Int, help: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            TitleWithHelp(stringResource(title), stringResource(help))
            Text(stringResource(R.string.tuning_recommended_on), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** [title] and a "?" button that shows [help] in a dialog. */
@Composable
private fun TitleWithHelp(title: String, help: String) {
    var showing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
        HelpButton(onClick = { showing = true })
    }
    if (showing) {
        val close = { showing = false }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(title) },
            text = { Text(help, Modifier.dialogKeys(onEnter = close, onEscape = close), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
