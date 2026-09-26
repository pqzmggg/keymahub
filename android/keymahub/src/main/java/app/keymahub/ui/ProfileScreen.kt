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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.keymahub.KeyLabels
import app.keymahub.R
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
    var assignFor by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val active = settings.activeId == profile.id
    // Hotkeys follow the active profile, so only its devices can take control.
    val canSelect = status.running && active

    /** Changes which device is on which number; the device in control keeps it on its new number. */
    fun rearrange(change: (Settings) -> Settings) {
        val controlled = if (canSelect && status.slot != 0) profile.addressOf(status.slot) else null
        onEdit(change)
        val now = controlled?.let { change(settings).profile(profileId)?.slotOf(it) }
        if (now != null && now != status.slot) onSelect(now)
    }

    Page {
        TopBar(profileName(profile), onBack.takeIf { showBack }) {
            IconButton(onClick = { renaming = true }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profile_rename))
            }
        }
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
        ConnectedCard(
            settings = settings,
            chosen = profile.whenConnected,
            onChange = { set -> onEdit { it.setWhenConnected(profileId, set) } },
        )

        SectionTitle(stringResource(R.string.hotkeys_title), stringResource(R.string.hotkeys_hint))
        Card(Modifier.fillMaxWidth()) {
            SlotRow(0, profile, status, onSelect = if (canSelect) ({ onSelect(0) }) else null, onAssign = {}, onClear = {})
            ReceiverList(
                profile = profile,
                status = status,
                canSelect = canSelect,
                onSelect = onSelect,
                onAssign = { slot -> assignFor = slot },
                onClear = { slot -> onEdit { it.assign(profileId, slot, null) } },
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
    assignFor?.let { slot ->
        AssignDialog(
            slot = slot,
            profile = profile,
            settings = settings,
            onDismiss = { assignFor = null },
            onPick = { address ->
                assignFor = null
                rearrange { it.assign(profileId, slot, address) }
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
 * The receivers (slots 1..9) under this phone's row. Dragging a row by its handle moves its device
 * (or its emptiness) to another number: the rows in between make room as it passes, and the new
 * order is saved on release. The numbers and hotkeys stay where they are.
 */
@Composable
private fun ReceiverList(
    profile: Profile,
    status: HubStatus,
    canSelect: Boolean,
    onSelect: (slot: Int) -> Unit,
    onAssign: (slot: Int) -> Unit,
    onClear: (slot: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val slots = (1 until Profile.SLOTS).toList()
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
                onAssign = { onAssign(slot) },
                onClear = { onClear(slot) },
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
 * ([onSelect], null while that is not possible); tapping an empty slot picks its device. The ⋯
 * button holds the device choices.
 */
@Composable
private fun SlotRow(
    slot: Int,
    profile: Profile,
    status: HubStatus,
    onSelect: (() -> Unit)?,
    onAssign: () -> Unit,
    onClear: () -> Unit,
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
    val tap: (() -> Unit)? = when {
        slot != 0 && address == null -> onAssign
        else -> onSelect
    }
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = tap != null) { tap?.invoke() }
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
        if (slot != 0) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.assign_device)) }, onClick = { menu = false; onAssign() })
                    if (address != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.unassign)) }, onClick = { menu = false; onClear() })
                    }
                }
            }
        } else {
            // Keeps this phone's row as tall as the others.
            Spacer(Modifier.width(48.dp))
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
