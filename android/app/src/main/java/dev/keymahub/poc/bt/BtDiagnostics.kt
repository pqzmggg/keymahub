package dev.keymahub.poc.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.keymahub.poc.AppLog

/**
 * Logs every Bluetooth link coming up or going down, with the reason when Android gives one,
 * so the app log shows exactly which step of host/relay start-up drops the phone's own
 * keyboard or mouse.
 */
object BtDiagnostics {
    @Volatile private var registered = false

    fun register(context: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                AppLog.i("BT adapter: ${adapterState(state)}")
                return
            }
            @Suppress("DEPRECATION")
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            val name = runCatching { device.name }.getOrNull() ?: device.address
            val type = when (runCatching { device.type }.getOrDefault(0)) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                else -> "?"
            }
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> AppLog.i("BT link up: $name ($type)")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> AppLog.i("BT link DOWN: $name ($type)")
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    AppLog.i("BT bond: $name → ${bondState(bond)}")
                }
            }
        }
    }

    private fun adapterState(s: Int) = when (s) {
        BluetoothAdapter.STATE_ON -> "on"
        BluetoothAdapter.STATE_OFF -> "off"
        BluetoothAdapter.STATE_TURNING_ON -> "turning on"
        BluetoothAdapter.STATE_TURNING_OFF -> "turning off"
        else -> "$s"
    }

    private fun bondState(s: Int) = when (s) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_NONE -> "none"
        else -> "$s"
    }
}
