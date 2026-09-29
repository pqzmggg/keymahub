package app.keymahub.ui

import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.keymahub.R

/** Whether a physical keyboard and a mouse are connected to this device. */
data class PhysicalInput(val keyboard: Boolean, val mouse: Boolean)

/**
 * The physical keyboard and mouse connected right now (Bluetooth or USB), updated as they come
 * and go. The same keyboards the capture takes (alphabetic, not virtual); built-in devices
 * (Android 10+ tells them apart) do not count.
 */
@Composable
fun rememberPhysicalInput(): PhysicalInput {
    val context = LocalContext.current
    val im = remember { context.getSystemService(InputManager::class.java) }
    var state by remember { mutableStateOf(physicalInput(im)) }
    DisposableEffect(im) {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { state = physicalInput(im) }
            override fun onInputDeviceRemoved(deviceId: Int) { state = physicalInput(im) }
            override fun onInputDeviceChanged(deviceId: Int) { state = physicalInput(im) }
        }
        im?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { im?.unregisterInputDeviceListener(listener) }
    }
    return state
}

private fun physicalInput(im: InputManager?): PhysicalInput {
    if (im == null) return PhysicalInput(keyboard = false, mouse = false)
    val ids: IntArray = im.inputDeviceIds
    val devices: List<InputDevice> = ids.toList()
        .mapNotNull { id -> im.getInputDevice(id) }
        .filter { d -> !d.isVirtual && (Build.VERSION.SDK_INT < 29 || d.isExternal) }
    return PhysicalInput(
        keyboard = devices.any { d -> d.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC },
        mouse = devices.any { d -> d.supportsSource(InputDevice.SOURCE_MOUSE) },
    )
}

/**
 * Top of the home screen: says so when this device has no physical keyboard or mouse, the input
 * everything else relies on. With neither it stays; with one missing (a keyboard alone is a
 * valid setup) it can be closed until the app is opened again.
 */
@Composable
fun InputBanner(onBluetoothSettings: () -> Unit) {
    val input = rememberPhysicalInput()
    var closed by rememberSaveable { mutableStateOf(false) }
    val text = when {
        !input.keyboard && !input.mouse -> R.string.input_missing_both
        closed -> return
        !input.keyboard -> R.string.input_missing_keyboard
        !input.mouse -> R.string.input_missing_mouse
        else -> return
    }
    val both = !input.keyboard && !input.mouse
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                FilledTonalButton(onClick = onBluetoothSettings) { Text(stringResource(R.string.action_bluetooth_settings)) }
            }
            if (!both) {
                IconButton(onClick = { closed = true }, modifier = Modifier.align(Alignment.Top)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
        }
    }
}
