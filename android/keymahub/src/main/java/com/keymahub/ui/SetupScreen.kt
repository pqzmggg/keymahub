package com.keymahub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keymahub.R

/** Host mode setup: runtime permissions, then the accessibility service behind its disclosure. */
@Composable
fun SetupScreen(
    setup: SetupState,
    showDisclosure: Boolean,
    onDisclosure: (Boolean) -> Unit,
    onNotifications: () -> Unit,
    onBluetooth: () -> Unit,
    onAccessibility: () -> Unit,
    onAppInfo: () -> Unit,
) {
    Page {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.setup_steps), style = MaterialTheme.typography.titleMedium)

        Step(
            "1", stringResource(R.string.step_bluetooth_title), stringResource(R.string.step_bluetooth_body),
            done = setup.bluetooth, action = stringResource(R.string.action_allow), onClick = onBluetooth,
        )
        Step(
            "2", stringResource(R.string.step_a11y_title), stringResource(R.string.step_a11y_body),
            done = setup.accessibility, action = stringResource(R.string.action_open_settings),
            enabled = setup.bluetooth, onClick = { onDisclosure(true) },
        )
        Step(
            "3", stringResource(R.string.step_notifications_title), stringResource(R.string.step_notifications_body),
            done = setup.notifications, action = stringResource(R.string.action_allow), onClick = onNotifications,
        )

        if (setup.bluetooth && !setup.accessibility) {
            TextButton(onClick = onAppInfo) {
                Text(stringResource(R.string.restricted_hint), fontSize = 12.sp)
            }
        }
    }

    if (showDisclosure) {
        // Prominent disclosure (Google Play): shown before sending the user to the accessibility settings.
        AlertDialog(
            onDismissRequest = { onDisclosure(false) },
            title = { Text(stringResource(R.string.disclosure_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.disclosure_intro))
                    Text(stringResource(R.string.disclosure_1))
                    Text(stringResource(R.string.disclosure_2))
                    Text(stringResource(R.string.disclosure_3))
                    Text(stringResource(R.string.disclosure_4))
                    Text(stringResource(R.string.disclosure_next), fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(onClick = { onDisclosure(false); onAccessibility() }) { Text(stringResource(R.string.disclosure_agree)) }
            },
            dismissButton = { TextButton(onClick = { onDisclosure(false) }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun Step(
    n: String,
    title: String,
    body: String,
    done: Boolean,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Badge(if (done) "✓" else n, highlighted = done)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (!done) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onClick, enabled = enabled) { Text(action) }
            }
        }
    }
}
