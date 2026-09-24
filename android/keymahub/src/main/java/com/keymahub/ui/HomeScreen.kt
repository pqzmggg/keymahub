package com.keymahub.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.keymahub.R
import com.keymahub.core.ActivationMode
import com.keymahub.core.HubStatus
import com.keymahub.core.Profile
import com.keymahub.core.Settings

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
        ModeRow(settings.mode) { m -> onEdit { it.setMode(m) } }
        Text(
            stringResource(
                when (settings.mode) {
                    ActivationMode.AUTO -> R.string.profiles_hint_auto
                    ActivationMode.RULES -> R.string.profiles_hint
                    ActivationMode.MANUAL -> R.string.profiles_hint_manual
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        settings.overriddenId?.let { id ->
            settings.profile(id)?.let { matched ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.manual_banner, profileName(settings.active), profileName(matched)),
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { onEdit { it.automatic() } }) { Text(stringResource(R.string.manual_off)) }
                    }
                }
            }
        }

        ProfileList(settings, status.running, status.ready, onEdit, onOpenProfile)
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

/**
 * The profiles in priority order. Dragging a row by its handle moves it; the other rows make
 * room as it passes them, and the new order is saved on release.
 */
@Composable
private fun ProfileList(settings: Settings, running: Boolean, ready: Set<String>, onEdit: ((Settings) -> Settings) -> Unit, onOpen: (String) -> Unit) {
    val profiles = settings.profiles
    val heights = remember { mutableStateMapOf<String, Int>() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val gap = with(LocalDensity.current) { 12.dp.toPx() } // Page's spacing between rows

    fun height(i: Int) = (heights[profiles[i].id] ?: 0).toFloat()

    /** Where the dragged row would land now (-1: nothing dragged). Reads live state only. */
    fun target(): Int {
        val from = profiles.indexOfFirst { it.id == dragId }
        if (from < 0) return -1
        val tops = FloatArray(profiles.size)
        var y = 0f
        for (i in profiles.indices) {
            tops[i] = y
            y += height(i) + gap
        }
        val center = tops[from] + dragOffset + height(from) / 2
        return profiles.indices.count { it != from && tops[it] + height(it) / 2 < center }
    }

    val from = profiles.indexOfFirst { it.id == dragId }
    val target = target()

    profiles.forEachIndexed { index, p -> key(p.id) {
        val dragging = index == from
        val shift = when {
            from < 0 || dragging -> 0f
            index in (from + 1)..target -> -(height(from) + gap)
            index in target until from -> height(from) + gap
            else -> 0f
        }
        val animatedShift by animateFloatAsState(shift, label = "shift")
        ProfileRow(
            p = p,
            settings = settings,
            running = running,
            ready = ready,
            dragging = dragging,
            modifier = Modifier
                .onSizeChanged { heights[p.id] = it.height }
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) dragOffset else animatedShift },
            handle = Modifier.pointerInput(p.id, profiles) {
                detectDragGestures(
                    onDragStart = {
                        dragId = p.id
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        val to = target()
                        val at = profiles.indexOfFirst { it.id == p.id }
                        if (to >= 0 && to != at) onEdit { it.moveProfile(p.id, to - at) }
                        dragId = null
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        dragId = null
                        dragOffset = 0f
                    },
                ) { change, amount ->
                    change.consume()
                    dragOffset += amount.y
                }
            },
            onActivate = { onEdit { it.activate(p.id) } },
            onOpen = { onOpen(p.id) },
        )
    } }
}

@Composable
private fun ProfileRow(
    p: Profile,
    settings: Settings,
    /** While hosting is off, nothing is really in use: the mark is dimmed and Activate is off. */
    running: Boolean,
    ready: Set<String>,
    dragging: Boolean,
    modifier: Modifier,
    handle: Modifier,
    onActivate: () -> Unit,
    onOpen: () -> Unit,
) {
    val active = p.id == settings.activeId
    val lit = active && running
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Card(
        modifier.fillMaxWidth(),
        colors = if (lit) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 1.dp),
    ) {
        Row(Modifier.padding(end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(handle.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(profileName(p), style = MaterialTheme.typography.titleMedium)
                Text(
                    when (settings.mode) {
                        ActivationMode.AUTO -> stringResource(
                            R.string.profile_devices_connected,
                            p.receivers.values.count { it in ready },
                            p.receivers.size,
                        )
                        ActivationMode.RULES -> conditionText(p, settings)
                        ActivationMode.MANUAL -> pluralStringResource(R.plurals.receiver_count, p.receivers.size, p.receivers.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                val state = when {
                    active -> stringResource(R.string.profile_in_use)
                    // The fallback only means something while profiles switch by themselves.
                    p.id == settings.chosenId && settings.mode != ActivationMode.MANUAL -> stringResource(R.string.profile_fallback)
                    else -> null
                }
                if (state != null) {
                    Text(
                        state,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            lit -> MaterialTheme.colorScheme.primary
                            active -> dim
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (active) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.profile_in_use),
                    tint = if (running) MaterialTheme.colorScheme.primary else dim,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                FilledTonalButton(onClick = onActivate, enabled = running, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(stringResource(R.string.profile_activate), maxLines = 1)
                }
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profile_edit))
            }
        }
    }
}

/** Label and explanation of each activation mode. */
private val MODES = listOf(
    Triple(ActivationMode.AUTO, R.string.mode_auto, R.string.mode_auto_hint),
    Triple(ActivationMode.RULES, R.string.mode_rules, R.string.mode_rules_hint),
    Triple(ActivationMode.MANUAL, R.string.mode_manual, R.string.mode_manual_hint),
)

/** "Profile activation: [Fully automatic ▾] (?)" */
@Composable
private fun ModeRow(mode: ActivationMode, onMode: (ActivationMode) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_activation), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Box {
            OutlinedButton(onClick = { menu = true }, contentPadding = PaddingValues(start = 12.dp, end = 4.dp)) {
                Text(stringResource(MODES.first { it.first == mode }.second))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for ((m, label, _) in MODES) {
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { if (m == mode) Icon(Icons.Default.Check, contentDescription = null) },
                        onClick = { menu = false; onMode(m) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { help = true }) {
            Surface(
                shape = CircleShape,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                color = Color.Transparent,
                modifier = Modifier.size(24.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text(stringResource(R.string.settings_activation)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((m, label, hint) in MODES) {
                        Column {
                            Text(
                                stringResource(label),
                                fontWeight = FontWeight.SemiBold,
                                color = if (m == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(stringResource(R.string.mode_fallback_note), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
