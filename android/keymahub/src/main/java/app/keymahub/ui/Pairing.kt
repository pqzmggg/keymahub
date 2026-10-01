package app.keymahub.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keymahub.R
import app.keymahub.core.HubStatus
import kotlinx.coroutines.delay

/** Pairing mode on/off, its time left, and how to add this device on each kind of host (devices screen). */
@Composable
fun PairingCard(status: HubStatus, onPairing: (Boolean) -> Unit) {
    val on = status.pairingUntil != 0L
    fun secondsLeft() = ((status.pairingUntil - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
    var left by remember(status.pairingUntil) { mutableLongStateOf(secondsLeft()) }
    LaunchedEffect(status.pairingUntil) {
        while (status.pairingUntil != 0L) {
            left = secondsLeft()
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
                // Room between the text and the switch.
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
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
                WindowsAddDevice()
                GuideLine("Mac", stringResource(R.string.guide_mac))
                GuideLine("iPad / iPhone / Android", stringResource(R.string.guide_mobile))
                Text(stringResource(R.string.guide_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Where 'Show all devices' is: the top of Windows' "Add a device" window, drawn like it (its dark
 * background and white text, in Windows' own words) rather than a screenshot, so it follows the
 * app's language. A little smaller than the real one. Same in the tutorial and here.
 */
@Composable
fun WindowsAddDevice() {
    Column(
        Modifier.widthIn(max = 320.dp).fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2A2A2A))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.windows_add_device), color = Color.White, fontSize = 18.sp, lineHeight = 22.sp)
        Text(stringResource(R.string.windows_add_device_body), color = Color.White, fontSize = 10.sp, lineHeight = 14.sp)
        Text(
            stringResource(R.string.windows_show_all),
            Modifier.padding(top = 4.dp),
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun GuideLine(platform: String, steps: String) {
    Column {
        Text(platform, fontWeight = FontWeight.SemiBold)
        Text(steps, style = MaterialTheme.typography.bodyMedium)
    }
}
