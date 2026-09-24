package app.keymahub.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        /** Bluetooth was turned off while hosting. */
        fun onBluetoothOff() {}
    }

    private fun uuid16(v: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))

    private val HID_SERVICE = uuid16(0x1812)
    private val CCCD = uuid16(0x2902)
    private val REPORT_REFERENCE = uuid16(0x2908)
    private const val INPUT = 1
    private const val OUTPUT = 2

    // The GATT server and the advertising set are opened once and kept while Bluetooth stays on;
    // hosting only turns advertising on and off and drops the links. Hosts keep what they learned
    // about the phone: the service layout (cached per paired device) and the advertising address
    // they reconnect to. Closing the server makes the HID service disappear under a connected host,
    // and a new advertising set comes with a new random address; either way a paired host that
    // would otherwise come back on its own does not.
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingSet: AdvertisingSet? = null
    /** Hosting: advertising, accepting targets and sending input. */
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    /** Addresses using the HID service without being recognized as a target (see admit). */
    private val strangers = HashSet<String>()
    /** GATT requests on each link so far (see trace). */
    private val traced = HashMap<String, Int>()

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
    @Volatile private var active: String? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Devices the user disconnected: refused when they reconnect. Set by the service. */
    @Volatile var isBlocked: (address: String) -> Boolean = { false }

    /** Addresses of the targets the user keeps (paired through KeymaHub). Set by the service. */
    @Volatile var knownTargets: () -> Collection<String> = { emptyList() }

    /**
     * While on, new (unpaired) devices may pair, and the advertising carries the phone's name so
     * it shows in "add device" lists. While off, only already paired devices are accepted.
     */
    @Volatile var pairing = false
        private set

    fun setPairing(on: Boolean) {
        if (pairing == on) return
        pairing = on
        Hub.log(if (on) "BLE HID: pairing mode on" else "BLE HID: pairing mode off")
        // Only the scan response changes: the advertising (and its address) goes on.
        advertisingSet?.setScanResponseData(scanResponse(withName = on))
    }

    /** Drops the link to [address] (it may come back unless blocked). */
    fun disconnect(address: String) {
        closeLink(address) // the link stays up while the phone's own client connection holds it
        val d = synchronized(lock) { connected[address] } ?: return
        runCatching { server?.cancelConnection(d) }
    }

    /** "Connect": the host has to connect; make sure the phone is advertising for it. */
    fun reconnect(address: String) {
        if (!running) return
        Hub.log("BLE HID: waiting for ${nameOf(address)} to connect")
        advertise()
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

    /** Blocking; call off the main thread. Returns null when hosting, else a string resource saying why not. */
    @Synchronized
    fun start(context: Context): Int? {
        if (running) return null
        if (!hasPermission(context)) return R.string.problem_bt_permission
        appContext = context.applicationContext
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return R.string.problem_bt_unsupported
        val adapter = manager.adapter ?: return R.string.problem_bt_unsupported
        if (!adapter.isEnabled) return R.string.problem_bt_off
        advertiser = adapter.bluetoothLeAdvertiser ?: return R.string.problem_ble_peripheral
        if (server == null) open(context, manager)?.let { return it }
        running = true
        advertise()
        Hub.log("BLE HID: hosting")
        adoptLinks(manager)
        return null
    }

    /**
     * Links that are still up take part right away. Stopping hosting cannot always drop a link: the
     * phone only lets go of its own use of it, and another app or the system (e.g. Samsung's own
     * phone-tablet features) may keep it. The host then still shows the phone connected and has no
     * reason to connect again, so without this it would stay unusable.
     */
    private fun adoptLinks(manager: BluetoothManager) {
        val known = knownTargets().toSet()
        for (device in runCatching { manager.getConnectedDevices(BluetoothProfile.GATT_SERVER) }.getOrDefault(emptyList())) {
            val address = device.address
            if (address !in known || isBlocked(address)) continue
            if (synchronized(lock) { address in connected }) continue
            synchronized(lock) { connected[address] = device }
            Hub.log("BLE HID: ${nameOf(address)} still connected")
            restoreSubscriptions(device)
        }
    }

    /** Opens the GATT server with its services, once per Bluetooth-on period. */
    private fun open(context: Context, manager: BluetoothManager): Int? {
        Hub.log("BLE HID: opening GATT server")
        val s = manager.openGattServer(context.applicationContext, callback) ?: return R.string.problem_gatt
        values.clear()
        // One at a time: a service is only in the database once onServiceAdded says so.
        for (svc in listOf(hidService(), batteryService(), deviceInfoService())) {
            serviceAdded = CountDownLatch(1)
            if (!s.addService(svc) || !serviceAdded.await(3, TimeUnit.SECONDS)) {
                s.close()
                Hub.log("BLE HID: adding service ${svc.uuid} failed")
                return R.string.problem_gatt
            }
        }
        server = s
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(bluetoothReceiver, bluetoothEvents, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(bluetoothReceiver, bluetoothEvents)
        }
        Hub.log("BLE HID: services added")
        return null
    }

    /** Stops hosting: no advertising, targets disconnected. The server and advertising set stay. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        pairing = false
        main.removeCallbacksAndMessages(null)
        advertisingSet?.enableAdvertising(false, 0, 0)
        advertisingSet?.setScanResponseData(scanResponse(withName = false))
        val s = server
        val links = synchronized(lock) { connected.values.toList() }
        synchronized(lock) {
            fastLinks.values.forEach { runCatching { it.disconnect(); it.close() } }
            fastLinks.clear()
            links.forEach { runCatching { s?.cancelConnection(it) } }
            connected.clear()
            subscribed.clear()
            queues.clear()
            inFlight.clear()
            lastSent.clear()
            strangers.clear()
        }
        active = null
        Hub.log("BLE HID: stopped hosting")
        // Diagnostics: a link the phone could not drop (see adoptLinks).
        val manager = appContext?.getSystemService(BluetoothManager::class.java) ?: return
        main.postDelayed({
            if (running) return@postDelayed
            val up = links.filter { manager.getConnectionState(it, BluetoothProfile.GATT_SERVER) == BluetoothProfile.STATE_CONNECTED }
            if (up.isNotEmpty()) Hub.log("BLE HID: still linked after stopping (kept by another app or the system): ${up.joinToString { it.name ?: it.address }}")
        }, 2_000)
    }


    /** Bluetooth turned off: the server and advertising set are gone with it; opened again on start. */
    private fun closeAll() {
        running = false
        runCatching { appContext?.unregisterReceiver(bluetoothReceiver) }
        runCatching { advertisingSet?.let { _ -> advertiser?.stopAdvertisingSet(advertisingCallback) } }
        advertisingSet = null
        runCatching { server?.close() }
        server = null
        synchronized(lock) {
            fastLinks.clear(); connected.clear(); subscribed.clear(); queues.clear()
            inFlight.clear(); lastSent.clear(); strangers.clear(); traced.clear()
        }
        active = null
    }

    private val bluetoothEvents = IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        if (server != null) Hub.log("BLE HID: Bluetooth turned off")
                        val wasHosting = running
                        closeAll()
                        if (wasHosting) listeners.forEach { l -> l.onBluetoothOff() }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondState(intent)
            }
        }
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

    // ---------------------------------------------------------------- callbacks

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) serviceAdded.countDown()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) { traced.remove(address) }
                    // Every LE link of the phone shows up here, the phone's own Bluetooth keyboard and
                    // mouse included. Only KeymaHub's devices are targets right away; any other device
                    // becomes one when it starts using the HID service (see admit).
                    val known = address in knownTargets()
                    if (!known) return
                    if (!running) return refuse(device, "not hosting")
                    if (isBlocked(address)) return refuse(device, "disconnected by user")
                    synchronized(lock) { connected[address] = device }
                    Hub.log("BLE HID: ${nameOf(address)} connected${statusText(status)}")
                    restoreSubscriptions(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val name = nameOf(address)
                    val wasTarget = synchronized(lock) {
                        subscribed.remove(address)
                        queues.remove(address)
                        inFlight.remove(address)
                        lastSent.remove(address)
                        fastLinks.remove(address)?.let { runCatching { it.close() } }
                        strangers.remove(address)
                        traced.remove(address)
                        connected.remove(address) != null
                    }
                    if (!wasTarget) return
                    Hub.log("BLE HID: $name disconnected${statusText(status)}")
                    if (active == address) active = null
                    listeners.forEach { it.onGone(address) }
                    advertise() // back to advertising if the link had stopped it (same set, same address)
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            trace(device, "read ${label(ch)}${if (offset > 0) " @$offset" else ""}")
            if (!admit(device, requestId)) return
            respond(device, requestId, offset, values[ch] ?: ByteArray(0))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            trace(device, "read ${label(d.characteristic)}/${short(d.uuid)}")
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
            trace(device, "write ${label(d.characteristic)}/${short(d.uuid)} = ${hex(value)}")
            if (!admit(device, requestId)) return
            // Notification settings count for targets only; others just get an answer.
            if (d.uuid == CCCD && synchronized(lock) { device.address in connected }) {
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                val (mask, changed) = synchronized(lock) {
                    val set = subscribed.getOrPut(device.address) { HashSet() }
                    val changed = if (on) set.add(d.characteristic) else set.remove(d.characteristic)
                    maskOf(set) to changed
                }
                saveSubscriptions(device.address, mask)
                // A host may enable notifications again after they were restored: already ready.
                if (d.characteristic === keyboardIn && !changed && on) {
                    Hub.log("BLE HID: ${nameOf(device.address)} enabled input again")
                }
                if (d.characteristic === keyboardIn && changed) {
                    if (on) {
                        markReady(device, restored = false)
                    } else {
                        if (active == device.address) active = null
                        listeners.forEach { it.onGone(device.address) }
                    }
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            trace(device, "write ${label(ch)} = ${hex(value)}")
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
     * A device using the HID service is a target: KeymaHub's own devices (taken on connect), any
     * paired device, and new devices while pairing mode is on. Others are turned away.
     */
    private fun markReady(device: BluetoothDevice, restored: Boolean) {
        Hub.log("BLE HID: ${nameOf(device.address)} ready${if (restored) " (restored)" else ""}")
        listeners.forEach { it.onReady(device.address, nameOf(device.address)) }
        // A host that enabled notifications has set up the link; a restored one has only just
        // connected and is still encrypting and opening its HID connection.
        if (restored) afterSettling { tune(device) } else tune(device)
    }

    /** Short connection interval, and advertise again (some controllers stop on connect). */
    private fun tune(device: BluetoothDevice) {
        if (synchronized(lock) { device.address !in connected }) return
        requestFastLink(device)
        advertise()
    }

    /**
     * Runs [block] once a new link has settled. Changing the connection parameters or restarting
     * advertising while a host is still encrypting and opening its HID connection leaves the host
     * stuck at "connecting" (and drops a link that is pairing).
     */
    private fun afterSettling(block: () -> Unit) {
        main.postDelayed({ if (running) block() }, SETTLE_MS)
    }

    private const val SETTLE_MS = 3_000L

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
            !running -> refuse(device, "not hosting")
            isBlocked(address) -> refuse(device, "disconnected by user")
            bonded || pairing -> {
                synchronized(lock) { connected[address] = device }
                Hub.log("BLE HID: ${nameOf(address)} connected${if (bonded) "" else " (pairing)"}")
                if (bonded) restoreSubscriptions(device)
                return true
            }
            else -> {
                // Not recognized: a paired host whose first requests still carry its private address
                // (the link itself was recognized), or a device that will have to pair. Serve it, but
                // don't make it a target; pairing is refused while pairing mode is off (onBondState).
                if (synchronized(lock) { strangers.add(address) }) {
                    Hub.log("BLE HID: $address using the HID service (not recognized yet)")
                }
                return true
            }
        }
        runCatching { server?.sendResponse(device, requestId, INSUFFICIENT_AUTHORIZATION, 0, null) }
        return false
    }

    /** Refuses a device that starts pairing through the HID service while pairing mode is off. */
    private fun onBondState(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        if (state != BluetoothDevice.BOND_BONDING || pairing) return
        // Only devices that used the HID service: not the phone's own new keyboard or mouse.
        if (synchronized(lock) { device.address in strangers }) refuse(device, "not paired, pairing mode off")
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

    /**
     * Advertising is one advertising set kept for as long as Bluetooth is on (see the note on
     * [server]): its random address is what paired hosts reconnect to, and a new set would get a
     * new one. Hosting enables and disables it; pairing mode swaps only its scan response.
     */
    private fun advertise() {
        if (!running) return
        val set = advertisingSet
        if (set != null) {
            set.enableAdvertising(true, 0, 0)
            return
        }
        if (creatingSet) return
        creatingSet = true
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true) // every host can see it
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW) // ~100 ms: hosts find the phone quickly
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(HID_SERVICE)).build()
        runCatching { advertiser?.startAdvertisingSet(params, data, scanResponse(pairing), null, null, advertisingCallback) }
            .onFailure { creatingSet = false; Hub.log("BLE HID: advertising failed: $it") }
    }

    @Volatile private var creatingSet = false

    private fun scanResponse(withName: Boolean) = AdvertiseData.Builder().setIncludeDeviceName(withName).build()

    private val advertisingCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            creatingSet = false
            if (status != ADVERTISE_SUCCESS || set == null) {
                Hub.log("BLE HID: advertising failed ($status)")
                return
            }
            advertisingSet = set
            Hub.log("BLE HID: advertising")
            if (!running) set.enableAdvertising(false, 0, 0) // hosting stopped meanwhile
        }

        override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
            if (status == ADVERTISE_SUCCESS) Hub.log(if (enable) "BLE HID: advertising" else "BLE HID: advertising off")
        }

        override fun onScanResponseDataSet(set: AdvertisingSet?, status: Int) {
            // A long phone name may not fit next to the rest: advertise without it.
            if (status == ADVERTISE_FAILED_DATA_TOO_LARGE) set?.setScanResponseData(scanResponse(withName = false))
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /**
     * Logs the first GATT requests of each link: how a host sets up its HID connection, and where
     * it stalls when it does not finish ("connecting" on the host).
     */
    private fun trace(device: BluetoothDevice, what: String) {
        val n = synchronized(lock) { ((traced[device.address] ?: 0) + 1).also { traced[device.address] = it } }
        if (n <= TRACE_MAX) Hub.log("BLE HID:   ${nameOf(device.address)} $what")
        else if (n == TRACE_MAX + 1) Hub.log("BLE HID:   ${nameOf(device.address)} …")
    }

    private const val TRACE_MAX = 40

    private fun short(uuid: UUID) = "%04X".format((uuid.mostSignificantBits ushr 32).toInt() and 0xFFFF)

    /** "2A4D#12": characteristic and its instance (report characteristics share a UUID). */
    private fun label(ch: BluetoothGattCharacteristic) = "${short(ch.uuid)}#${ch.instanceId}"

    private fun hex(value: ByteArray?) = value?.joinToString("") { "%02x".format(it) } ?: "-"

    /** " (status 0x3e)" for a failure; the HCI reason tells why a link dropped. */
    private fun statusText(status: Int) = if (status == BluetoothGatt.GATT_SUCCESS) "" else " (status 0x%02x)".format(status)

    private const val ENC_READ = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    private const val ENC_WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
    private const val WINDOW = 3
    private const val IN_FLIGHT_TIMEOUT_MS = 250L
}
