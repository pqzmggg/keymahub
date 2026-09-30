package app.keymahub.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    /** False in the right pane of two, which has no back arrow. */
    showBack: Boolean = true,
    onEdit: ((Settings) -> Settings) -> Unit,
    /** Moves control to a slot of the active profile (0 = this phone). */
    onSelect: (slot: Int) -> Unit,
) {
    val settings = status.settings
    val profile = settings.profile(profileId)
    if (profile == null) { // deleted
        LaunchedEffect(profileId) { onBack() }
        return
    }
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val active = settings.activeId == profile.id
    // Hotkeys follow the active profile, so only its devices can take control.
    val canSelect = status.running && active

    /** Changes which device is on which number; the device in control keeps it on its new number. */
    fun rearrange(change: (Settings) -> Settings) {
        val controlled = if (canSelect && status.slot != 0) profile.addressOf(status.slot) else null
        onEdit(change)
        if (controlled == null) return
        val now = change(settings).profile(profileId)?.slotOf(controlled)
        // Excluded: control goes back to this phone rather than to whoever moved up to its number.
        if (now == null) onSelect(0) else if (now != status.slot) onSelect(now)
    }

    Page {
        TopBar(
            profileName(profile),
            onBack.takeIf { showBack },
            titleAction = {
                IconButton(onClick = { renaming = true }) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profile_rename))
                }
            },
        )
        Text(
            stringResource(
                when {
                    active -> R.string.profile_in_use
                    settings.mode != ActivationMode.MANUAL && settings.profiles.first().id == profile.id -> fallbackLabel(settings.mode)
                    else -> R.string.profile_not_in_use
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle(stringResource(R.string.conditions_title), stringResource(R.string.conditions_hint))
        ConnectedCard(
            settings = settings,
            chosen = profile.whenConnected,
            onChange = { set -> onEdit { it.setWhenConnected(profileId, set) } },
        )

        SectionTitle(stringResource(R.string.hotkeys_title), stringResource(R.string.hotkeys_hint))
        // Nine numbers at most: the button goes quiet once they are all taken.
        FilledTonalButton(
            onClick = { adding = true },
            enabled = profile.receivers.size < Profile.SLOTS - 1,
            modifier = Modifier.align(Alignment.End),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.profile_add_device))
        }
        Card(Modifier.fillMaxWidth()) {
            SlotRow(0, profile, status, onSelect = if (canSelect) ({ onSelect(0) }) else null)
            ReceiverList(
                profile = profile,
                status = status,
                canSelect = canSelect,
                onSelect = onSelect,
                onRemove = { slot -> rearrange { it.excludeReceiver(profileId, slot) } },
                onMove = { from, to -> rearrange { it.moveReceiver(profileId, from, to) } },
            )
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
            message = stringResource(R.string.profile_delete_confirm, profileName(profile)),
            confirm = stringResource(R.string.action_delete),
            onDismiss = { deleting = false },
            onConfirm = { deleting = false; onEdit { it.deleteProfile(profileId) }; onBack() },
        )
    }
    if (adding) {
        AddDeviceDialog(
            profile = profile,
            status = status,
            onDismiss = { adding = false },
            onAdd = { addresses ->
                adding = false
                onEdit { s -> addresses.fold(s) { acc, address -> acc.addReceiver(profileId, address) } }
            },
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

/**
 * The profile's devices (their receiver slots, in order) under this phone's row; empty numbers are
 * not shown. Dragging a row by its handle moves its device to another place in the list: the rows
 * in between make room as it passes, and the new order is saved on release. The numbers and
 * hotkeys stay where they are.
 */
@Composable
private fun ReceiverList(
    profile: Profile,
    status: HubStatus,
    canSelect: Boolean,
    onSelect: (slot: Int) -> Unit,
    onRemove: (slot: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val slots = profile.receivers.keys.sorted()
    val reorder = rememberReorder<Int>()
    val from = reorder.from(slots)
    val target = reorder.target(slots)
    slots.forEachIndexed { index, slot -> key(slot) {
        val dragging = index == from
        val shift by animateFloatAsState(reorder.shift(slots, index), label = "shift")
        // The number and hotkey belong to the place in the list, the rest to the dragged device.
        val shown = if (from >= 0) slots[slotShownAt(index, from, target)] else slot
        Column(
            reorder.measure(slot)
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) reorder.offset else shift }
                .then(if (dragging) Modifier.shadow(8.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
        ) {
            HorizontalDivider()
            SlotRow(
                slot, profile, status,
                onSelect = if (canSelect) ({ onSelect(slot) }) else null,
                onRemove = { onRemove(slot) },
                numberSlot = if (dragging) slots[target] else shown,
                handle = reorder.handle(slot, slots) { a, b -> onMove(slots[a], slots[b]) },
            )
        }
    } }
}

/**
 * While a row moves from [from] to [to], which row's number the row at [index] shows: the others
 * shift one place, so each takes its neighbour's number (and hotkey) as it makes room.
 */
private fun slotShownAt(index: Int, from: Int, to: Int): Int = when {
    index in (from + 1)..to -> index - 1
    index in to until from -> index + 1
    else -> index
}

/**
 * One hotkey of [profile]: slot 0 is this phone, 1..9 a receiver. Tapping it moves control there
 * ([onSelect], null while that is not possible); the − button takes the device out of the profile.
 */
@Composable
private fun SlotRow(
    slot: Int,
    profile: Profile,
    status: HubStatus,
    onSelect: (() -> Unit)?,
    /** Not for this phone (slot 0), which has no − button. */
    onRemove: () -> Unit = {},
    /** The number (and hotkey) to show; differs from [slot] while rows are being dragged. */
    numberSlot: Int = slot,
    /** The drag handle's gestures; null for this phone, which stays first. */
    handle: Modifier? = null,
) {
    val settings = status.settings
    val hotkey = settings.hotkey(numberSlot)
    val address = profile.addressOf(slot)
    val active = status.running && settings.activeId == profile.id && status.slot == slot
    val connected = address != null && address in status.ready

    val title = if (slot == 0) stringResource(R.string.this_phone) else settings.device(address.orEmpty())?.name.orEmpty()
    val state = when {
        active -> stringResource(R.string.state_controlling)
        slot == 0 || address == null -> null
        connected -> stringResource(R.string.state_connected)
        else -> stringResource(R.string.state_not_connected)
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = onSelect != null) { onSelect?.invoke() }
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (handle != null) {
            Box(handle.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.drag_to_renumber),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(40.dp)) // lines up with the handles below
        }
        Badge(KeyLabels.key(hotkey.code), highlighted = active)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(KeyLabels.hotkey(hotkey), state).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (active || connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (slot != 0) {
            IconButton(onClick = onRemove) {
                Icon(Minus, contentDescription = stringResource(R.string.unassign), tint = MaterialTheme.colorScheme.error)
            }
        } else {
            // Keeps this phone's row as tall as the others.
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * The devices not in [profile] yet, to check one or more: [onAdd] puts them at the end of its list
 * in the order they were checked. No more can be checked than there are free numbers.
 */
@Composable
private fun AddDeviceDialog(profile: Profile, status: HubStatus, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val settings = status.settings
    val devices = settings.devices.filter { profile.slotOf(it.address) == null }
    val room = Profile.SLOTS - 1 - profile.receivers.size
    var picked by remember { mutableStateOf(listOf<String>()) }
    // From a keyboard: Enter does what the confirm button does (Add, or Close with nothing to add), Esc cancels.
    val confirm = { if (devices.isEmpty()) onDismiss() else if (picked.isNotEmpty()) onAdd(picked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_add_device)) },
        text = {
            Column(
                Modifier.dialogKeys(onEnter = confirm, onEscape = onDismiss).verticalScroll(rememberScrollState()),
            ) {
                when {
                    settings.devices.isEmpty() -> Text(stringResource(R.string.assign_no_devices))
                    devices.isEmpty() -> Text(stringResource(R.string.add_device_all_in))
                    devices.size > room -> Text(
                        stringResource(R.string.add_device_room, room),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (d in devices) {
                    val on = d.address in picked
                    val connected = d.address in status.ready
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(on, enabled = on || picked.size < room, role = Role.Checkbox) {
                                picked = if (it) picked + d.address else picked - d.address
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, enabled = on || picked.size < room)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(if (connected) R.string.state_connected else R.string.state_not_connected),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (devices.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            } else {
                TextButton(onClick = { onAdd(picked) }, enabled = picked.isNotEmpty()) {
                    Text(stringResource(R.string.add_device_confirm))
                }
            }
        },
        dismissButton = {
            if (devices.isNotEmpty()) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A minus sign (Material's "remove" icon), not in the core icon set. */
private val Minus: ImageVector = materialIcon(name = "Filled.Remove") {
    materialPath {
        moveTo(19f, 13f)
        horizontalLineTo(5f)
        verticalLineToRelative(-2f)
        horizontalLineToRelative(14f)
        verticalLineToRelative(2f)
        close()
    }
}

@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
