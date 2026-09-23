package com.kemahub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kemahub.KeyLabels
import com.kemahub.R
import com.kemahub.core.Device
import com.kemahub.core.HubStatus
import com.kemahub.core.Profile
import com.kemahub.core.Settings

private enum class ProfileAction { NEW, RENAME, DELETE }

@Composable
fun HomeScreen(
    status: HubStatus,
    log: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: ((Settings) -> Settings) -> Unit,
) {
    val settings = status.settings
    val profile = settings.active
    var showGuide by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var profileAction by remember { mutableStateOf<ProfileAction?>(null) }
    var hotkeyFor by remember { mutableStateOf<Int?>(null) }
    var assignFor by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Device?>(null) }
    var forgetting by remember { mutableStateOf<Device?>(null) }
    var resetting by remember { mutableStateOf(false) }

    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showLog = true }) { Text(stringResource(R.string.diagnostics)) }
        }

        StatusCard(status, onStart, onStop)

        status.problem?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        ProfileCard(settings, onSelect = { id -> onEdit { it.select(id) } }, onAction = { profileAction = it })

        Text(stringResource(R.string.hotkeys_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.hotkeys_hint), style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            for (slot in 0 until Profile.SLOTS) {
                if (slot > 0) HorizontalDivider()
                SlotRow(
                    slot, status,
                    onHotkey = { hotkeyFor = slot },
                    onAssign = { assignFor = slot },
                    onClear = { onEdit { it.assign(profile.id, slot, null) } },
                )
            }
        }
        TextButton(onClick = { resetting = true }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.hotkeys_reset))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showGuide = true }) { Text(stringResource(R.string.devices_add)) }
        }
        if (settings.devices.isEmpty()) {
            Text(
                stringResource(if (status.running) R.string.devices_empty_running else R.string.devices_empty_off),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        for (d in settings.devices) {
            DeviceRow(d, status, onRename = { renaming = d }, onForget = { forgetting = d })
        }
    }

    when (profileAction) {
        ProfileAction.NEW -> NameDialog(
            title = stringResource(R.string.profile_new_title),
            initial = "",
            message = stringResource(R.string.profile_new_message),
            onDismiss = { profileAction = null },
            onConfirm = { name -> profileAction = null; onEdit { it.addProfile(name) } },
        )
        ProfileAction.RENAME -> NameDialog(
            title = stringResource(R.string.profile_rename),
            initial = profileName(profile),
            onDismiss = { profileAction = null },
            onConfirm = { name -> profileAction = null; onEdit { it.renameProfile(profile.id, name) } },
        )
        ProfileAction.DELETE -> ConfirmDialog(
            title = stringResource(R.string.profile_delete_confirm, profileName(profile)),
            confirm = stringResource(R.string.action_delete),
            onDismiss = { profileAction = null },
            onConfirm = { profileAction = null; onEdit { it.deleteProfile(profile.id) } },
        )
        null -> {}
    }
    hotkeyFor?.let { slot ->
        HotkeyDialog(
            slot = slot,
            status = status,
            onDismiss = { hotkeyFor = null },
            onSave = { h -> hotkeyFor = null; onEdit { it.setHotkey(profile.id, slot, h) } },
        )
    }
    assignFor?.let { slot ->
        AssignDialog(
            slot = slot,
            settings = settings,
            onDismiss = { assignFor = null },
            onPick = { address -> assignFor = null; onEdit { it.assign(profile.id, slot, address) } },
        )
    }
    renaming?.let { d ->
        NameDialog(
            title = stringResource(R.string.device_rename),
            initial = d.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; onEdit { it.renameDevice(d.address, name) } },
        )
    }
    forgetting?.let { d ->
        ConfirmDialog(
            title = stringResource(R.string.device_forget_confirm, d.name),
            confirm = stringResource(R.string.device_forget),
            onDismiss = { forgetting = null },
            onConfirm = { forgetting = null; onEdit { it.forgetDevice(d.address) } },
        )
    }
    if (resetting) {
        ConfirmDialog(
            title = stringResource(R.string.hotkeys_reset_confirm),
            confirm = stringResource(R.string.hotkeys_reset),
            onDismiss = { resetting = false },
            onConfirm = { resetting = false; onEdit { it.resetHotkeys(profile.id) } },
        )
    }
    if (showGuide) AddDeviceGuide { showGuide = false }
    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource(R.string.diagnostics_title)) },
            text = {
                SelectionContainer(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                    Text(log.ifEmpty { stringResource(R.string.log_empty) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun StatusCard(status: HubStatus, onStart: () -> Unit, onStop: () -> Unit) {
    val settings = status.settings
    val focus = when {
        !status.running -> stringResource(R.string.status_off)
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
                Text(focus, style = MaterialTheme.typography.titleLarge)
                if (status.running) {
                    Text(
                        pluralStringResource(R.plurals.ready_count, status.ready.size, status.ready.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (status.running) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            } else {
                Button(onClick = onStart) { Text(stringResource(R.string.action_start)) }
            }
        }
    }
}

@Composable
private fun ProfileCard(settings: Settings, onSelect: (String) -> Unit, onAction: (ProfileAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.profile_label), style = MaterialTheme.typography.labelMedium)
                Text(profileName(settings.active), style = MaterialTheme.typography.titleMedium)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text(stringResource(R.string.profile_change)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (p in settings.profiles) {
                        DropdownMenuItem(
                            text = { Text(profileName(p)) },
                            leadingIcon = { if (p.id == settings.activeId) Icon(Icons.Default.Check, contentDescription = null) },
                            onClick = { menu = false; onSelect(p.id) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.profile_new)) }, onClick = { menu = false; onAction(ProfileAction.NEW) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.profile_rename)) }, onClick = { menu = false; onAction(ProfileAction.RENAME) })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_delete)) },
                        enabled = settings.profiles.size > 1,
                        onClick = { menu = false; onAction(ProfileAction.DELETE) },
                    )
                }
            }
        }
    }
}

/** One hotkey: slot 0 is this phone, 1..9 a receiver. */
@Composable
private fun SlotRow(slot: Int, status: HubStatus, onHotkey: () -> Unit, onAssign: () -> Unit, onClear: () -> Unit) {
    val settings = status.settings
    val profile = settings.active
    val hotkey = profile.hotkeys[slot]
    val address = profile.addressOf(slot)
    val active = status.running && status.slot == slot
    val highlight = active || (address != null && address in status.ready)
    var menu by remember { mutableStateOf(false) }

    val title = when {
        slot == 0 -> stringResource(R.string.this_phone)
        address == null -> stringResource(R.string.slot_empty)
        else -> settings.device(address)?.name.orEmpty()
    }
    val state = when {
        active -> stringResource(R.string.state_controlling)
        slot == 0 || address == null -> null
        address in status.ready -> stringResource(R.string.state_connected)
        else -> stringResource(R.string.state_not_connected)
    }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .clickable { if (slot == 0) onHotkey() else menu = true }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge(KeyLabels.key(hotkey.code), highlighted = active)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (slot != 0 && address == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    listOfNotNull(KeyLabels.hotkey(hotkey), state).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.assign_device)) }, onClick = { menu = false; onAssign() })
            DropdownMenuItem(text = { Text(stringResource(R.string.change_hotkey)) }, onClick = { menu = false; onHotkey() })
            if (address != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.unassign)) }, onClick = { menu = false; onClear() })
            }
        }
    }
}

@Composable
private fun AssignDialog(slot: Int, settings: Settings, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val profile = settings.active
    val current = profile.addressOf(slot)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assign_title, KeyLabels.hotkey(profile.hotkeys[slot]))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (settings.devices.isEmpty()) Text(stringResource(R.string.assign_no_devices))
                for (d in settings.devices) {
                    val other = profile.slotOf(d.address)?.takeIf { it != slot }
                    Choice(
                        label = d.name,
                        detail = other?.let { stringResource(R.string.assign_now_on, KeyLabels.hotkey(profile.hotkeys[it])) },
                        selected = d.address == current,
                        onClick = { onPick(d.address) },
                    )
                }
                Choice(stringResource(R.string.assign_none), null, selected = current == null, onClick = { onPick(null) })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.assign_swap_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun Choice(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DeviceRow(d: Device, status: HubStatus, onRename: () -> Unit, onForget: () -> Unit) {
    val profile = status.settings.active
    val connected = d.address in status.ready
    val slot = profile.slotOf(d.address)
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(
                        stringResource(if (connected) R.string.state_connected else R.string.state_not_connected),
                        slot?.let { KeyLabels.hotkey(profile.hotkeys[it]) } ?: stringResource(R.string.device_unassigned),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_forget)) }, onClick = { menu = false; onForget() })
                }
            }
        }
    }
}

@Composable
private fun AddDeviceGuide(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guide_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.guide_intro))
                Text("Windows", fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.guide_windows))
                Text(stringResource(R.string.guide_windows_hint), style = MaterialTheme.typography.bodySmall)
                Text("Mac", fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.guide_mac))
                Text("iPad / iPhone / Android", fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.guide_mobile))
                Text(stringResource(R.string.guide_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
