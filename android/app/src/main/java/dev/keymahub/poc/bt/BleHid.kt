package dev.keymahub.poc.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import dev.keymahub.poc.AppLog
import dev.keymahub.poc.input.HidDescriptors
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This phone as a Bluetooth LE keyboard + mouse (HID over GATT, "HOGP").
 *
 * Why LE rather than Classic ([BtHid]): it uses GATT instead of the HID L2CAP channels, so
 * the phone's own Bluetooth keyboard and mouse stay connected; and several targets (PCs,
 * tablets) can be connected at once, so switching between them is instant.
 *
 * Targets pair from their own Bluetooth settings ("add device"); nothing to install there.
 * A target becomes ready once it subscribes to the keyboard input report.
 */
@SuppressLint("MissingPermission") // checked in start()
object BleHid : HidTransport {
    private fun uuid16(v: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))

    private val HID_SERVICE = uuid16(0x1812)
    private val CCCD = uuid16(0x2902)
    private val REPORT_REFERENCE = uuid16(0x2908)
    private const val INPUT = 1
    private const val OUTPUT = 2

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var running = false

    private lateinit var keyboardIn: BluetoothGattCharacteristic
    private lateinit var mouseIn: BluetoothGattCharacteristic
    private lateinit var batteryLevel: BluetoothGattCharacteristic

    private val lock = Any()
    /** Connected devices by address, in connection order. */
    private val connected = LinkedHashMap<String, BluetoothDevice>()
    /** Per device, the characteristics it enabled notifications for. */
    private val subscribed = HashMap<String, MutableSet<BluetoothGattCharacteristic>>()
    private val queues = HashMap<String, ReportQueue>()
    /** Notifications sent but not yet confirmed by onNotificationSent, and when the last one went out. */
    private val inFlight = HashMap<String, Int>()
    private val lastSent = HashMap<String, Long>()
    /** Client-role links used only to ask the target for a short connection interval. */
    private val fastLinks = HashMap<String, BluetoothGatt>()
    @Volatile private var appContext: Context? = null
    @Volatile private var advertisingFast = true
    @Volatile private var active: String? = null

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var serviceAdded = CountDownLatch(1)

    override fun addListener(l: (Boolean) -> Unit) {
        listeners.add(l)
    }

    override fun removeListener(l: (Boolean) -> Unit) {
        listeners.remove(l)
    }

    private fun targets(): List<String> = synchronized(lock) {
        connected.keys.filter { subscribed[it]?.contains(keyboardIn) == true }
    }

    override fun hasTarget() = active != null

    override fun state(): String {
        if (!running) return "BLE, 중지"
        val t = targets()
        if (t.isEmpty()) return "BLE, 광고 중 (PC의 블루투스 설정에서 '장치 추가')"
        return "BLE, 대상 ${t.size}개: " + t.joinToString { a -> (if (a == active) "▶" else "") + nameOf(a) }
    }

    private fun nameOf(address: String) =
        synchronized(lock) { connected[address] }?.let { runCatching { it.name }.getOrNull() } ?: address

    override fun start(context: Context): String? {
        if (running) return null
        if (!HidTransport.hasPermission(context)) return "블루투스 권한(근처 기기)이 필요합니다"
        appContext = context.applicationContext
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return "블루투스를 지원하지 않는 기기"
        val adapter = manager.adapter ?: return "블루투스를 지원하지 않는 기기"
        if (!adapter.isEnabled) return "블루투스를 켜 주세요"
        val adv = adapter.bluetoothLeAdvertiser ?: return "이 기기는 BLE 주변기기(광고) 모드를 지원하지 않습니다"

        AppLog.i("BLE HID: opening GATT server")
        val s = manager.openGattServer(context.applicationContext, callback) ?: return "GATT 서버를 열 수 없습니다"
        server = s
        values.clear()
        for (svc in listOf(hidService(), batteryService(), deviceInfoService())) {
            serviceAdded = CountDownLatch(1)
            if (!s.addService(svc) || !serviceAdded.await(3, TimeUnit.SECONDS)) {
                s.close()
                server = null
                return "GATT 서비스 등록 실패 (${svc.uuid})"
            }
        }
        AppLog.i("BLE HID: services added, starting advertising")
        advertiser = adv
        running = true
        advertisingFast = true
        advertise(withName = true)
        AppLog.i("BLE HID ready: add this phone as a Bluetooth device on the target")
        return null
    }

    override fun stop(context: Context) {
        if (!running) return
        running = false
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        val s = server
        synchronized(lock) {
            connected.values.forEach { runCatching { s?.cancelConnection(it) } }
            connected.clear()
            subscribed.clear()
            queues.clear()
            inFlight.clear()
            lastSent.clear()
            fastLinks.values.forEach { runCatching { it.close() } }
            fastLinks.clear()
        }
        runCatching { s?.close() }
        server = null
        setActive(null)
    }

    override fun send(reportId: Int, data: ByteArray): Boolean {
        val target = active ?: return false
        synchronized(lock) {
            queues.getOrPut(target) { ReportQueue() }.push(reportId, data)
            pump(target)
        }
        return true
    }

    override fun nextTarget(): String? {
        if (!running) return null
        val t = targets()
        if (t.size < 2) return null
        val next = t[(t.indexOf(active) + 1) % t.size]
        setActive(next)
        return nameOf(next)
    }

    /**
     * Keeps up to [WINDOW] notifications outstanding per target (a few fit in one connection
     * event); the rest wait in the [ReportQueue], where mouse motion merges. Holds [lock].
     */
    private fun pump(address: String) {
        val device = connected[address] ?: return
        val q = queues[address] ?: return
        val now = SystemClock.uptimeMillis()
        if ((inFlight[address] ?: 0) > 0 && now - (lastSent[address] ?: 0) > IN_FLIGHT_TIMEOUT_MS) {
            inFlight[address] = 0 // a confirmation got lost; don't stall forever
        }
        while ((inFlight[address] ?: 0) < WINDOW) {
            val item = q.poll() ?: break
            val ch = if (item.reportId == HidDescriptors.REPORT_ID_MOUSE) mouseIn else keyboardIn
            if (!notify(device, ch, item.data)) {
                q.unpoll(item) // stack busy: retry on the next confirmation or report
                break
            }
            inFlight[address] = (inFlight[address] ?: 0) + 1
            lastSent[address] = now
        }
    }

    private fun notify(device: BluetoothDevice, ch: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val s = server ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            s.notifyCharacteristicChanged(device, ch, false, data) == 0 // BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(device, ch, false)
        }
    }

    private fun setActive(address: String?) {
        val changed = active != address
        active = address
        if (changed) {
            address?.let { AppLog.i("BLE HID: active target → ${nameOf(it)}") }
            listeners.forEach { it(address != null) }
        }
    }

    // ---------------------------------------------------------------- GATT database

    private fun characteristic(uuid: Int, props: Int, perms: Int) = BluetoothGattCharacteristic(uuid16(uuid), props, perms)

    private fun descriptor(uuid: UUID, perms: Int, value: ByteArray? = null) =
        BluetoothGattDescriptor(uuid, perms).also { d ->
            @Suppress("DEPRECATION")
            if (value != null) d.value = value
        }

    private val values = HashMap<Any, ByteArray>() // characteristic/descriptor -> static value

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
        values[c] = ByteArray(if (id == HidDescriptors.REPORT_ID_MOUSE) 7 else if (type == OUTPUT) 1 else 8)
        return c
    }

    private fun hidService() = BluetoothGattService(HID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        // HID Information: bcdHID 1.11, country 0, flags = normally connectable.
        addCharacteristic(characteristic(0x2A4A, BluetoothGattCharacteristic.PROPERTY_READ, ENC_READ)
            .also { values[it] = byteArrayOf(0x11, 0x01, 0x00, 0x02) })
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

    // ---------------------------------------------------------------- callbacks

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) serviceAdded.countDown()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) { connected[address] = device }
                    AppLog.i("BLE HID: ${nameOf(address)} connected")
                    // Some controllers stop advertising on connect; keep accepting more targets.
                    advertise(withName = true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val name = nameOf(address)
                    synchronized(lock) {
                        connected.remove(address)
                        subscribed.remove(address)
                        queues.remove(address)
                        inFlight.remove(address)
                        lastSent.remove(address)
                        fastLinks.remove(address)?.let { runCatching { it.close() } }
                    }
                    AppLog.i("BLE HID: $name disconnected")
                    if (active == address) setActive(targets().firstOrNull())
                    updateAdvertising()
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            respond(device, requestId, offset, values[ch] ?: ByteArray(0))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val value = if (d.uuid == CCCD) {
                val on = synchronized(lock) { subscribed[device.address]?.contains(d.characteristic) == true }
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                values[d] ?: ByteArray(0)
            }
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (d.uuid == CCCD) {
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                synchronized(lock) {
                    val set = subscribed.getOrPut(device.address) { HashSet() }
                    if (on) set.add(d.characteristic) else set.remove(d.characteristic)
                }
                if (d.characteristic === keyboardIn) {
                    if (on) {
                        AppLog.i("BLE HID: ${nameOf(device.address)} ready")
                        requestFastLink(device)
                        if (active == null) setActive(device.address) else listeners.forEach { it(true) }
                    } else if (active == device.address) {
                        setActive(targets().firstOrNull())
                    }
                    updateAdvertising()
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            // Protocol mode, control point (suspend/exit suspend) and keyboard LEDs: accepted, ignored.
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                inFlight[device.address] = ((inFlight[device.address] ?: 1) - 1).coerceAtLeast(0)
                pump(device.address)
            }
        }
    }

    /**
     * As a peripheral, Android never asks for a short connection interval, so the target may
     * pick 30-50 ms — far too slow for a mouse. A client-role connection over the same link
     * gives access to requestConnectionPriority(HIGH), i.e. roughly 11-15 ms.
     */
    private fun requestFastLink(device: BluetoothDevice) {
        val ctx = appContext ?: return
        synchronized(lock) { if (device.address in fastLinks) return }
        val gatt = device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    AppLog.i("BLE HID: short connection interval requested from ${nameOf(device.address)}: $ok")
                }
            }
        }, BluetoothDevice.TRANSPORT_LE) ?: return
        synchronized(lock) { fastLinks[device.address] = gatt }
    }

    /** Advertise aggressively only while waiting for a first target; slowly afterwards (airtime). */
    private fun updateAdvertising() {
        val fast = targets().isEmpty()
        if (fast != advertisingFast) {
            advertisingFast = fast
            advertise(withName = true)
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        // Long values (the report map) are read in pieces at increasing offsets.
        val part = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
    }

    // ---------------------------------------------------------------- advertising

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            AppLog.i("BLE HID: advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> {}
                ADVERTISE_FAILED_DATA_TOO_LARGE -> advertise(withName = false) // long phone name
                else -> AppLog.i("BLE HID: advertising failed ($errorCode)")
            }
        }
    }

    private fun advertise(withName: Boolean) {
        val adv = advertiser ?: return
        if (!running) return
        runCatching { adv.stopAdvertising(advertiseCallback) }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (advertisingFast) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            )
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(HID_SERVICE)).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        runCatching { adv.startAdvertising(settings, data, scan, advertiseCallback) }
            .onFailure { AppLog.i("BLE HID: advertising failed: $it") }
    }

    private const val ENC_READ = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    private const val ENC_WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
    private const val WINDOW = 3
    private const val IN_FLIGHT_TIMEOUT_MS = 250L
}
