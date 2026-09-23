package com.kemahub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kemahub.R
import com.kemahub.core.HubStatus
import com.kemahub.core.Profile
import com.kemahub.core.Settings

/** Main screen: hosting on/off and the profiles in priority order. */
@Composable
fun HomeScreen(
    status: HubStatus,
    log: String,
    onHosting: (Boolean) -> Unit,
    onEdit: ((Settings) -> Settings) -> Unit,
    onOpenProfile: (String) -> Unit,
    onDevices: () -> Unit,
    onSettings: () -> Unit,
    /** Opens an email to the developer with [log] (the user sends it from their mail app). */
    onSendLog: (log: String) -> Unit,
) {
    val settings = status.settings
    var menu by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }

    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.devices_title)) }, onClick = { menu = false; onDevices() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_title)) }, onClick = { menu = false; onSettings() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.report_menu)) }, onClick = { menu = false; showLog = true })
                }
            }
        }

        HostingCard(status, onHosting)

        status.problem?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.profiles_title))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.profile_new))
            }
        }
        Text(
            stringResource(R.string.profiles_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        settings.manualId?.let { id ->
            val p = settings.profile(id)
            if (p != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.manual_banner, profileName(p)),
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton(onClick = { onEdit { it.pin(null) } }) { Text(stringResource(R.string.manual_off)) }
                    }
                }
            }
        }

        settings.profiles.forEachIndexed { index, p ->
            ProfileRow(
                p = p,
                index = index,
                settings = settings,
                onOpen = { onOpenProfile(p.id) },
                onEdit = onEdit,
                onDelete = { deleting = p },
            )
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.profile_new_title),
            initial = "",
            message = stringResource(R.string.profile_new_message),
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                var id = ""
                onEdit { s -> s.addProfile(name).let { (next, newId) -> id = newId; next } }
                if (id.isNotEmpty()) onOpenProfile(id)
            },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.profile_delete_confirm, profileName(p)),
            confirm = stringResource(R.string.action_delete),
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; onEdit { it.deleteProfile(p.id) } },
        )
    }
    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.report_message), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    ) {
                        SelectionContainer(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                            Text(log.ifEmpty { stringResource(R.string.log_empty) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showLog = false; onSendLog(log) }) { Text(stringResource(R.string.report_send)) }
            },
            dismissButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun HostingCard(status: HubStatus, onHosting: (Boolean) -> Unit) {
    val settings = status.settings
    val detail = when {
        !status.running -> stringResource(R.string.hosting_off_hint)
        status.slot == 0 -> stringResource(R.string.status_phone)
        else -> stringResource(
            R.string.status_target,
            settings.active.addressOf(status.slot)?.let { settings.device(it)?.name }.orEmpty(),
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hosting_title), style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
                if (status.running) {
                    Text(
                        pluralStringResource(R.plurals.ready_count, status.ready.size, status.ready.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Switch(checked = status.running, onCheckedChange = onHosting)
        }
    }
}

@Composable
private fun ProfileRow(
    p: Profile,
    index: Int,
    settings: Settings,
    onOpen: () -> Unit,
    onEdit: ((Settings) -> Settings) -> Unit,
    onDelete: () -> Unit,
) {
    val active = p.id == settings.activeId
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = if (active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge("${index + 1}", highlighted = active)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profileName(p), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (settings.manualId == p.id) R.string.profile_in_use_manual else R.string.profile_in_use),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(conditionText(p), style = MaterialTheme.typography.bodySmall)
                Text(
                    pluralStringResource(R.plurals.receiver_count, p.receivers.size, p.receivers.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_use_now)) },
                        enabled = settings.manualId != p.id,
                        onClick = { menu = false; onEdit { it.pin(p.id) } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_move_up)) },
                        enabled = index > 0,
                        onClick = { menu = false; onEdit { it.moveProfile(p.id, -1) } },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_move_down)) },
                        enabled = index < settings.profiles.size - 1,
                        onClick = { menu = false; onEdit { it.moveProfile(p.id, 1) } },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_delete)) },
                        enabled = settings.profiles.size > 1,
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
