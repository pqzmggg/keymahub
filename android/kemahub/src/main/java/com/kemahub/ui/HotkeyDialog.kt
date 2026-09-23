package com.kemahub.ui

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kemahub.KeyLabels
import com.kemahub.R
import com.kemahub.core.Hotkey
import com.kemahub.core.Hub
import com.kemahub.core.HubStatus
import com.kemahub.core.Mods
import com.kemahub.core.Profile

/** Records a new chord for [slot] from the connected keyboard. */
@Composable
fun HotkeyDialog(slot: Int, profile: Profile, status: HubStatus, onDismiss: () -> Unit, onSave: (Hotkey) -> Unit) {
    var captured by remember { mutableStateOf<Hotkey?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // Let chords reach this dialog instead of switching devices while recording.
    DisposableEffect(Unit) {
        Hub.recordingHotkey = true
        onDispose { Hub.recordingHotkey = false }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val slotName = if (slot == 0) {
        stringResource(R.string.this_phone)
    } else {
        profile.addressOf(slot)?.let { status.settings.device(it)?.name } ?: stringResource(R.string.slot_empty)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_title, slotName)) },
        text = {
            Column(
                Modifier.onPreviewKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    // Leave on-screen keys and navigation buttons alone (e.g. Back closes the dialog).
                    if (n.device?.isVirtual != false || n.scanCode == 0) return@onPreviewKeyEvent false
                    if (n.action != KeyEvent.ACTION_DOWN || Mods.of(n.scanCode) != 0) return@onPreviewKeyEvent true
                    var mods = 0
                    if (n.isCtrlPressed) mods = mods or Mods.CTRL
                    if (n.isAltPressed) mods = mods or Mods.ALT
                    if (n.isShiftPressed) mods = mods or Mods.SHIFT
                    if (n.isMetaPressed) mods = mods or Mods.META
                    val h = Hotkey(mods, n.scanCode)
                    when {
                        mods == 0 && n.keyCode == KeyEvent.KEYCODE_ESCAPE -> onDismiss()
                        h.isValid -> { captured = h; invalid = false }
                        else -> invalid = true
                    }
                    true
                }.focusRequester(focus).focusable(), // the key handler must sit outside the focus target
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.record_prompt))
                if (status.running && status.slot != 0) {
                    Text(
                        stringResource(R.string.record_remote, KeyLabels.hotkey(status.settings.active.hotkeys[0])),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            KeyLabels.hotkey(captured ?: profile.hotkeys[slot]),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = if (captured != null) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                Text(
                    stringResource(if (invalid) R.string.record_invalid else R.string.record_rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val other = captured?.let { profile.slotFor(it) }?.takeIf { it != slot }
                if (other != null) {
                    val otherName = if (other == 0) {
                        stringResource(R.string.this_phone)
                    } else {
                        profile.addressOf(other)?.let { status.settings.device(it)?.name } ?: stringResource(R.string.slot_empty)
                    }
                    Text(stringResource(R.string.record_swap, otherName), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { captured?.let(onSave) }, enabled = captured != null) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
