package com.keymahub.ui

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.keymahub.R
import com.keymahub.core.Device
import com.keymahub.core.HubStatus
import com.keymahub.core.Settings
import kotlinx.coroutines.delay

/** Paired devices: pairing mode for new ones, alias, disconnect, remove, last used. */
@Composable
fun DevicesScreen(
    status: HubStatus,
    onBack: () -> Unit,
    onEdit: ((Settings) -> Settings) -> Unit,
    onPairing: (Boolean) -> Unit,
    onConnect: (Device) -> Unit,
    onDisconnect: (String) -> Unit,
    onAllow: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val settings = status.settings
    var renaming by remember { mutableStateOf<Device?>(null) }
    var removing by remember { mutableStateOf<Device?>(null) }

    Page {
        TopBar(stringResource(R.string.devices_title), onBack)
        PairingCard(status, onPairing)

        SectionTitle(stringResource(R.string.devices_paired))
        if (settings.devices.isEmpty()) {
            Text(stringResource(R.string.devices_empty), style = MaterialTheme.typography.bodyMedium)
        }
        for (d in settings.devices) {
            DeviceRow(
                d, connected = d.address in status.ready,
                onRename = { renaming = d },
                onConnect = { onConnect(d) },
                onDisconnect = { onDisconnect(d.address) },
                onAllow = { onAllow(d.address) },
                onRemove = { removing = d },
            )
        }
    }

    renaming?.let { d ->
        NameDialog(
            title = stringResource(R.string.device_rename),
            initial = d.name,
            message = stringResource(R.string.device_rename_hint),
            onDismiss = { renaming = null },
            onConfirm = { name -> renaming = null; onEdit { it.renameDevice(d.address, name) } },
        )
    }
    removing?.let { d ->
        ConfirmDialog(
            title = stringResource(R.string.device_remove_confirm, d.name),
            confirm = stringResource(R.string.device_remove),
            onDismiss = { removing = null },
            onConfirm = { removing = null; onRemove(d.address) },
        )
    }
}

@Composable
private fun PairingCard(status: HubStatus, onPairing: (Boolean) -> Unit) {
    val on = status.pairingUntil != 0L
    var left by remember { mutableLongStateOf(0L) }
    LaunchedEffect(status.pairingUntil) {
        while (status.pairingUntil != 0L) {
            left = ((status.pairingUntil - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (on) stringResource(R.string.pairing_on, "%d:%02d".format(left / 60, left % 60))
                        else stringResource(R.string.pairing_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = on, onCheckedChange = onPairing)
            }
            if (on) {
                HorizontalDivider()
                Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyMedium)
                GuideLine("Windows", stringResource(R.string.guide_windows))
                Text(stringResource(R.string.guide_windows_hint), style = MaterialTheme.typography.bodySmall)
                GuideLine("Mac", stringResource(R.string.guide_mac))
                GuideLine("iPad / iPhone / Android", stringResource(R.string.guide_mobile))
                Text(stringResource(R.string.guide_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GuideLine(platform: String, steps: String) {
    Column {
        Text(platform, fontWeight = FontWeight.SemiBold)
        Text(steps, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceRow(
    d: Device,
    connected: Boolean,
    onRename: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAllow: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val state = when {
        d.blocked -> stringResource(R.string.state_disconnected_by_user)
        connected -> stringResource(R.string.state_connected)
        else -> stringResource(R.string.state_not_connected)
    }
    val lastUsed = if (d.lastUsed == 0L) {
        stringResource(R.string.last_used_never)
    } else {
        stringResource(
            R.string.last_used,
            DateUtils.getRelativeTimeSpanString(d.lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    state,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        d.blocked -> MaterialTheme.colorScheme.error
                        connected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(lastUsed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!connected && !d.blocked) {
                TextButton(onClick = onConnect) { Text(stringResource(R.string.device_connect)) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_rename)) }, onClick = { menu = false; onRename() })
                    if (d.blocked) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.device_allow)) }, onClick = { menu = false; onAllow() })
                    } else if (connected) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.device_disconnect)) }, onClick = { menu = false; onDisconnect() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.device_remove)) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}
