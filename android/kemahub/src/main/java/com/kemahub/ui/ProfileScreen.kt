package com.kemahub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kemahub.KeyLabels
import com.kemahub.Locations
import com.kemahub.R
import com.kemahub.core.HubStatus
import com.kemahub.core.PlaceRule
import com.kemahub.core.Profile
import com.kemahub.core.Settings
import com.kemahub.core.TimeRule

/** One profile: when it applies (time, place) and which device is on which hotkey. */
@Composable
fun ProfileScreen(
    profileId: String,
    status: HubStatus,
    locationGranted: Boolean,
    onBack: () -> Unit,
    onEdit: ((Settings) -> Settings) -> Unit,
    /** Runs the block once location may be used (asks first). */
    onLocation: (() -> Unit) -> Unit,
) {
    val settings = status.settings
    val profile = settings.profile(profileId)
    if (profile == null) { // deleted
        LaunchedEffect(profileId) { onBack() }
        return
    }
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var assignFor by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locationFailed by remember { mutableStateOf(false) }

    fun useCurrentLocation() = onLocation {
        locating = true
        locationFailed = false
        Locations.current(context) { p ->
            locating = false
            if (p == null) {
                locationFailed = true
            } else {
                val old = settings.profile(profileId)?.place
                onEdit { it.setPlace(profileId, PlaceRule(p.lat, p.lon, old?.radius ?: 200, old?.label.orEmpty())) }
            }
        }
    }

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
        TimeCard(profile.time) { rule -> onEdit { it.setTime(profileId, rule) } }
        PlaceCard(
            place = profile.place,
            granted = locationGranted,
            locating = locating,
            failed = locationFailed,
            onEnable = ::useCurrentLocation,
            onUpdate = ::useCurrentLocation,
            onDisable = { onEdit { it.setPlace(profileId, null) } },
            onChange = { rule -> onEdit { it.setPlace(profileId, rule) } },
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

@Composable
private fun TimeCard(time: TimeRule?, onChange: (TimeRule?) -> Unit) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.condition_time), style = MaterialTheme.typography.titleMedium)
                    Text(
                        time?.let { conditionText(Profile("", "", time = it)) } ?: stringResource(R.string.condition_time_off),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = time != null, onCheckedChange = { onChange(if (it) TimeRule() else null) })
            }
            if (time != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (day in 1..7) {
                        DayToggle(dayName(day), time.on(day), Modifier.weight(1f)) {
                            val next = time.toggle(day)
                            if (next.days != 0) onChange(next)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text(timeText(time.start)) }
                    Text("–")
                    OutlinedButton(onClick = { picking = false }, modifier = Modifier.weight(1f)) { Text(timeText(time.end)) }
                }
                Text(
                    stringResource(if (time.start == time.end) R.string.time_all_day_hint else R.string.time_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val which = picking
    if (which != null && time != null) {
        TimeDialog(
            title = stringResource(if (which) R.string.time_start else R.string.time_end),
            minute = if (which) time.start else time.end,
            onDismiss = { picking = null },
            onPick = { m ->
                picking = null
                onChange(if (which) time.copy(start = m) else time.copy(end = m))
            },
        )
    }
}

@Composable
private fun DayToggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.height(40.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(minute / 60, minute % 60, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PlaceCard(
    place: PlaceRule?,
    granted: Boolean,
    locating: Boolean,
    failed: Boolean,
    onEnable: () -> Unit,
    onUpdate: () -> Unit,
    onDisable: () -> Unit,
    onChange: (PlaceRule) -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.condition_place), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            locating -> stringResource(R.string.place_locating)
                            place != null -> place.label.ifEmpty { stringResource(R.string.place_unnamed) }
                            else -> stringResource(R.string.condition_place_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = place != null, enabled = !locating, onCheckedChange = { if (it) onEnable() else onDisable() })
            }
            if (failed) {
                Text(stringResource(R.string.place_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (place == null && !granted) {
                Text(
                    stringResource(R.string.place_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (place != null) {
                Text(
                    "%.5f, %.5f".format(place.lat, place.lon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.place_radius), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in PlaceRule.RADII) {
                        FilterChip(
                            selected = place.radius == r,
                            onClick = { onChange(place.copy(radius = r)) },
                            label = { Text(distanceText(r)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUpdate, enabled = !locating) { Text(stringResource(R.string.place_update)) }
                    OutlinedButton(onClick = { naming = true }) { Text(stringResource(R.string.place_name)) }
                }
                if (!granted) {
                    Text(stringResource(R.string.place_no_permission), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (naming && place != null) {
        NameDialog(
            title = stringResource(R.string.place_name),
            initial = place.label,
            onDismiss = { naming = false },
            onConfirm = { name -> naming = false; onChange(place.copy(label = name)) },
        )
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
