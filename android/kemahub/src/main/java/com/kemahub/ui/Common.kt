package com.kemahub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kemahub.R
import com.kemahub.core.Profile
import com.kemahub.core.Settings
import com.kemahub.core.TimeRule
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Android 15 draws apps edge to edge: keep clear of the status and navigation bars.
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** A rounded label: a step number, or the key of a hotkey ("2", "F5"). */
@Composable
fun Badge(text: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp),
    ) {
        Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Bold, fontSize = if (text.length <= 2) 18.sp else 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun profileName(p: Profile) = p.name.ifEmpty { stringResource(R.string.profile_default) }

/** Title row of a sub-screen, with a back arrow. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun SectionTitle(text: String, hint: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

fun timeText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

/** "Weekdays", "Mon, Wed", "Every day". */
@Composable
fun daysText(days: Int): String = when (days) {
    TimeRule.ALL -> stringResource(R.string.days_every)
    TimeRule.WEEKDAYS -> stringResource(R.string.days_weekdays)
    TimeRule.WEEKEND -> stringResource(R.string.days_weekend)
    else -> {
        val locale = LocalConfiguration.current.locales[0]
        (1..7).filter { days and (1 shl (it - 1)) != 0 }
            .joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
    }
}

/** Short localized name of ISO day [day] (1 = Monday). */
@Composable
fun dayName(day: Int): String {
    val locale = LocalConfiguration.current.locales[0]
    return DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale)
}

/** One line describing when [p] applies. */
@Composable
fun conditionText(p: Profile, settings: Settings): String {
    val parts = mutableListOf<String>()
    p.time?.let { t ->
        val hours = if (t.start == t.end) stringResource(R.string.time_all_day) else "${timeText(t.start)}–${timeText(t.end)}"
        parts += "${daysText(t.days)} $hours"
    }
    if (p.whenConnected.isNotEmpty()) parts += connectedText(settings, p.whenConnected)
    return if (parts.isEmpty()) stringResource(R.string.condition_always) else parts.joinToString(" · ")
}

/** "When Desk PC or Laptop is connected". */
@Composable
fun connectedText(settings: Settings, addresses: Set<String>): String {
    val names = settings.devices.filter { it.address in addresses }.joinToString(", ") { it.name }
    return stringResource(R.string.condition_devices_on, names)
}

/** Asks for a name. [onConfirm] gets the trimmed text; the button is off while it is empty. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, message: String? = null) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(text, { text = it.take(40) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
