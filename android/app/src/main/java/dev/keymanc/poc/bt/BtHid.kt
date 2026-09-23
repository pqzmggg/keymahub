package dev.keymanc.poc.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.keymanc.poc.AppLog
import dev.keymanc.poc.input.HidDescriptors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * This phone as a Bluetooth keyboard + mouse (HID Device profile, Android 9+).
 * The target (e.g. a Windows PC) pairs with the phone in its own Bluetooth settings and
 * needs no keymanc software.
 */
@SuppressLint("MissingPermission") // checked in start()
object BtHid {
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile var registered = false
        private set
    @Volatile var connected: BluetoothDevice? = null
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var registeredLatch = CountDownLatch(1)

    fun onConnectionChanged(l: (Boolean) -> Unit) = listeners.add(l)
    fun removeListener(l: (Boolean) -> Unit) = listeners.remove(l)

    fun state(): String = when {
        connected != null -> "연결됨: ${runCatching { connected?.name }.getOrNull() ?: connected?.address}"
        registered -> "등록됨 (PC에서 페어링/연결 대기)"
        else -> "중지"
    }

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Blocking; call off the main thread. Returns null when registered. */
    fun start(context: Context): String? {
        if (registered) return null
        appContext = context.applicationContext
        if (!hasPermission(context)) return "블루투스 권한(근처 기기)이 필요합니다"
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return "블루투스를 지원하지 않는 기기"
        if (!adapter.isEnabled) return "블루투스를 켜 주세요"

        val proxyReady = CountDownLatch(1)
        val ok = adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                proxyReady.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                setConnected(null)
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok || !proxyReady.await(3, TimeUnit.SECONDS)) {
            return "이 기기는 블루투스 HID 장치 기능(HID Device profile)을 지원하지 않거나 꺼 두었습니다"
        }

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "keymanc", "keymanc keyboard and mouse", "keymanc",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptors.COMBO,
        )
        registeredLatch = CountDownLatch(1)
        if (hid?.registerApp(sdp, null, null, executor, callback) != true) {
            return "registerApp 실패 (다른 앱이 이미 HID 장치로 등록했을 수 있음)"
        }
        if (!registeredLatch.await(3, TimeUnit.SECONDS)) return "HID 앱 등록 응답 없음"
        AppLog.i("BT HID registered")
        reconnectLast(context)
        return null
    }

    fun stop(context: Context) {
        val h = hid ?: return
        runCatching { connected?.let { h.disconnect(it) } }
        runCatching { h.unregisterApp() }
        context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        hid = null
        registered = false
        setConnected(null)
    }

    /** Sends one report; false when no target is connected. */
    fun send(reportId: Int, data: ByteArray): Boolean {
        val d = connected ?: return false
        return hid?.sendReport(d, reportId, data) == true
    }

    private fun reconnectLast(context: Context) {
        val address = prefs(context).getString(LAST_TARGET, null) ?: return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val device = adapter.bondedDevices.firstOrNull { it.address == address } ?: return
        AppLog.i("BT HID: reconnecting to ${device.name ?: address}")
        hid?.connect(device)
    }

    @Volatile private var appContext: Context? = null

    private fun prefs(context: Context) = context.getSharedPreferences("bt", Context.MODE_PRIVATE)

    private fun setConnected(device: BluetoothDevice?) {
        if (connected == device) return
        connected = device
        listeners.forEach { it(device != null) }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BtHid.registered = registered
            if (registered) registeredLatch.countDown() else setConnected(null)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    AppLog.i("BT HID: connected to ${device.name ?: device.address}")
                    appContext?.let { prefs(it).edit().putString(LAST_TARGET, device.address).apply() }
                    setConnected(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (connected == device) {
                    AppLog.i("BT HID: disconnected from ${device.name ?: device.address}")
                    setConnected(null)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // Hosts may poll the current state once after connecting; an idle report is correct.
            val size = if (id.toInt() == HidDescriptors.REPORT_ID_MOUSE) 7 else 8
            hid?.replyReport(device, type, id, ByteArray(size))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // Keyboard LED state from the host; accepted and ignored.
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private const val LAST_TARGET = "last_target"
}
