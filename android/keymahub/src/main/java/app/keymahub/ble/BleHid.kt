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
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.Process
import android.os.SystemClock
import app.keymahub.R
import app.keymahub.ble.HostTable.Change
import app.keymahub.core.Hub
import app.keymahub.hid.ConsumerReport
import app.keymahub.hid.HidDescriptors
import app.keymahub.hid.KeyboardReport
import app.keymahub.hid.MouseReport
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
 * of its own ([holds], also used to ask for the connection interval, see [pace]) and ends the link by
 * letting go of it. When another app or the system also uses the link, it stays up (Windows
 * with this phone): no input goes over it, and hosting on takes it back at once. Before letting
 * go the phone asks for a long connection interval, so a link that stays up idles cheaply.
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

    /** An empty service added and removed only to send hosts a Service Changed (see [refresh]). */
    private val REFRESH_SERVICE = UUID.fromString("8a3f1c52-4d1e-4b8a-9f3e-2c7d5a6b1e90")

    @Volatile private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var advertisingSet: AdvertisingSet? = null
    @Volatile private var creatingSet = false
    /** Hosting: advertising, accepting targets and sending input. */
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    /** Timeouts of the phone's joins ([join]) and service refreshes; kept apart from [main], which stop() clears. */
    private val timers = Handler(Looper.getMainLooper())
    /** Retries of stalled sends ([pump]): on their own thread, so a busy main thread (the app's UI) does not hold input back. */
    private val sendTimers = Handler(HandlerThread("ble-send", Process.THREAD_PRIORITY_DISPLAY).apply { start() }.looper)

    /** The services of the open GATT server. */
    private lateinit var db: HidGattDatabase

    /** Hosts' links, targets and subscriptions (loaded in start). */
    @Volatile private var hosts = HostTable()
    private var hostsLoaded = false

    private val lock = Any()
    /** Every device whose link the GATT server reported, by address. */
    private val devices = HashMap<String, BluetoothDevice>()
    /** Input on its way to each target (see [pump]). */
    private val outboxes = HashMap<String, Outbox>()
    /** The phone's own client connection over each host's link (see the class note and [join]). */
    private val holds = HashMap<String, Hold>()
    /** Links the GATT server joined itself (links up before it was opened, see [rejoinLinks]). */
    private val serverJoined = HashSet<String>()
    /** Hosts that read the report map since the GATT server opened: they looked at the keyboard again. */
    private val refreshed = HashSet<String>()
    /**
     * Hosts linked when the GATT server opened that have not looked at the keyboard again yet, and
     * when it opened (see [refresh]): one dropping the link within [FORGET_WINDOW_MS] forgot it.
     */
    private val refreshing = HashMap<String, Long>()
    /** Hosts already noted using the keyboard while hosting is off (see admit): noted once per link. */
    private val offNoted = HashSet<String>()
    /** The [REFRESH_SERVICE] while it is in the database. */
    private var refreshService: BluetoothGattService? = null
    /** Addresses using the HID service without being recognized as a target (see admit). */
    private val strangers = HashSet<String>()
    /** GATT requests on each link so far (see trace). */
    private val traced = HashMap<String, Int>()
    @Volatile private var appContext: Context? = null
    @Volatile private var active: String? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * A host dropped the link without looking at the keyboard again after the HID service was gone
     * for a while (see [refresh]): it has forgotten the keyboard and has to pair again. Called on a
     * Bluetooth thread with the host's address and name. Set by the accessibility service.
     */
    @Volatile var onForgotten: ((name: String) -> Unit)? = null

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
        log(if (on) "pairing mode on" else "pairing mode off")
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

    /** Sends subsequent reports to [address] (null: nowhere), and gives it the short connection interval (see [pace]). */
    fun select(address: String?) {
        synchronized(lock) {
            val old = active
            active = address
            if (old == address) return
            for (a in listOfNotNull(old, address)) holds[a]?.let { pace(a, it) }
        }
    }

    fun nameOf(address: String): String {
        val d = synchronized(lock) { devices[address] } ?: remote(address)
        return d?.let { runCatching { it.name }.getOrNull() } ?: address
    }

    private fun remote(address: String): BluetoothDevice? = runCatching {
        manager()?.adapter?.getRemoteDevice(address)
    }.getOrNull()

    private fun log(msg: String) = Hub.log("BLE HID: $msg")

    /** Logs off the caller's thread: for callers holding [lock] ([nameOf] asks the Bluetooth service). */
    private fun logLater(msg: () -> String) {
        timers.post { log(msg()) }
    }

    private fun manager(): BluetoothManager? = appContext?.getSystemService(BluetoothManager::class.java)

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 || listOf(
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
    ).all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    // ---------------------------------------------------------------- hosting on / off

    /**
     * Opens the GATT server if it is not open, hosting or not. Called as soon as the app's process
     * starts (the accessibility service connects): after an update the old process took the HID
     * service out of the database, and hosts still linked must see it back quickly (see [refresh]).
     * Blocking; call off the main thread. Returns null when open, else a string resource saying why not.
     */
    @Synchronized
    fun openServer(context: Context): Int? {
        if (!hasPermission(context)) return R.string.problem_bt_permission
        appContext = context.applicationContext
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return R.string.problem_bt_unsupported
        val adapter = manager.adapter ?: return R.string.problem_bt_unsupported
        if (!adapter.isEnabled) return R.string.problem_bt_off
        loadHosts(context, adapter)
        if (server != null) return null
        open(context, manager)?.let { return it }
        // Links up before the server: hosts that were using the keyboard when the old server went away.
        for (d in knownLinks(manager)) {
            adopt(d)
            synchronized(lock) { refreshing[d.address] = SystemClock.uptimeMillis() }
            refresh(d)
        }
        return null
    }

    /** Blocking; call off the main thread. Returns null when hosting, else a string resource saying why not. */
    @Synchronized
    fun start(context: Context): Int? {
        if (running) return null
        openServer(context)?.let { return it }
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return R.string.problem_bt_unsupported
        advertiser = manager.adapter?.bluetoothLeAdvertiser ?: return R.string.problem_ble_peripheral
        running = true
        advertise()
        rejoinLinks(manager)
        log("hosting (${hosts.ready().size} ready)")
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
        synchronized(lock) { active = null }
        // Nothing may stay pressed on a host whose link outlives hosting.
        releaseKeys(hosts.ready())
        val targets = synchronized(lock) {
            outboxes.clear()
            strangers.clear()
            devices.filterKeys { hosts.isTarget(it) || hosts.knows(it) }.values.toList()
        }
        hosts.dropLinks()
        for (d in targets) endLink(d)
        log("stopped hosting, ending ${targets.size} link(s)")
    }

    /** Sends "nothing pressed" on every input report to [addresses], bypassing the queues. */
    private fun releaseKeys(addresses: Set<String>) {
        for (address in addresses) {
            val d = synchronized(lock) { devices[address] } ?: continue
            runCatching {
                notify(d, db.keyboardIn, KeyboardReport().clear())
                notify(d, db.mouseIn, MouseReport().clear())
                notify(d, db.consumerIn, ConsumerReport().clear())
            }
        }
    }

    /** Links to known, paired, allowed hosts that are up. */
    private fun knownLinks(manager: BluetoothManager): List<BluetoothDevice> = runCatching {
        (manager.getConnectedDevices(BluetoothProfile.GATT_SERVER) + manager.getConnectedDevices(BluetoothProfile.GATT))
            .distinctBy { it.address }
            .filter { it.bondState == BluetoothDevice.BOND_BONDED && hosts.knows(it.address) && !isBlocked(it.address) }
    }.getOrDefault(emptyList())

    /**
     * The GATT server joins the link to [device]: it only reports links that come up after it
     * opened (or was left by [endLink]), and has to join the others to send notifications on them.
     */
    private fun adopt(device: BluetoothDevice) {
        synchronized(lock) { devices[device.address] = device }
        if (synchronized(lock) { serverJoined.add(device.address) }) runCatching { server?.connect(device, false) }
    }

    /**
     * Takes back links to known hosts that are still up: the phone cannot end a link it is the
     * peripheral of, so a host keeps it while hosting is off, and across the app's restarts.
     */
    private fun rejoinLinks(manager: BluetoothManager) {
        for (d in knownLinks(manager)) {
            val address = d.address
            if (hosts.isLinked(address)) continue
            log("${nameOf(address)} is still linked, taking it back")
            adopt(d)
            if (hosts.linkUp(address, bonded = true) == Change.READY) onReady(d, restored = true)
        }
    }

    /**
     * Gets a host that was linked while the HID service left the database (the app's process ended:
     * an update, a force stop) to look at it again. The stack tells linked hosts about both changes
     * (Service Changed), but a host busy taking the keyboard away when the service comes back
     * misses that it is back, then drops the link and forgets the keyboard (pairing again is the
     * only way back); one told a few seconds later looks again and keeps it. So until the host
     * reads the report map, or its link drops, the phone tells it again every [REFRESH_MS] by adding
     * or removing an empty service after all the others (the HID service and its handles stay as
     * they are).
     */
    private fun refresh(device: BluetoothDevice, round: Int = 0) {
        timers.postDelayed({
            val address = device.address
            val done = server == null || !isLinkUp(device) || synchronized(lock) { address in refreshed }
            if (done || round >= REFRESH_ROUNDS) {
                if (!done) log("${nameOf(address)} did not look at the keyboard again")
                if (refreshService != null) toggleRefreshService()
                return@postDelayed
            }
            log("telling ${nameOf(address)} again that the services changed (${round + 1})")
            toggleRefreshService()
            refresh(device, round + 1)
        }, REFRESH_MS)
    }

    /** Adds or removes [REFRESH_SERVICE]: either way linked hosts get a Service Changed. */
    private fun toggleRefreshService() {
        val s = server ?: return
        val svc = refreshService
        if (svc != null) {
            refreshService = null
            runCatching { s.removeService(svc) }
        } else {
            val added = BluetoothGattService(REFRESH_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            if (runCatching { s.addService(added) }.getOrDefault(false)) refreshService = added
        }
    }

    private fun isLinkUp(device: BluetoothDevice): Boolean {
        val manager = manager() ?: return false
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
        val linkUp = isLinkUp(device)
        // Checked and added in one go: two joins at once must not both connect (one hold would be lost).
        val hold = synchronized(lock) {
            val h = holds[address]
            if (h != null) {
                if (end && !h.ending) {
                    h.ending = true
                    if (h.up) letGo(address, h)
                } else if (!end && h.ending) {
                    // Hosting came back on before the hold let go: keep it, with its interval again.
                    h.ending = false
                    pace(address, h)
                }
                return
            }
            if (!linkUp) return
            Hold(ending = end).also { holds[address] = it }
        }
        val gatt = runCatching { device.connectGatt(ctx, false, HoldCallback(address, hold), BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) {
            synchronized(lock) { holds.remove(address, hold) }
            log("could not join the link to ${nameOf(address)}")
            return
        }
        hold.gatt = gatt
        // Joining a link that went down meanwhile would open one from the phone: give up then.
        timers.postDelayed({
            if (hold.up) return@postDelayed
            synchronized(lock) { holds.remove(address, hold) }
            runCatching { gatt.disconnect(); gatt.close() }
        }, JOIN_TIMEOUT_MS)
    }

    private class HoldCallback(private val address: String, private val hold: Hold) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hold.gatt = gatt
                    hold.up = true
                    synchronized(lock) {
                        if (hold.ending) letGo(address, hold) else pace(address, hold)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Let go (or the link is gone): the stack ends a link no app uses any more.
                    synchronized(lock) { holds.remove(address, hold) }
                    runCatching { gatt.close() }
                }
            }
        }
    }

    /**
     * Asks for the connection interval the link should have while it is kept: short for the
     * selected target, medium for the others. As a peripheral, Android never asks for one, so the
     * host may pick 30-50 ms — far too slow for a mouse. The client role can: CONNECTION_PRIORITY_HIGH
     * is roughly 11-15 ms. Only the selected target gets it: every link has its connection events
     * even with nothing to send, and the phone's one radio serves them all (and the phone's own
     * Bluetooth keyboard and mouse), so several short-interval links collide and delay the one in
     * use. CONNECTION_PRIORITY_BALANCED (roughly 30-50 ms) keeps a switch quick: the new interval
     * takes effect a few connection events after the request, and input goes over the link meanwhile.
     * Holds [lock].
     */
    private fun pace(address: String, hold: Hold) {
        if (!hold.up || hold.ending) return
        val gatt = hold.gatt ?: return
        val fast = address == active
        val priority = if (fast) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        val ok = runCatching { gatt.requestConnectionPriority(priority) }.getOrDefault(false)
        logLater { "${if (fast) "short" else "medium"} connection interval requested from ${nameOf(address)}: $ok" }
    }

    /**
     * Lets go of [hold] (ending the link unless something else uses it), after asking for a long
     * connection interval: the stack keeps a link's last parameters after its apps let go, and a
     * link the phone cannot end (the host's system or another app uses it, see the class note)
     * would otherwise stay at a short or medium one, taking radio time from the links in use and battery
     * while nothing goes over it. CONNECTION_PRIORITY_LOW_POWER is roughly 100-125 ms. The hold
     * stays [LOW_POWER_SETTLE_MS] so the host gets the request over a link still in use.
     */
    private fun letGo(address: String, hold: Hold) {
        val gatt = hold.gatt ?: return
        val ok = runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }.getOrDefault(false)
        logLater { "long connection interval requested from ${nameOf(address)}: $ok" }
        timers.postDelayed({
            synchronized(lock) {
                if (!hold.ending) return@postDelayed // hosting came back on
                holds.remove(address, hold)
            }
            runCatching { gatt.disconnect() }
        }, LOW_POWER_SETTLE_MS)
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
        log("opening GATT server")
        val s = manager.openGattServer(context.applicationContext, callback) ?: return R.string.problem_gatt
        db = HidGattDatabase()
        synchronized(lock) { refreshed.clear() }
        refreshService = null
        // One at a time, always in this order: a service is only in the database once
        // onServiceAdded says so, and hosts expect the same database every time.
        for (svc in db.services) {
            serviceAdded = CountDownLatch(1)
            if (!s.addService(svc) || !serviceAdded.await(3, TimeUnit.SECONDS)) {
                s.close()
                log("adding service ${svc.uuid} failed")
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
        log("services added")
        return null
    }

    @Volatile private var serviceAdded = CountDownLatch(1)

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
        pairing = false // as stop(): the next start begins with pairing mode off
        main.removeCallbacksAndMessages(null)
        runCatching { appContext?.unregisterReceiver(bluetoothReceiver) }
        runCatching { if (advertisingSet != null) advertiser?.stopAdvertisingSet(advertisingCallback) }
        advertisingSet = null
        creatingSet = false
        runCatching { server?.close() }
        server = null
        refreshService = null
        synchronized(lock) {
            refreshed.clear()
            refreshing.clear()
            offNoted.clear()
            holds.values.forEach { h -> runCatching { h.gatt?.close() } }
            holds.clear(); serverJoined.clear(); devices.clear(); outboxes.clear()
            strangers.clear(); traced.clear()
            active = null
        }
        hosts.dropLinks()
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
                        if (server != null) log("Bluetooth turned off")
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
        log("waiting for ${nameOf(address)} to connect")
        advertise()
        val manager = manager() ?: return
        rejoinLinks(manager)
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
        synchronized(lock) {
            val target = active ?: return false
            // A link that just went down: nothing is kept to be sent when it is back.
            if (target !in devices) return false
            outboxes.getOrPut(target) { Outbox() }.queue.push(reportId, data)
            pump(target)
        }
        return true
    }

    /** What waits to go to one target, and what is on its way. */
    private class Outbox {
        val queue = ReportQueue()
        /** Notifications sent but not yet confirmed by onNotificationSent, and when the last one went out. */
        var inFlight = 0
        var lastSent = 0L
        /** A [pump] retry is scheduled. */
        var retrying = false
        /** Since when reports have been waiting, and why (logged when the wait was long). */
        var waiting: Pair<Long, String>? = null
    }

    /**
     * Keeps up to [WINDOW] notifications outstanding per target (a few fit in one connection
     * event); the rest wait in the [ReportQueue], where mouse motion merges. A wait of
     * [SLOW_WAIT_MS] or more is logged with its cause, so a felt delay can be told apart from the
     * radio's. Holds [lock].
     */
    private fun pump(address: String) {
        val device = devices[address] ?: return
        val box = outboxes[address] ?: return
        val q = box.queue
        val now = SystemClock.uptimeMillis()
        if (box.inFlight > 0 && now - box.lastSent > IN_FLIGHT_TIMEOUT_MS) {
            // A confirmation got lost (or is late; a late one only lowers the count, floored at 0): don't stall.
            // Logged only when input was held back by it (not when it went unnoticed while idle).
            if (box.waiting != null) log("${box.inFlight} sent to ${nameOf(address)} unconfirmed after ${now - box.lastSent} ms, sending on")
            box.inFlight = 0
        }
        while (box.inFlight < WINDOW) {
            val item = q.poll() ?: break
            val ch = when (item.reportId) {
                HidDescriptors.REPORT_ID_MOUSE -> db.mouseIn
                HidDescriptors.REPORT_ID_CONSUMER -> db.consumerIn
                else -> db.keyboardIn
            }
            if (!notify(device, ch, item.data)) {
                q.unpoll(item) // stack busy: retry on the next confirmation or report
                break
            }
            box.inFlight++
            box.lastSent = now
        }
        if (q.size == 0) {
            box.waiting?.let { (since, why) ->
                if (now - since >= SLOW_WAIT_MS) log("input to ${nameOf(address)} waited ${now - since} ms ($why)")
            }
            box.waiting = null
            return
        }
        val full = box.inFlight >= WINDOW
        if (box.waiting == null) box.waiting = now to (if (full) "waiting for confirmations" else "Bluetooth stack busy")
        // Reports still waiting may get no confirmation to send them: none comes when the stack
        // was busy with nothing in flight, or when a confirmation got lost. Without a retry they
        // would wait for the next input (a key release held back keeps the key down on the host).
        if (!box.retrying) {
            box.retrying = true
            sendTimers.postDelayed({
                synchronized(lock) {
                    outboxes[address]?.retrying = false
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
                        outboxes.remove(address)
                        serverJoined.remove(address)
                        strangers.remove(address)
                        traced.remove(address)
                        offNoted.remove(address)
                    }
                    // Known hosts too: shows when a link ended after hosting stopped.
                    if (wasTarget || hosts.knows(address)) log("${nameOf(address)} disconnected${statusText(status)}")
                    val since = synchronized(lock) { refreshing.remove(address) }
                    if (since != null && SystemClock.uptimeMillis() - since <= FORGET_WINDOW_MS) {
                        log("${nameOf(address)} dropped the link without looking at the keyboard again: it has forgotten it (pair again)")
                        onForgotten?.invoke(nameOf(address))
                    }
                    if (change == Change.GONE) gone(address)
                    advertise() // hosts reconnect to it (a no-op while not hosting)
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            trace(device, "read ${label(ch)}${if (offset > 0) " @$offset" else ""}")
            if (ch.uuid == REPORT_MAP) synchronized(lock) {
                refreshed.add(device.address)
                refreshing.remove(device.address)
            }
            if (!admit(device, requestId, isHid(ch))) return
            respond(device, requestId, offset, db.valueOf(ch))
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            trace(device, "read ${label(d.characteristic)}/${short(d.uuid)}")
            if (!admit(device, requestId, isHid(d.characteristic))) return
            val report = db.reportOf(d.characteristic)
            val value = if (d.uuid == CCCD && report != null) {
                if (hosts.isSubscribed(device.address, report)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else {
                db.valueOf(d)
            }
            respond(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            trace(device, "write ${label(d.characteristic)}/${short(d.uuid)} = ${hex(value)}")
            if (!admit(device, requestId, isHid(d.characteristic))) return
            val report = db.reportOf(d.characteristic)
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

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            log("${nameOf(device.address)} PHY tx $txPhy rx $rxPhy (1: 1M, 2: 2M)${statusText(status)}")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(lock) {
                outboxes[device.address]?.let { it.inFlight = (it.inFlight - 1).coerceAtLeast(0) }
                pump(device.address)
            }
        }
    }

    /** A target enabled keyboard input ([restored]: it did before, and its link came back). */
    private fun onReady(device: BluetoothDevice, restored: Boolean) {
        val address = device.address
        log("${nameOf(address)} ready${if (restored) " (restored)" else ""}")
        if (!running) return
        listeners.forEach { it.onReady(address, nameOf(address)) }
        // A returning host may still be encrypting and opening its HID connection: joining the link
        // then gets in the way, so wait until it settles.
        if (restored) main.postDelayed({ tune(device) }, SETTLE_MS) else tune(device)
    }

    /** A target stopped listening or its link went down. */
    private fun gone(address: String) {
        synchronized(lock) {
            if (active == address) active = null
            outboxes.remove(address)
        }
        if (running) listeners.forEach { it.onGone(address) }
    }

    /**
     * Joins the link (its connection interval, and a way to end it), asks for the LE 2M PHY, and
     * advertise again (some controllers stop on connect). 2M sends each packet in half the air time,
     * leaving more room for the other links (and Wi-Fi) on the phone's radio; the host decides, and
     * one that does not support it stays on 1M.
     */
    private fun tune(device: BluetoothDevice) {
        if (!running || !hosts.isLinked(device.address)) return
        join(device, end = false)
        val le2m = runCatching { manager()?.adapter?.isLe2MPhySupported }.getOrNull() == true
        if (le2m) {
            runCatching { server?.setPreferredPhy(device, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }
        }
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
            } else if (synchronized(lock) { offNoted.add(address) }) {
                log("${nameOf(address)} uses the keyboard while hosting is off, ending the link")
                join(device, end = true)
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
            log("${nameOf(address)} connected${if (bonded) "" else " (pairing)"}")
            // A paired host recognized only now (its link came up under its private address): ready
            // at once with its saved subscriptions.
            if (hosts.linkUp(address, bonded) == Change.READY) onReady(device, restored = true)
            return true
        }
        // Not recognized: a paired host whose first requests still carry its private address (the
        // link itself was recognized), or a device that will have to pair. Serve it, but don't make
        // it a target; pairing is refused while pairing mode is off (onBondState).
        if (synchronized(lock) { strangers.add(address) }) {
            log("$address using the HID service (not recognized yet)")
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
        log("refused ${device.address} ($why)")
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
                log("advertising failed: $it")
            }
    }

    private fun scanResponse(withName: Boolean) = AdvertiseData.Builder().setIncludeDeviceName(withName).build()

    private val advertisingCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            creatingSet = false
            if (status != ADVERTISE_SUCCESS || set == null) {
                log("advertising failed ($status)")
                return
            }
            if (server == null) {
                // Bluetooth went off while the set was being made (closeAll): it went with it.
                runCatching { advertiser?.stopAdvertisingSet(this) }
                return
            }
            advertisingSet = set
            log("advertising")
            // Pairing mode may have changed while the set was being made (setPairing had no set to
            // update then): the name goes in or out now.
            set.setScanResponseData(scanResponse(withName = pairing))
            if (!running) set.enableAdvertising(false, 0, 0) // hosting stopped meanwhile
        }

        override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
            if (status != ADVERTISE_SUCCESS) log("advertising ${if (enable) "on" else "off"} failed ($status)")
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
        if (n <= TRACE_MAX) log("  ${nameOf(device.address)} $what")
        else if (n == TRACE_MAX + 1) log("  ${nameOf(device.address)} …")
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
    /** How long a hold stays after asking for a long connection interval (see [letGo]). */
    private const val LOW_POWER_SETTLE_MS = 2_000L
    /** How often, and how many times, a host is told again that the services changed (see [refresh]). */
    private const val REFRESH_MS = 1_500L
    private const val REFRESH_ROUNDS = 6
    /** A host linked when the server opened that drops the link this soon without looking again forgot the keyboard. */
    private const val FORGET_WINDOW_MS = 15_000L
    /** How long a new link is left alone before tuning it or restarting advertising. */
    private const val SETTLE_MS = 3_000L
    private const val WINDOW = 3
    /**
     * How long sent notifications may stay unconfirmed before sending goes on. Confirmations come
     * within a connection event or two (11-50 ms); waiting longer only holds input back.
     */
    private const val IN_FLIGHT_TIMEOUT_MS = 60L
    /** A wait for sending this long or longer is logged (see [pump]). */
    private const val SLOW_WAIT_MS = 50L
    /** How soon a send the stack was too busy for is tried again. */
    private const val BUSY_RETRY_MS = 5L
}
