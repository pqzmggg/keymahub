package app.keymahub.core

import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice

/**
 * The physical input devices connected to this phone right now (Bluetooth or USB): not virtual,
 * and not built in (Android 10+ tells them apart).
 */
fun InputManager.physicalDevices(): List<InputDevice> =
    inputDeviceIds.toList()
        .mapNotNull { id -> getInputDevice(id) }
        .filter { d -> !d.isVirtual && (Build.VERSION.SDK_INT < 29 || d.isExternal) }

/** A keyboard to type on (the kind the capture takes): not the media keys of earphones or remotes. */
val InputDevice.isTypingKeyboard get() = keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

/** A mouse; a captured one (another device has control) reports relative motion instead. */
val InputDevice.isMouse get() = supportsSource(InputDevice.SOURCE_MOUSE) || supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)

/** The physical keyboards and mice connected now, one each (a keyboard with a touchpad is one device). */
fun InputManager.keyboardsAndMice(): List<InputKey> =
    physicalDevices()
        .filter { it.isTypingKeyboard || it.isMouse }
        .map { InputKey(it.descriptor, it.name) }
        .distinctBy { it.id }
