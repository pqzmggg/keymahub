package dev.keymanc.poc.bt

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.keymanc.poc.BtMode
import dev.keymanc.poc.Prefs

/** This phone as a Bluetooth keyboard + mouse, sending reports to the active target. */
interface HidTransport {
    /** Blocking; call off the main thread. Returns null when ready, else why not. */
    fun start(context: Context): String?
    fun stop(context: Context)

    /** Sends one report to the active target; false when there is none. */
    fun send(reportId: Int, data: ByteArray): Boolean

    /** Whether some target is connected and ready for input. */
    fun hasTarget(): Boolean

    /** Makes the next connected target active; returns its name, or null if there is no other. */
    fun nextTarget(): String?

    fun state(): String

    /** Called with [hasTarget] whenever targets come or go. */
    fun addListener(l: (Boolean) -> Unit)
    fun removeListener(l: (Boolean) -> Unit)

    companion object {
        fun of(prefs: Prefs): HidTransport = when (prefs.btMode) {
            BtMode.BLE -> BleHid
            BtMode.CLASSIC -> BtHid
        }

        fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 || listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
        ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
