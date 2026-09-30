package app.keymahub.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keymahub.HubService
import app.keymahub.KeymaAccessibilityService
import app.keymahub.Locales
import app.keymahub.R
import app.keymahub.ble.BleHid
import app.keymahub.core.Hub
import app.keymahub.core.HubStatus
import app.keymahub.core.ThemeMode
import app.keymahub.core.UiPrefs

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.settings(this) // load saved settings for the first frame
        Hub.loadUi(this)
        setContent {
            val ui by Hub.ui.collectAsStateWithLifecycle()
            KeymaTheme(ui) {
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    tick++
                    Hub.resolve(this@MainActivity)
                }
                val status by Hub.status.collectAsStateWithLifecycle()
                val log by Hub.log.collectAsStateWithLifecycle()
                val setup = remember(tick, ui.batterySkipped) { setupState(ui.batterySkipped) }
                // Opened again from the home menu; after setup it shows by itself until seen (TUTORIAL_VERSION).
                var tutorial by rememberSaveable { mutableStateOf(false) }

                when {
                    !setup.done && !status.running -> Setup(setup)
                    tutorial || ui.tutorialSeen < TUTORIAL_VERSION -> TutorialScreen(
                        status = status,
                        onDone = {
                            tutorial = false
                            Hub.editUi(this) { it.copy(tutorialSeen = TUTORIAL_VERSION) }
                        },
                    )
                    else -> Main(status, log, ui, onTutorial = { tutorial = true })
                }
            }
        }
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    /**
     * Bluetooth turned back on: a "Bluetooth is off" notice left by hosting stopping no longer holds.
     * Only the notice goes; hosting stays off until turned on. Checked on opening too (the change
     * may have come while the app was not on screen).
     */
    private val bluetoothOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) clearBluetoothOff()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothOn, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothOn, filter)
        clearBluetoothOff()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(bluetoothOn) }
        super.onStop()
    }

    private fun clearBluetoothOff() {
        val on = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
        if (on) Hub.update { if (it.problem == R.string.problem_bt_off) it.copy(problem = null) else it }
    }

    override fun onDestroy() {
        // Closed (swiped away from recents), not just rotated: start the next visit with a fresh log.
        if (isFinishing && !isChangingConfigurations) Hub.clearLog()
        super.onDestroy()
    }

    @Composable
    private fun Main(status: HubStatus, log: String, ui: UiPrefs, onTutorial: () -> Unit) {
        // "home", "profile:<id>", "devices", "settings". With two panes, home and profiles share the
        // screen (the route picks the right pane, "home" there = the profile in use); devices and
        // settings always take the whole screen.
        var route by rememberSaveable { mutableStateOf("home") }
        // Where devices and settings go back to: the home or profile screen they were opened from.
        var lastProfile by rememberSaveable { mutableStateOf("home") }
        val twoPane = LocalConfiguration.current.screenWidthDp >= TWO_PANE_DP
        val fullScreen = route == "devices" || route == "settings"
        val open: (String) -> Unit = { r ->
            if (r == "home" || r.startsWith("profile:")) lastProfile = r
            route = r
        }
        val back = { open(if (fullScreen) lastProfile else "home") }
        // Two panes: back leaves the app from a profile (the list is already on screen).
        BackHandler(enabled = fullScreen || (!twoPane && route != "home")) { back() }
        val onEdit: ((app.keymahub.core.Settings) -> app.keymahub.core.Settings) -> Unit = { change -> Hub.edit(this, change) }
        // App shortcuts (and so Galaxy routines) follow the profile list: names and order.
        val shortcutKey = status.settings.profiles.map { it.id to it.name }
        LaunchedEffect(shortcutKey) { ProfileShortcuts.sync(this@MainActivity, status.settings.profiles) }

        val home = @Composable { selectedId: String? ->
            HomeScreen(
                status = status,
                log = log,
                onHosting = { on -> if (on) HubService.start(this) else HubService.stop(this) },
                onBluetoothSettings = ::openBluetoothSettings,
                onAddDevice = {
                    HubService.pairing(this, true)
                    open("devices")
                },
                onEdit = onEdit,
                onOpenProfile = { id -> open("profile:$id") },
                onDevices = { open("devices") },
                onSettings = { open("settings") },
                onTutorial = onTutorial,
                onCopyLog = ::copyLog,
                selectedId = selectedId,
                batteryExempt = remember(tick) { batteryExempt() },
                onBattery = ::askBatteryExempt,
            )
        }
        val detail = @Composable { r: String ->
            when {
                r.startsWith("profile:") -> key(r) {
                    ProfileScreen(
                        profileId = r.removePrefix("profile:"),
                        status = status,
                        onBack = { open("home") },
                        showBack = !twoPane,
                        onEdit = onEdit,
                        onSelect = { slot -> HubService.select(this, slot) },
                    )
                }
                r == "devices" -> DevicesScreen(
                    status = status,
                    onBack = back,
                    onEdit = onEdit,
                    onPairing = { on -> HubService.pairing(this, on) },
                    onConnect = { d ->
                        // Starting hosting connects to every paired device anyway.
                        if (HubService.running) BleHid.reconnect(d.address) else HubService.start(this)
                        Toast.makeText(this, getString(R.string.device_connecting, d.name), Toast.LENGTH_LONG).show()
                    },
                    onDisconnect = { address ->
                        Hub.edit(this) { it.setBlocked(address, true) }
                        BleHid.disconnect(address)
                    },
                    onAllow = { address ->
                        Hub.edit(this) { it.setBlocked(address, false) }
                        BleHid.reconnect(address)
                    },
                    onRemove = { address ->
                        // In control: back to this device first, before another device moves up to its number.
                        val slot = Hub.status.value.slot
                        if (HubService.running && slot != 0 && status.settings.active.addressOf(slot) == address) {
                            HubService.select(this, 0)
                        }
                        Hub.edit(this) { it.forgetDevice(address) }
                        BleHid.disconnect(address)
                        BleHid.unpair(this, address)
                    },
                )
                r == "settings" -> SettingsScreen(
                    ui = ui,
                    mods = status.settings.mods,
                    onMods = { m -> Hub.edit(this) { it.setMods(m) } },
                    language = remember(tick) { Locales.current(this) },
                    onBack = back,
                    onUi = { change -> Hub.editUi(this, change) },
                    onLanguage = { tag -> Locales.set(this, tag) },
                    batteryExempt = remember(tick) { batteryExempt() },
                    onBattery = ::askBatteryExempt,
                )
            }
        }

        if (twoPane && !fullScreen) {
            // "home" on the right = the profile in use, so the pane is never empty.
            val right = if (route == "home") "profile:${status.settings.activeId}" else route
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(LIST_PANE_DP.dp).fillMaxHeight()) {
                    home(right.removePrefix("profile:"))
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) { detail(right) }
            }
        } else if (route == "home") {
            home(null)
        } else {
            detail(route)
        }
    }

    /** Host mode setup. On opening, ask for the runtime permissions right away, then show the accessibility disclosure. */
    @Composable
    private fun Setup(setup: SetupState) {
        var autoStep by rememberSaveable { mutableStateOf(0) }
        var disclosure by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(setup) {
            if (!setup.runtimeDone && autoStep == 0) {
                autoStep = 1
                requestRuntime(automatic = true)
            } else if (setup.runtimeDone && !setup.accessibility && autoStep < 2) {
                autoStep = 2
                disclosure = true
            }
        }
        SetupScreen(
            setup = setup,
            showDisclosure = disclosure,
            onDisclosure = { disclosure = it },
            onNotifications = { requestRuntime(automatic = false, Manifest.permission.POST_NOTIFICATIONS) },
            onBluetooth = { requestRuntime(automatic = false, *BLUETOOTH) },
            onAccessibility = ::openAccessibilitySettings,
            onAppInfo = ::openAppInfo,
            onBattery = ::askBatteryExempt,
            onSkip = { Hub.editUi(this) { it.copy(batterySkipped = true) } },
        )
    }

    /** Copies the log, with the app and phone it came from, to the clipboard. */
    private fun copyLog(log: String) {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        val text = buildString {
            append("KeymaHub $version\n")
            append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")
            append(log)
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("KeymaHub log", text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.report_copied, Toast.LENGTH_SHORT).show()
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun needed(p: String) = when (p) {
        Manifest.permission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= 33
        else -> Build.VERSION.SDK_INT >= 31
    } && !granted(p)

    /**
     * Requests [only] (default: everything host mode needs). A permission the system will no longer
     * ask for (denied before, no rationale) sends a tap to the app info screen instead; the
     * automatic request on opening just skips it.
     */
    private fun requestRuntime(automatic: Boolean, vararg only: String) {
        val prefs = getSharedPreferences("keymahub", Context.MODE_PRIVATE)
        val wanted = (if (only.isEmpty()) listOf(Manifest.permission.POST_NOTIFICATIONS, *BLUETOOTH) else only.toList())
            .filter(::needed)
        if (wanted.isEmpty()) return
        val blocked = wanted.filter { prefs.getBoolean("asked:$it", false) && !shouldShowRequestPermissionRationale(it) }
        val ask = wanted - blocked.toSet()
        if (ask.isEmpty()) {
            if (!automatic) openAppInfo()
            return
        }
        prefs.edit().apply { ask.forEach { putBoolean("asked:$it", true) } }.apply()
        permissions.launch(ask.toTypedArray())
    }

    /**
     * KeymaHub's own page in the accessibility settings (its on/off switch), not the top of the
     * list where it is easy to miss (on Galaxy it is further down, under installed apps). Where that
     * page can't be opened, the list, scrolled to KeymaHub and highlighted where the settings app
     * supports it.
     */
    private fun openAccessibilitySettings() {
        val service = ComponentName(this, KeymaAccessibilityService::class.java).flattenToString()
        runCatching { startActivity(Intent(ACTION_ACCESSIBILITY_DETAILS).putExtra(Intent.EXTRA_COMPONENT_NAME, service)) }
            .onFailure {
                val highlight = Bundle().apply { putString(FRAGMENT_ARGS_KEY, service) }
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .putExtra(FRAGMENT_ARGS_KEY, service)
                            .putExtra(SHOW_FRAGMENT_ARGS, highlight),
                    )
                }
            }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun setupState(batterySkipped: Boolean) = SetupState(
        notifications = !needed(Manifest.permission.POST_NOTIFICATIONS),
        bluetooth = BleHid.hasPermission(this),
        accessibility = KeymaAccessibilityService.isEnabled(this),
        battery = batteryExempt(),
        batterySkipped = batterySkipped,
    )

    /** Whether KeymaHub is left out of battery optimization (the system may stop it otherwise). */
    private fun batteryExempt() = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    /** Asks to be left out of battery optimization; where that dialog is missing, the list of apps. */
    @SuppressLint("BatteryLife")
    private fun askBatteryExempt() {
        runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    private companion object {
        val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

        /** The settings page of one accessibility service (Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS), spelled out: not every SDK has the constant. */
        const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

        /** The settings app's extras for scrolling to and highlighting an entry in a list. */
        const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
        const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    }
}

/**
 * The setup steps. KeymaHub would run without notifications (the notification is just hidden), but
 * setup waits for them with the other two it needs. Leaving battery optimization ([battery]) is
 * recommended, not required: it can be skipped.
 */
data class SetupState(
    val notifications: Boolean,
    val bluetooth: Boolean,
    val accessibility: Boolean,
    val battery: Boolean,
    /** The optional battery step was skipped once (UiPrefs.batterySkipped). */
    val batterySkipped: Boolean,
) {
    val runtimeDone get() = notifications && bluetooth
    /** Steps 1 to 3: what hosting needs. The battery step can be skipped from here. */
    val required get() = bluetooth && accessibility && notifications
    /** All four steps done, or the battery one skipped: setup moves on by itself. */
    val done get() = required && (battery || batterySkipped)
}

@Composable
private fun KeymaTheme(ui: UiPrefs, content: @Composable () -> Unit) {
    val dark = when (ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // The app's own colors, not the wallpaper's (dynamic color): the same look on every phone.
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

/** Window width (dp) from which the profile list and the details sit side by side: unfolded Folds, tablets. */
private const val TWO_PANE_DP = 600

/** Width of the list pane when there are two. */
private const val LIST_PANE_DP = 380
