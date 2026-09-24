package app.keymahub.ble

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
import app.keymahub.R
import android.os.SystemClock
import app.keymahub.core.Hub
import app.keymahub.hid.HidDescriptors
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This phone as a Bluetooth LE keyboard + mouse (HID over GATT, "HOGP").
 *
 * Why LE rather than Classic HID: it uses GATT instead of the HID L2CAP channels, so the
 * phone's own Bluetooth keyboard and mouse stay connected (Classic disconnects them); and
 * several targets (PCs, tablets) stay connected at once, so switching is instant.
 *
 * Targets pair from their own Bluetooth settings ("add device"); nothing to install there.
 * A target becomes ready once it subscribes to the keyboard input report.
 */
@SuppressLint("MissingPermission") // checked in start()
object BleHid {
    /** Target events for the service; called on Bluetooth binder threads. */
    interface Listener {
        /** A target subscribed to keyboard input and can receive reports. */
        fun onReady(address: String, name: String)
        /** A target disconnected or stopped listening. */
        fun onGone(address: String)
    }

    private fun uuid16(v: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))

    private val HID_SERVICE = uuid16(0x1812)
    private val CCCD = uuid16(0x2902)
    private val REPORT_REFERENCE = uuid16(0x2908)
    private const val INPUT = 1
    private const val OUTPUT = 2

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var running = false
    /** False until this session's services are up: links reported earlier belong to the last session. */
    @Volatile private var accepting = false

    private lateinit var keyboardIn: BluetoothGattCharacteristic
    private lateinit var mouseIn: BluetoothGattCharacteristic
    private lateinit var consumerIn: BluetoothGattCharacteristic
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
    /** Client-role links over a target's link, only to ask it for a short connection interval. */
    private val fastLinks = HashMap<String, BluetoothGatt>()
    @Volatile private var appContext: Context? = null
    @Volatile private var advertisingMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    @Volatile private var active: String? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Devices the user disconnected: refused when they reconnect. Set by the service. */
    @Volatile var isBlocked: (address: String) -> Boolean = { false }

    /** Addresses of the targets the user keeps (paired through KeymaHub). Set by the service. */
    @Volatile var knownTargets: () -> Collection<String> = { emptyList() }

    /**
     * While on, new (unpaired) devices may connect and pair, and the phone advertises its name
     * quickly. While off, only already paired devices are accepted.
     */
    @Volatile var pairing = false
        private set

    fun setPairing(on: Boolean) {
        if (pairing == on) return
        pairing = on
        Hub.log(if (on) "BLE HID: pairing mode on" else "BLE HID: pairing mode off")
        advertisingMode = wantedMode()
        advertise(withName = on)
    }

    /** Drops the link to [address] (it may come back unless blocked). */
    fun disconnect(address: String) {
        closeLink(address) // the link stays up while the phone's own client connection holds it
        val d = synchronized(lock) { connected[address] } ?: return
        runCatching { server?.cancelConnection(d) }
    }

    /** Connects to paired [address] again ("connect", "allow"), as on start; restarts a pending attempt. */
    fun reconnect(address: String) {
        if (!running) return
        // The host has to connect: a link the phone opens is not taken as its keyboard (the host
        // shows it as not connected) and keeps the host from connecting itself. Drop any such
        // link and advertise quickly so the host finds the phone.
        closeLink(address)
        Hub.log("BLE HID: waiting for ${nameOf(address)} to connect")
        advertisingMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        advertise(withName = pairing)
    }

    private fun closeLink(address: String) {
        synchronized(lock) { fastLinks.remove(address) }?.let { runCatching { it.disconnect(); it.close() } }
    }

    /** Removes the phone's pairing with [address]. Best effort: the call is not public API. */
    fun unpair(context: Context, address: String): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return runCatching {
            val device = adapter.getRemoteDevice(address)
            device.javaClass.getMethod("removeBond").invoke(device) as Boolean
        }.onFailure { Hub.log("unpair $address failed: $it") }.getOrDefault(false)
            .also { context.getSharedPreferences(SUBSCRIPTIONS, Context.MODE_PRIVATE).edit().remove(address).apply() }
    }
    private var serviceAdded = CountDownLatch(1)

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Addresses of targets ready for input. */
    fun readyTargets(): Set<String> = if (!running) emptySet() else synchronized(lock) {
        connected.keys.filter { subscribed[it]?.contains(keyboardIn) == true }.toSet()
    }

    private fun targets(): List<String> = readyTargets().toList()

    /** Sends subsequent reports to [address] (null: nowhere). */
    fun select(address: String?) {
        active = address
    }

    fun nameOf(address: String) =
        synchronized(lock) { connected[address] }?.let { runCatching { it.name }.getOrNull() } ?: address

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 || listOf(
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
    ).all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    /** Blocking; call off the main thread. Returns null when ready, else a string resource saying why not. */
    fun start(context: Context): Int? {
        if (running) return null
        if (!hasPermission(context)) return R.string.problem_bt_permission
        appContext = context.applicationContext
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return R.string.problem_bt_unsupported
        val adapter = manager.adapter ?: return R.string.problem_bt_unsupported
        if (!adapter.isEnabled) return R.string.problem_bt_off
        val adv = adapter.bluetoothLeAdvertiser ?: return R.string.problem_ble_peripheral

        Hub.log("BLE HID: opening GATT server")
        val s = manager.openGattServer(context.applicationContext, callback) ?: return R.string.problem_gatt
        server = s
        values.clear()
        for (svc in listOf(hidService(), batteryService(), deviceInfoService())) {
            serviceAdded = CountDownLatch(1)
            if (!s.addService(svc) || !serviceAdded.await(3, TimeUnit.SECONDS)) {
                s.close()
                server = null
                Hub.log("BLE HID: adding service ${svc.uuid} failed")
                return R.string.problem_gatt
            }
        }
        Hub.log("BLE HID: services added, starting advertising")
        advertiser = adv
        running = true
        advertisingMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        advertise(withName = pairing)
        accepting = true
        Hub.log("BLE HID ready: add this phone as a Bluetooth device on the target")
        return null
    }

    fun stop() {
        if (!running) return
        running = false
        accepting = false
        pairing = false
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        val s = server
        synchronized(lock) {
            connected.values.forEach { runCatching { s?.cancelConnection(it) } }
            connected.clear()
            subscribed.clear()
            queues.clear()
            inFlight.clear()
            lastSent.clear()
            fastLinks.values.forEach { runCatching { it.disconnect(); it.close() } }
            fastLinks.clear()
        }
        runCatching { s?.close() }
        server = null
        active = null
    }

    /** Sends one report to the selected target; false when there is none. */
    fun send(reportId: Int, data: ByteArray): Boolean {
        val target = active ?: return false
        synchronized(lock) {
            queues.getOrPut(target) { ReportQueue() }.push(reportId, data)
            pump(target)
        }
        return true
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
            val ch = when (item.reportId) {
                HidDescriptors.REPORT_ID_MOUSE -> mouseIn
                HidDescriptors.REPORT_ID_CONSUMER -> consumerIn
                else -> keyboardIn
            }
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

    // ---------------------------------------------------------------- callbacks

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) serviceAdded.countDown()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Every LE link of the phone shows up here, the phone's own Bluetooth keyboard and
                    // mouse included. Only KeymaHub's devices are targets right away; any other device
                    // becomes one when it starts using the HID service (see admit).
                    // A link still up from the last session (hosting was just restarted) is about to
                    // drop; it is handled like any target coming back once it does.
                    if (!accepting) return
                    if (isBlocked(address)) return refuse(device, "disconnected by user")
                    val known = address in knownTargets()
                    if (known) {
                        synchronized(lock) { connected[address] = device }
                        Hub.log("BLE HID: ${nameOf(address)} connected${statusText(status)}")
                        restoreSubscriptions(device)
                    }
                    // Some phones stop advertising when any link comes up (the phone's own keyboard
                    // or the phone's own connection to a target included), and then no PC or tablet
                    // can find the phone. Restart it, but not for a device that is pairing: stopping
                    // the advertising set while a first connection is being encrypted and paired
                    // drops that link. For those it restarts once the target is ready.
                    if (known || device.bondState == BluetoothDevice.BOND_BONDED) advertise(withName = pairing)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val name = nameOf(address)
                    val wasTarget = synchronized(lock) {
                        subscribed.remove(address)
                        queues.remove(address)
                        inFlight.remove(address)
                        lastSent.remove(address)
                        fastLinks.remove(address)?.let { runCatching { it.close() } }
                        connected.remove(address) != null
                    }
                    if (!wasTarget) {
                        if (pairing) advertise(withName = true) // a pairing attempt that went nowhere
                        return
                    }
                    Hub.log("BLE HID: $name disconnected${statusText(status)}")
                    if (active == address) active = null
                    listeners.forEach { it.onGone(address) }
                    updateAdvertising()
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            if (!admit(device, requestId)) return
            respond(device, requestId, offset, values[ch] ?: ByteArray(0))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            if (!admit(device, requestId)) return
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
            if (!admit(device, requestId)) return
            if (d.uuid == CCCD) {
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                val (mask, changed) = synchronized(lock) {
                    val set = subscribed.getOrPut(device.address) { HashSet() }
                    val changed = if (on) set.add(d.characteristic) else set.remove(d.characteristic)
                    maskOf(set) to changed
                }
                saveSubscriptions(device.address, mask)
                // A host may enable notifications again after they were restored: already ready.
                if (d.characteristic === keyboardIn && changed) {
                    if (on) {
                        markReady(device, restored = false)
                    } else {
                        if (active == device.address) active = null
                        listeners.forEach { it.onGone(device.address) }
                        updateAdvertising()
                    }
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (!admit(device, requestId)) return
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
    private fun requestFastLink(device: BluetoothDevice) = link(device, auto = false)

    private fun link(device: BluetoothDevice, auto: Boolean) {
        val ctx = appContext ?: return
        synchronized(lock) { if (device.address in fastLinks) return }
        val gatt = device.connectGatt(ctx, auto, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Hub.log("BLE HID: short connection interval requested from ${nameOf(device.address)}: $ok")
                }
            }
        }, BluetoothDevice.TRANSPORT_LE) ?: return
        synchronized(lock) { fastLinks[device.address] = gatt }
    }

    /**
     * Advertise aggressively while pairing or waiting for a first target; slowly afterwards
     * (airtime). Paired devices reconnect without the name, so it is only sent while pairing.
     */
    private fun updateAdvertising() {
        val mode = wantedMode()
        if (mode != advertisingMode) {
            advertisingMode = mode
            advertise(withName = pairing)
        }
    }

    /**
     * Fast while pairing or with no target at all; medium while one of KeymaHub's devices is not
     * back yet, so a PC or tablet finds the phone again quickly; slow once all of them are here.
     */
    private fun wantedMode(): Int {
        val ready = targets()
        return when {
            pairing || ready.isEmpty() -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            knownTargets().any { it !in ready && !isBlocked(it) } -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }
    }

    /**
     * A device using the HID service is a target: KeymaHub's own devices (taken on connect), any
     * paired device, and new devices while pairing mode is on. Others are turned away.
     */
    private fun markReady(device: BluetoothDevice, restored: Boolean) {
        Hub.log("BLE HID: ${nameOf(device.address)} ready${if (restored) " (restored)" else ""}")
        requestFastLink(device)
        listeners.forEach { it.onReady(device.address, nameOf(device.address)) }
        // The link is settled: advertise again (some controllers stop on connect).
        advertisingMode = wantedMode()
        advertise(withName = pairing)
    }

    // ---------------------------------------------------------------- subscriptions
    // HOGP: the device keeps each paired host's notification settings (CCCDs) across connections.
    // A paired host that comes back does not enable notifications again; it expects input right
    // away. Without this the host reconnects but the phone never sees it as ready.

    private fun maskOf(set: Set<BluetoothGattCharacteristic>) =
        (if (keyboardIn in set) 1 else 0) or (if (mouseIn in set) 2 else 0) or (if (consumerIn in set) 4 else 0)

    private const val SUBSCRIPTIONS = "ble_subscriptions"

    private fun prefs() = appContext?.getSharedPreferences(SUBSCRIPTIONS, Context.MODE_PRIVATE)

    private fun saveSubscriptions(address: String, mask: Int) {
        prefs()?.edit()?.putInt(address, mask)?.apply()
    }

    /** A paired host reconnected: its notifications are on as it left them. */
    private fun restoreSubscriptions(device: BluetoothDevice) {
        val mask = prefs()?.getInt(device.address, 0) ?: 0
        if (mask == 0) return
        val set = buildSet {
            if (mask and 1 != 0) add(keyboardIn)
            if (mask and 2 != 0) add(mouseIn)
            if (mask and 4 != 0) add(consumerIn)
        }
        val wasReady = synchronized(lock) {
            val current = subscribed.getOrPut(device.address) { HashSet() }
            val had = keyboardIn in current
            current.addAll(set)
            had
        }
        if (keyboardIn in set && !wasReady) markReady(device, restored = true)
    }

    private fun admit(device: BluetoothDevice, requestId: Int): Boolean {
        val address = device.address
        if (synchronized(lock) { address in connected }) return true
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        when {
            isBlocked(address) -> refuse(device, "disconnected by user")
            !bonded && !pairing -> refuse(device, "not paired, pairing mode off")
            else -> {
                synchronized(lock) { connected[address] = device }
                Hub.log("BLE HID: ${nameOf(address)} connected${if (bonded) "" else " (pairing)"}")
                if (bonded) restoreSubscriptions(device)
                return true
            }
        }
        runCatching { server?.sendResponse(device, requestId, INSUFFICIENT_AUTHORIZATION, 0, null) }
        return false
    }

    /** ATT error "insufficient authorization" (BluetoothGatt has no constant for it before API 34). */
    private const val INSUFFICIENT_AUTHORIZATION = 0x08

    private fun refuse(device: BluetoothDevice, why: String) {
        Hub.log("BLE HID: refused ${device.address} ($why)")
        closeLink(device.address)
        runCatching { server?.cancelConnection(device) }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        // Long values (the report map) are read in pieces at increasing offsets.
        val part = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
    }

    // ---------------------------------------------------------------- advertising

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Hub.log("BLE HID: advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> {}
                ADVERTISE_FAILED_DATA_TOO_LARGE -> advertise(withName = false) // long phone name
                else -> Hub.log("BLE HID: advertising failed ($errorCode)")
            }
        }
    }

    /** " (status 0x3e)" for a failure; the HCI reason tells why a link dropped. */
    private fun statusText(status: Int) = if (status == BluetoothGatt.GATT_SUCCESS) "" else " (status 0x%02x)".format(status)

    private fun advertise(withName: Boolean) {
        val adv = advertiser ?: return
        if (!running) return
        runCatching { adv.stopAdvertising(advertiseCallback) }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                advertisingMode,
            )
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(HID_SERVICE)).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(withName).build()
        runCatching { adv.startAdvertising(settings, data, scan, advertiseCallback) }
            .onFailure { Hub.log("BLE HID: advertising failed: $it") }
    }

    private const val ENC_READ = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    private const val ENC_WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
    private const val WINDOW = 3
    private const val IN_FLIGHT_TIMEOUT_MS = 250L
}
