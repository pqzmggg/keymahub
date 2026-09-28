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
import android.os.SystemClock
import app.keymahub.R
import app.keymahub.ble.HostTable.Change
import app.keymahub.ble.HostTable.Report
import app.keymahub.core.Hub
import app.keymahub.hid.HidDescriptors
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This phone as a Bluetooth LE keyboard + mouse (HID over GATT, "HOGP"), behaving as a BLE
 * keyboard does (HOGP 1.0 §5, GAP privacy).
 *
 * Why LE rather than Classic HID: it uses GATT instead of the HID L2CAP channels, so the
 * phone's own Bluetooth keyboard and mouse stay connected (Classic disconnects them); and
 * several targets (PCs, tablets) stay connected at once, so switching is instant.
 *
 * - Pairing: hosts pair from their own Bluetooth settings ("add device") while pairing mode is
 *   on. The phone advertises with a private address that changes over time; hosts got its
 *   identity key (IRK) when pairing and recognize it at any address.
 * - The GATT database never changes while Bluetooth is on: the same services, in the same order,
 *   made once. Hosts keep it with the bond. It is never taken apart: a host told that the HID
 *   service is gone (Service Changed) forgets the keyboard, and Windows then never reconnects.
 * - Notification settings belong to the bond ([HostTable]): a returning host is ready at once,
 *   without subscribing again (Windows never does).
 * - Hosts connect, the phone only advertises (undirected, connectable). A bonded host waits for
 *   the advertising and connects on its own. The phone never opens a link itself: a host's HID
 *   does not use a link the phone opened.
 * - Hosting off is a keyboard switched off: the phone ends its links to the hosts and stops
 *   advertising. Hosts keep the bond and the keyboard, and reconnect when hosting is on again.
 *
 * Ending a link: apps have no call for it. The stack ends an LE link about a second after the
 * last app using it lets go, but only if an app used it at all: a link the host opened and no
 * app joined stays up for good. So the phone joins every host's link with a client connection
 * of its own ([holds], also used to ask for a short connection interval) and ends the link by
 * letting go of it. When another app or the system also uses the link, it stays up (Windows
 * with this phone): no input goes over it, and hosting on takes it back at once.
 */
@SuppressLint("MissingPermission") // checked in start()
object BleHid {
    /** Target events for the service; called on Bluetooth binder threads, only while hosting. */
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

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingSet: AdvertisingSet? = null
    @Volatile private var creatingSet = false
    /** Hosting: advertising, accepting targets and sending input. */
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    /** Timeouts of the phone's joins ([join]); kept apart from [main], which stop() clears. */
    private val timers = Handler(Looper.getMainLooper())
    /** Retries of stalled sends ([pump]); kept apart from [main], which stop() clears. */
    private val retries = Handler(Looper.getMainLooper())

    private lateinit var keyboardIn: BluetoothGattCharacteristic
    private lateinit var mouseIn: BluetoothGattCharacteristic
    private lateinit var consumerIn: BluetoothGattCharacteristic
    private lateinit var batteryLevel: BluetoothGattCharacteristic

    /** Hosts' links, targets and subscriptions (loaded in start). */
    @Volatile private var hosts = HostTable()
    private var hostsLoaded = false

    private val lock = Any()
    /** Every device whose link the GATT server reported, by address. */
    private val devices = HashMap<String, BluetoothDevice>()
    private val queues = HashMap<String, ReportQueue>()
    /** Notifications sent but not yet confirmed by onNotificationSent, and when the last one went out. */
    private val inFlight = HashMap<String, Int>()
    private val lastSent = HashMap<String, Long>()
    /** Targets with a [pump] retry scheduled. */
    private val retrying = HashSet<String>()
    /** The phone's own client connection over each host's link (see the class note and [join]). */
    private val holds = HashMap<String, Hold>()
    /** Links the GATT server joined itself (links up before it was opened, see [rejoinLinks]). */
    private val serverJoined = HashSet<String>()
    /** Addresses using the HID service without being recognized as a target (see admit). */
    private val strangers = HashSet<String>()
    /** GATT requests on each link so far (see trace). */
    private val traced = HashMap<String, Int>()
    @Volatile private var appContext: Context? = null
    @Volatile private var active: String? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Devices the user disconnected: refused when they reconnect. Set by the service. */
    @Volatile var isBlocked: (address: String) -> Boolean = { false }

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
        // Only the scan response changes: the advertising goes on.
        advertisingSet?.setScanResponseData(scanResponse(withName = on))
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Addresses of targets ready for input (none while not hosting). */
    fun readyTargets(): Set<String> = if (running) hosts.ready() else emptySet()

    /** Sends subsequent reports to [address] (null: nowhere). */
    fun select(address: String?) {
        active = address
    }

    fun nameOf(address: String): String {
        val d = synchronized(lock) { devices[address] } ?: remote(address)
        return d?.let { runCatching { it.name }.getOrNull() } ?: address
    }

    private fun remote(address: String): BluetoothDevice? = runCatching {
        appContext?.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
    }.getOrNull()

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 || listOf(
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
    ).all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    // ---------------------------------------------------------------- hosting on / off

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
        loadHosts(context, adapter)
        val opened = server == null
        if (opened) open(context, manager)?.let { return it }
        running = true
        advertise()
        rejoinLinks(manager, opened)
        Hub.log("BLE HID: hosting (${hosts.ready().size} ready)")
        return null
    }

    /**
     * Stops hosting, as a keyboard is switched off: no advertising, no input, and the links to the
     * hosts end (see the class note). Saved subscriptions stay, so the hosts are ready again as
     * soon as they reconnect.
     */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        pairing = false
        main.removeCallbacksAndMessages(null)
        advertisingSet?.enableAdvertising(false, 0, 0)
        advertisingSet?.setScanResponseData(scanResponse(withName = false))
        active = null
        // Nothing may stay pressed on a host whose link outlives hosting.
        releaseKeys(hosts.ready())
        val targets = synchronized(lock) {
            queues.clear()
            inFlight.clear()
            lastSent.clear()
            strangers.clear()
            devices.filterKeys { hosts.isTarget(it) || hosts.knows(it) }.values.toList()
        }
        hosts.dropLinks()
        for (d in targets) endLink(d)
        Hub.log("BLE HID: stopped hosting, ending ${targets.size} link(s)")
    }

    /** Sends "nothing pressed" on every input report to [addresses], bypassing the queues. */
    private fun releaseKeys(addresses: Set<String>) {
        for (address in addresses) {
            val d = synchronized(lock) { devices[address] } ?: continue
            runCatching {
                notify(d, keyboardIn, ByteArray(8))
                notify(d, mouseIn, ByteArray(7))
                notify(d, consumerIn, ByteArray(2))
            }
        }
    }

    /**
     * Takes back links to known hosts that are still up: kept by another app or the system while
     * hosting was off, or up before the GATT server was ([serverOpened]: it only reports links
     * that come up later, and has to join those to send notifications on them).
     */
    private fun rejoinLinks(manager: BluetoothManager, serverOpened: Boolean) {
        val up = runCatching {
            (manager.getConnectedDevices(BluetoothProfile.GATT_SERVER) + manager.getConnectedDevices(BluetoothProfile.GATT))
                .distinctBy { it.address }
        }.getOrDefault(emptyList())
        for (d in up) {
            val address = d.address
            if (d.bondState != BluetoothDevice.BOND_BONDED || !hosts.knows(address) || isBlocked(address)) continue
            if (hosts.isLinked(address)) continue
            Hub.log("BLE HID: ${nameOf(address)} is still linked, taking it back")
            synchronized(lock) { devices[address] = d }
            if (serverOpened && synchronized(lock) { serverJoined.add(address) }) runCatching { server?.connect(d, false) }
            if (hosts.linkUp(address, bonded = true) == Change.READY) onReady(d, restored = true)
        }
    }

    private fun isLinkUp(device: BluetoothDevice): Boolean {
        val manager = appContext?.getSystemService(BluetoothManager::class.java) ?: return false
        return runCatching { manager.getConnectionState(device, BluetoothProfile.GATT_SERVER) == BluetoothProfile.STATE_CONNECTED }
            .getOrDefault(false)
    }

    // ---------------------------------------------------------------- the phone's hold on a link

    /** The phone's client connection over a host's link. [ending]: let go of it once it is up. */
    private class Hold(@Volatile var ending: Boolean) {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var up = false
    }

    /**
     * Joins the link to [device] with a client connection (see the class note), or with [end] lets
     * go of it, which ends the link unless something else uses it. Only over a link that is up:
     * with none, connecting would open a link from the phone.
     */
    private fun join(device: BluetoothDevice, end: Boolean) {
        val ctx = appContext ?: return
        val address = device.address
        synchronized(lock) {
            val h = holds[address]
            if (h != null) {
                if (end && !h.ending) {
                    h.ending = true
                    if (h.up) h.gatt?.disconnect()
                }
                return
            }
        }
        if (!isLinkUp(device)) return
        val hold = Hold(ending = end)
        synchronized(lock) { holds[address] = hold }
        val gatt = runCatching { device.connectGatt(ctx, false, HoldCallback(address, hold), BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) {
            synchronized(lock) { if (holds[address] === hold) holds.remove(address) }
            Hub.log("BLE HID: could not join the link to ${nameOf(address)}")
            return
        }
        hold.gatt = gatt
        // Joining a link that went down meanwhile would open one from the phone: give up then.
        timers.postDelayed({
            if (hold.up) return@postDelayed
            synchronized(lock) { if (holds[address] === hold) holds.remove(address) }
            runCatching { gatt.disconnect(); gatt.close() }
        }, JOIN_TIMEOUT_MS)
    }

    private class HoldCallback(private val address: String, private val hold: Hold) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hold.gatt = gatt
                    hold.up = true
                    if (hold.ending) {
                        gatt.disconnect()
                    } else {
                        // As a peripheral, Android never asks for a short connection interval, so the
                        // host may pick 30-50 ms — far too slow for a mouse. The client role can:
                        // CONNECTION_PRIORITY_HIGH is roughly 11-15 ms.
                        val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        Hub.log("BLE HID: short connection interval requested from ${nameOf(address)}: $ok")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Let go (or the link is gone): the stack ends a link no app uses any more.
                    synchronized(lock) { if (holds[address] === hold) holds.remove(address) }
                    runCatching { gatt.close() }
                }
            }
        }
    }

    /** Ends the link to [device], as a keyboard switched off does (see the class note). */
    private fun endLink(device: BluetoothDevice) {
        // The server's own join, if any, goes first: the client connection has to be the last user.
        if (synchronized(lock) { serverJoined.remove(device.address) }) runCatching { server?.cancelConnection(device) }
        join(device, end = true)
    }

    // ---------------------------------------------------------------- GATT server

    /** Opens the GATT server with its services, once per Bluetooth-on period. */
    private fun open(context: Context, manager: BluetoothManager): Int? {
        Hub.log("BLE HID: opening GATT server")
        val s = manager.openGattServer(context.applicationContext, callback) ?: return R.string.problem_gatt
        values.clear()
        // One at a time, always in this order: a service is only in the database once
        // onServiceAdded says so, and hosts expect the same database every time.
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

    private var serviceAdded = CountDownLatch(1)

    /** Loads the saved subscriptions once, dropping hosts that are no longer paired. */
    private fun loadHosts(context: Context, adapter: BluetoothAdapter) {
        if (hostsLoaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hosts = HostTable(HostTable.decode(prefs.getString(KEY_SUBSCRIPTIONS, null))) { saved ->
            prefs.edit().putString(KEY_SUBSCRIPTIONS, HostTable.encode(saved)).apply()
        }
        runCatching { adapter.bondedDevices.map { it.address }.toSet() }.getOrNull()?.let { hosts.retainPaired(it) }
        hostsLoaded = true
    }

    /** Bluetooth turned off: the server, advertising set and links are gone with it; opened again on start. */
    private fun closeAll() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { appContext?.unregisterReceiver(bluetoothReceiver) }
        runCatching { advertisingSet?.let { _ -> advertiser?.stopAdvertisingSet(advertisingCallback) } }
        advertisingSet = null
        creatingSet = false
        runCatching { server?.close() }
        server = null
        synchronized(lock) {
            holds.values.forEach { h -> runCatching { h.gatt?.close() } }
            holds.clear(); serverJoined.clear(); devices.clear(); queues.clear()
            inFlight.clear(); lastSent.clear(); strangers.clear(); traced.clear()
        }
        hosts.dropLinks()
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

    // ---------------------------------------------------------------- device actions

    /** "Disconnect": ends the link to [address] (refused when it comes back while blocked). */
    fun disconnect(address: String) {
        val d = synchronized(lock) { devices[address] } ?: remote(address)
        if (hosts.linkDown(address) == Change.GONE) gone(address)
        if (d != null) endLink(d)
    }

    /** "Connect": the host connects when it sees the advertising; take its link back if it is already up. */
    fun reconnect(address: String) {
        if (!running) return
        Hub.log("BLE HID: waiting for ${nameOf(address)} to connect")
        advertise()
        val manager = appContext?.getSystemService(BluetoothManager::class.java) ?: return
        rejoinLinks(manager, serverOpened = false)
    }

    /** Removes the phone's pairing with [address]. Best effort: the call is not public API. */
    fun unpair(context: Context, address: String): Boolean {
        if (hosts.forget(address) == Change.GONE) gone(address)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return runCatching {
            val device = adapter.getRemoteDevice(address)
            device.javaClass.getMethod("removeBond").invoke(device) as Boolean
        }.onFailure { Hub.log("unpair $address failed: $it") }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- input

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
        val device = devices[address] ?: return
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
        // Reports still waiting may get no confirmation to send them: none comes when the stack
        // was busy with nothing in flight, or when a confirmation got lost. Without a retry they
        // would wait for the next input (a key release held back keeps the key down on the host).
        if (q.size > 0 && retrying.add(address)) {
            val full = (inFlight[address] ?: 0) >= WINDOW
            retries.postDelayed({
                synchronized(lock) {
                    retrying.remove(address)
                    pump(address)
                }
            }, if (full) IN_FLIGHT_TIMEOUT_MS + BUSY_RETRY_MS else BUSY_RETRY_MS)
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

    /** The subscription a CCCD belongs to. */
    private fun reportOf(ch: BluetoothGattCharacteristic): Report? = when {
        ch === keyboardIn -> Report.KEYBOARD
        ch === mouseIn -> Report.MOUSE
        ch === consumerIn -> Report.CONSUMER
        ch === batteryLevel -> Report.BATTERY
        else -> null
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
                    // Every LE link of the phone shows up here: the phone's own Bluetooth keyboard and
                    // mouse, links a host's system opens for its own use (Samsung devices read the
                    // battery level), and hosts. A paired host that subscribed before is ready at once.
                    synchronized(lock) {
                        traced.remove(address)
                        devices[address] = device
                    }
                    // While hosting is off the keyboard is off: a host that uses it anyway gets its link
                    // ended (admit).
                    if (!running) return
                    val bonded = device.bondState == BluetoothDevice.BOND_BONDED
                    if (bonded && hosts.knows(address) && isBlocked(address)) {
                        refuse(device, "disconnected by user")
                        return
                    }
                    if (hosts.linkUp(address, bonded) == Change.READY) onReady(device, restored = true)
                    // Some controllers stop advertising when a link comes up; the same set goes on once
                    // the link has settled (restarting at once drops a link still pairing).
                    main.postDelayed(::advertise, SETTLE_MS)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasTarget = hosts.isTarget(address)
                    val change = hosts.linkDown(address)
                    synchronized(lock) {
                        devices.remove(address)
                        queues.remove(address)
                        inFlight.remove(address)
                        lastSent.remove(address)
                        serverJoined.remove(address)
                        strangers.remove(address)
                        traced.remove(address)
                    }
                    // Known hosts too: shows when a link ended after hosting stopped.
                    if (wasTarget || hosts.knows(address)) Hub.log("BLE HID: ${nameOf(address)} disconnected${statusText(status)}")
                    if (change == Change.GONE) gone(address)
                    advertise() // hosts reconnect to it (a no-op while not hosting)
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            trace(device, "read ${label(ch)}${if (offset > 0) " @$offset" else ""}")
            if (!admit(device, requestId, isHid(ch))) return
            respond(device, requestId, offset, values[ch] ?: ByteArray(0))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            trace(device, "read ${label(d.characteristic)}/${short(d.uuid)}")
            if (!admit(device, requestId, isHid(d.characteristic))) return
            val report = reportOf(d.characteristic)
            val value = if (d.uuid == CCCD && report != null) {
                if (hosts.isSubscribed(device.address, report)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
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
            if (!admit(device, requestId, isHid(d.characteristic))) return
            val report = reportOf(d.characteristic)
            if (d.uuid == CCCD && report != null) {
                // Recorded for targets only (see HostTable.subscribe); others just get an answer.
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                when (hosts.subscribe(device.address, report, on)) {
                    Change.READY -> onReady(device, restored = false)
                    Change.GONE -> gone(device.address)
                    Change.NONE -> {}
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            trace(device, "write ${label(ch)} = ${hex(value)}")
            if (!admit(device, requestId, isHid(ch))) return
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

    /** A target enabled keyboard input ([restored]: it did before, and its link came back). */
    private fun onReady(device: BluetoothDevice, restored: Boolean) {
        val address = device.address
        Hub.log("BLE HID: ${nameOf(address)} ready${if (restored) " (restored)" else ""}")
        if (!running) return
        listeners.forEach { it.onReady(address, nameOf(address)) }
        // A returning host may still be encrypting and opening its HID connection: joining the link
        // then gets in the way, so wait until it settles.
        if (restored) main.postDelayed({ tune(device) }, SETTLE_MS) else tune(device)
    }

    /** A target stopped listening or its link went down. */
    private fun gone(address: String) {
        if (active == address) active = null
        synchronized(lock) { queues.remove(address) }
        if (running) listeners.forEach { it.onGone(address) }
    }

    /** Joins the link (short connection interval, and a way to end it), and advertise again (some controllers stop on connect). */
    private fun tune(device: BluetoothDevice) {
        if (!running || !hosts.isLinked(device.address)) return
        join(device, end = false)
        advertise()
    }

    private fun isHid(ch: BluetoothGattCharacteristic) = ch.service?.uuid == HID_SERVICE

    /**
     * Whether to answer a GATT request. A device using the HID service becomes a target: any paired
     * device, and new ones while pairing mode is on. The battery and device information services
     * are answered for anyone (hosts' systems read them on their own links). While hosting is off,
     * a paired host that uses the HID service is answered, and its link ended: the keyboard is off.
     */
    private fun admit(device: BluetoothDevice, requestId: Int, hid: Boolean): Boolean {
        val address = device.address
        if (!hid || hosts.isTarget(address)) return true
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        if (!running) {
            if (!bonded) {
                synchronized(lock) { strangers.add(address) } // pairing is refused (onBondState)
            } else if (synchronized(lock) { address !in holds }) {
                Hub.log("BLE HID: ${nameOf(address)} uses the keyboard while hosting is off, ending the link")
                endLink(device)
            }
            return true
        }
        if (isBlocked(address)) {
            refuse(device, "disconnected by user")
            runCatching { server?.sendResponse(device, requestId, INSUFFICIENT_AUTHORIZATION, 0, null) }
            return false
        }
        if (hosts.useHid(address, bonded, pairing)) {
            synchronized(lock) { devices.putIfAbsent(address, device) }
            Hub.log("BLE HID: ${nameOf(address)} connected${if (bonded) "" else " (pairing)"}")
            // A paired host recognized only now (its link came up under its private address): ready
            // at once with its saved subscriptions.
            if (hosts.linkUp(address, bonded) == Change.READY) onReady(device, restored = true)
            return true
        }
        // Not recognized: a paired host whose first requests still carry its private address (the
        // link itself was recognized), or a device that will have to pair. Serve it, but don't make
        // it a target; pairing is refused while pairing mode is off (onBondState).
        if (synchronized(lock) { strangers.add(address) }) {
            Hub.log("BLE HID: $address using the HID service (not recognized yet)")
        }
        return true
    }

    /** Refuses a device that starts pairing through the HID service while pairing mode is off; forgets unpaired hosts. */
    private fun onBondState(intent: Intent) {
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
            BluetoothDevice.BOND_NONE -> if (hosts.forget(device.address) == Change.GONE) gone(device.address)
            BluetoothDevice.BOND_BONDING -> {
                // Only devices that used the HID service: not the phone's own new keyboard or mouse.
                if (!pairing && synchronized(lock) { device.address in strangers }) refuse(device, "not paired, pairing mode off")
            }
        }
    }

    /** ATT error "insufficient authorization" (BluetoothGatt has no constant for it before API 34). */
    private const val INSUFFICIENT_AUTHORIZATION = 0x08

    private fun refuse(device: BluetoothDevice, why: String) {
        Hub.log("BLE HID: refused ${device.address} ($why)")
        endLink(device)
    }

    private fun respond(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray) {
        // Long values (the report map) are read in pieces at increasing offsets.
        val part = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
        server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
    }

    // ---------------------------------------------------------------- advertising

    /**
     * Advertising is one advertising set kept for as long as Bluetooth is on: undirected and
     * connectable, at Android's shortest interval (100 ms; HOGP's 20-30 ms fast phase is not
     * available to apps). Hosting enables and disables it; pairing mode swaps only its scan
     * response (the name). It stays on while hosting, so a host that lost its link (out of range,
     * restarted) comes back on its own. Its address changes over time; paired hosts resolve it.
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
            .onFailure {
                creatingSet = false
                Hub.log("BLE HID: advertising failed: $it")
            }
    }

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
            // Pairing mode may have changed while the set was being made (setPairing had no set to
            // update then): the name goes in or out now.
            set.setScanResponseData(scanResponse(withName = pairing))
            if (!running) set.enableAdvertising(false, 0, 0) // hosting stopped meanwhile
        }

        override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
            if (status != ADVERTISE_SUCCESS) Hub.log("BLE HID: advertising ${if (enable) "on" else "off"} failed ($status)")
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

    private const val PREFS = "keymahub_ble"
    private const val KEY_SUBSCRIPTIONS = "subscriptions"
    /** How long joining a link may take before it is given up (see [join]). */
    private const val JOIN_TIMEOUT_MS = 5_000L
    /** How long a new link is left alone before tuning it or restarting advertising. */
    private const val SETTLE_MS = 3_000L
    private const val ENC_READ = BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
    private const val ENC_WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
    private const val WINDOW = 3
    private const val IN_FLIGHT_TIMEOUT_MS = 250L
    /** How soon a send the stack was too busy for is tried again. */
    private const val BUSY_RETRY_MS = 5L
}
