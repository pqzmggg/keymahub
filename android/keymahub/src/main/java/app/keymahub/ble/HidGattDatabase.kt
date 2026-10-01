package app.keymahub.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import app.keymahub.ble.HostTable.Report
import app.keymahub.hid.HidDescriptors
import java.util.UUID

internal fun uuid16(v: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))

internal val HID_SERVICE = uuid16(0x1812)
internal val CCCD = uuid16(0x2902)
/** Hosts read it when they look at the keyboard (again). */
internal val REPORT_MAP = uuid16(0x2A4B)

/**
 * The phone's GATT services as a keyboard + mouse: HID (keyboard, mouse and media key reports),
 * battery and device information, with the static value of each characteristic and descriptor.
 * Built anew for each GATT server; always the same database.
 */
internal class HidGattDatabase {
    /** Characteristic or descriptor -> static value. */
    private val values = HashMap<Any, ByteArray>()

    lateinit var keyboardIn: BluetoothGattCharacteristic
        private set
    lateinit var mouseIn: BluetoothGattCharacteristic
        private set
    lateinit var consumerIn: BluetoothGattCharacteristic
        private set
    private lateinit var batteryLevel: BluetoothGattCharacteristic

    /** In the order they are added to the server. */
    val services = listOf(hidService(), batteryService(), deviceInfoService())

    fun valueOf(attribute: Any): ByteArray = values[attribute] ?: ByteArray(0)

    /** The subscription a CCCD belongs to. */
    fun reportOf(ch: BluetoothGattCharacteristic): Report? = when {
        ch === keyboardIn -> Report.KEYBOARD
        ch === mouseIn -> Report.MOUSE
        ch === consumerIn -> Report.CONSUMER
        ch === batteryLevel -> Report.BATTERY
        else -> null
    }

    private fun characteristic(uuid: Int, props: Int, perms: Int) = BluetoothGattCharacteristic(uuid16(uuid), props, perms)

    private fun descriptor(uuid: UUID, perms: Int) = BluetoothGattDescriptor(uuid, perms)

    private fun report(id: Int, type: Int): BluetoothGattCharacteristic {
        val props = if (type == INPUT) {
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        } else {
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        }
        val c = characteristic(0x2A4D, props, ENC_READ or (if (type == OUTPUT) ENC_WRITE else 0))
        if (type == INPUT) c.addDescriptor(descriptor(CCCD, ENC_READ or ENC_WRITE))
        val ref = descriptor(REPORT_REFERENCE, ENC_READ)
        values[ref] = byteArrayOf(id.toByte(), type.toByte())
        c.addDescriptor(ref)
        values[c] = ByteArray(
            when {
                id == HidDescriptors.REPORT_ID_MOUSE -> 7
                id == HidDescriptors.REPORT_ID_CONSUMER -> 2
                type == OUTPUT -> 1
                else -> 8
            },
        )
        return c
    }

    private fun hidService() = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        // HID Information: bcdHID 1.11, country 0, flags = remote wake + normally connectable, as
        // real keyboards report: hosts use them to decide whether to wait for the keyboard to come back.
        addCharacteristic(characteristic(0x2A4A, BluetoothGattCharacteristic.PROPERTY_READ, ENC_READ)
            .also { values[it] = byteArrayOf(0x11, 0x01, 0x00, 0x03) })
        addCharacteristic(characteristic(0x2A4B, BluetoothGattCharacteristic.PROPERTY_READ, ENC_READ)
            .also { values[it] = HidDescriptors.COMBO }) // Report Map
        addCharacteristic(characteristic(0x2A4C, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, ENC_WRITE)
            .also { values[it] = byteArrayOf(0) }) // HID Control Point
        addCharacteristic(characteristic(
            0x2A4E,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            ENC_READ or ENC_WRITE,
        ).also { values[it] = byteArrayOf(1) }) // Protocol Mode = report
        keyboardIn = report(HidDescriptors.REPORT_ID_KEYBOARD, INPUT).also(::addCharacteristic)
        mouseIn = report(HidDescriptors.REPORT_ID_MOUSE, INPUT).also(::addCharacteristic)
        consumerIn = report(HidDescriptors.REPORT_ID_CONSUMER, INPUT).also(::addCharacteristic)
        addCharacteristic(report(HidDescriptors.REPORT_ID_KEYBOARD, OUTPUT)) // keyboard LEDs
    }

    private fun batteryService() = BluetoothGattService(uuid16(0x180F), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        batteryLevel = characteristic(
            0x2A19,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also {
            values[it] = byteArrayOf(100)
            it.addDescriptor(descriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        addCharacteristic(batteryLevel)
    }

    private fun deviceInfoService() = BluetoothGattService(uuid16(0x180A), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(characteristic(0x2A29, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            .also { values[it] = "KeymaHub".toByteArray() })
        // PnP ID: USB vendor-ID source, VID 0x1209 (pid.codes), PID 0x4B10, version 1.0 — little endian.
        addCharacteristic(characteristic(0x2A50, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
            .also { values[it] = byteArrayOf(0x02, 0x09, 0x12, 0x10, 0x4B, 0x00, 0x01) })
    }

    private companion object {
        const val INPUT = 1
        const val OUTPUT = 2
        val REPORT_REFERENCE = uuid16(0x2908)
        const val ENC_READ = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        const val ENC_WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
    }
}
