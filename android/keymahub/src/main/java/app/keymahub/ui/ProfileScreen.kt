package app.keymahub.ui

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keymahub.KeyLabels
import app.keymahub.R
import app.keymahub.core.ActivationMode
import app.keymahub.core.HubStatus
import app.keymahub.core.Profile
import app.keymahub.core.Settings

/** One profile: when it applies (connected devices) and which device is on which hotkey. */
@Composable
fun ProfileScreen(
    profileId: String,
    status: HubStatus,
    onBack: () -> Unit,
    onEdit: ((Settings) -> Settings) -> Unit,
) {
    val settings = status.settings
    val profile = settings.profile(profileId)
    if (profile == null) { // deleted
        LaunchedEffect(profileId) { onBack() }
        return
    }
    var renaming by remember { mutableStateOf(false) }
    var assignFor by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf(false) }

    Page {
        TopBar(profileName(profile), onBack) {
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profile_rename))
            }
        }
        val active = settings.activeId == profile.id
        Text(
            stringResource(
                when {
                    active -> R.string.profile_in_use
                    settings.chosenId == profile.id -> R.string.profile_fallback
                    else -> R.string.profile_not_in_use
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle(stringResource(R.string.conditions_title), stringResource(R.string.conditions_hint))
        if (settings.mode != ActivationMode.RULES) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text(
                    stringResource(R.string.conditions_rules_only),
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        ConnectedCard(
            settings = settings,
            chosen = profile.whenConnected,
            onChange = { set -> onEdit { it.setWhenConnected(profileId, set) } },
        )

        SectionTitle(stringResource(R.string.hotkeys_title), stringResource(R.string.hotkeys_hint))
        Card(Modifier.fillMaxWidth()) {
            for (slot in 0 until Profile.SLOTS) {
                if (slot > 0) HorizontalDivider()
                SlotRow(
                    slot, profile, status,
                    onAssign = { assignFor = slot },
                    onClear = { onEdit { it.assign(profileId, slot, null) } },
                )
            }
        }
        if (settings.profiles.size > 1) {
            TextButton(onClick = { deleting = true }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = stringResource(R.string.profile_rename),
            initial = profileName(profile),
            onDismiss = { renaming = false },
            onConfirm = { name -> renaming = false; onEdit { it.renameProfile(profileId, name) } },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.profile_delete_confirm, profileName(profile)),
            confirm = stringResource(R.string.action_delete),
            onDismiss = { deleting = false },
            onConfirm = { deleting = false; onEdit { it.deleteProfile(profileId) }; onBack() },
        )
    }
    assignFor?.let { slot ->
        AssignDialog(
            slot = slot,
            profile = profile,
            settings = settings,
            onDismiss = { assignFor = null },
            onPick = { address -> assignFor = null; onEdit { it.assign(profileId, slot, address) } },
        )
    }
}

// ---------------------------------------------------------------- conditions

/** "While one of these devices is connected": a checklist of the paired devices. */
@Composable
private fun ConnectedCard(settings: Settings, chosen: Set<String>, onChange: (Set<String>) -> Unit) {
    var open by remember { mutableStateOf(chosen.isNotEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.condition_devices), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (chosen.isEmpty()) stringResource(R.string.condition_devices_off)
                        else connectedText(settings, chosen),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = open || chosen.isNotEmpty(),
                    onCheckedChange = { on ->
                        open = on
                        if (!on) onChange(emptySet())
                    },
                )
            }
            if (open || chosen.isNotEmpty()) {
                Text(
                    stringResource(R.string.condition_devices_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.devices.isEmpty()) {
                    Text(stringResource(R.string.assign_no_devices), style = MaterialTheme.typography.bodyMedium)
                }
                for (d in settings.devices) {
                    val on = d.address in chosen
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(on, role = Role.Checkbox) { onChange(if (it) chosen + d.address else chosen - d.address) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(d.name)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- hotkeys and receivers

/** One hotkey of [profile]: slot 0 is this phone, 1..9 a receiver. */
@Composable
private fun SlotRow(slot: Int, profile: Profile, status: HubStatus, onAssign: () -> Unit, onClear: () -> Unit) {
    val settings = status.settings
    val hotkey = settings.hotkey(slot)
    val address = profile.addressOf(slot)
    val active = status.running && settings.activeId == profile.id && status.slot == slot
    val connected = address != null && address in status.ready
    var menu by remember { mutableStateOf(false) }

    val title = when {
        slot == 0 -> stringResource(R.string.this_phone)
        address == null -> stringResource(R.string.slot_empty)
        else -> settings.device(address)?.name.orEmpty()
    }
    val state = when {
        active -> stringResource(R.string.state_controlling)
        slot == 0 || address == null -> null
        connected -> stringResource(R.string.state_connected)
        else -> stringResource(R.string.state_not_connected)
    }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = slot != 0) { menu = true }
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
                    color = if (active || connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.assign_device)) }, onClick = { menu = false; onAssign() })
            if (address != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.unassign)) }, onClick = { menu = false; onClear() })
            }
        }
    }
}

@Composable
private fun AssignDialog(slot: Int, profile: Profile, settings: Settings, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val current = profile.addressOf(slot)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assign_title, KeyLabels.hotkey(settings.hotkey(slot)))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (settings.devices.isEmpty()) Text(stringResource(R.string.assign_no_devices))
                for (d in settings.devices) {
                    val other = profile.slotOf(d.address)?.takeIf { it != slot }
                    Choice(
                        label = d.name,
                        detail = other?.let { stringResource(R.string.assign_now_on, KeyLabels.hotkey(settings.hotkey(it))) },
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
fun Choice(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
