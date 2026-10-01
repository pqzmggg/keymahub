package app.keymahub.ui

import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
        // A captured mouse (another device has control) reports relative motion instead.
        mouse = devices.any { d ->
            d.supportsSource(InputDevice.SOURCE_MOUSE) || d.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        },
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
    val both = !input.keyboard && !input.mouse
    if (closed && !both) return
    val text = when {
        both -> R.string.input_missing_both
        !input.keyboard -> R.string.input_missing_keyboard
        !input.mouse -> R.string.input_missing_mouse
        else -> return
    }
    NoticeCard(
        stringResource(text), stringResource(R.string.action_bluetooth_settings), onBluetoothSettings,
        onClose = { closed = true }.takeIf { !both },
    )
}
