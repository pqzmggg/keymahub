package dev.keymahub.poc.priv

/**
 * Hand-written binder protocol between the app and [PrivilegedService]. Avoids AIDL so
 * the privileged side stays a single small class.
 */
object PrivProtocol {
    const val DESCRIPTOR = "dev.keymahub.poc.IPrivileged"

    /** () -> String */
    const val INFO = 1
    /** () -> String? (error or null) — creates the keyboard + mouse UHID devices. */
    const val UHID_CREATE = 2
    /** () -> Unit */
    const val UHID_DESTROY = 3
    /** oneway (int device, byte[] report) */
    const val UHID_INPUT = 4
    /** oneway (InputEvent) — injected with InputManager. */
    const val INJECT = 5
    /** (int seconds) -> String report — P0-4 evdev open/grab/read probe. */
    const val EVDEV_PROBE = 6
    /** (IBinder callback) -> String? — Android host: grab keyboards/mice, route via HostRouter. */
    const val CAPTURE_START = 7
    /** () -> Unit */
    const val CAPTURE_STOP = 8
    /** oneway (int available) — whether a Bluetooth target is connected. */
    const val SET_REMOTE_AVAILABLE = 9
    const val LAST = SET_REMOTE_AVAILABLE

    const val DEVICE_KEYBOARD = 0
    const val DEVICE_MOUSE = 1

    /** Shizuku calls this when the app unbinds with remove=true (AIDL `destroy() = 16777114`). */
    const val SHIZUKU_DESTROY = 16777115
}
