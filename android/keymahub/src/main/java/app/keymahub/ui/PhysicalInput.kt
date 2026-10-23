package app.keymahub.ui

import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
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
import app.keymahub.core.InputKey
import app.keymahub.core.isMouse
import app.keymahub.core.isTypingKeyboard
import app.keymahub.core.keyboardsAndMice
import app.keymahub.core.physicalDevices

/** Whether a physical keyboard and a mouse are connected to this device. */
data class PhysicalInput(val keyboard: Boolean, val mouse: Boolean)

/**
 * The physical keyboard and mouse connected right now (Bluetooth or USB), updated as they come
 * and go. The same keyboards the capture takes (alphabetic, not virtual); built-in devices
 * (Android 10+ tells them apart) do not count.
 */
@Composable
fun rememberPhysicalInput(): PhysicalInput = rememberInputDevices(::physicalInput)

/** The physical keyboards and mice connected right now, updated as they come and go. */
@Composable
fun rememberKeyboardsAndMice(): List<InputKey> = rememberInputDevices { it?.keyboardsAndMice().orEmpty() }

/** [read] from the input devices now, and again whenever one comes, goes or changes. */
@Composable
private fun <T> rememberInputDevices(read: (InputManager?) -> T): T {
    val context = LocalContext.current
    val im = remember { context.getSystemService(InputManager::class.java) }
    var state by remember { mutableStateOf(read(im)) }
    DisposableEffect(im) {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { state = read(im) }
            override fun onInputDeviceRemoved(deviceId: Int) { state = read(im) }
            override fun onInputDeviceChanged(deviceId: Int) { state = read(im) }
        }
        im?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { im?.unregisterInputDeviceListener(listener) }
    }
    return state
}

private fun physicalInput(im: InputManager?): PhysicalInput {
    if (im == null) return PhysicalInput(keyboard = false, mouse = false)
    val devices = im.physicalDevices()
    return PhysicalInput(keyboard = devices.any { it.isTypingKeyboard }, mouse = devices.any { it.isMouse })
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
