package com.kemahub.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kemahub.HubService
import com.kemahub.KemaAccessibilityService
import com.kemahub.Locales
import com.kemahub.R
import com.kemahub.ble.BleHid
import com.kemahub.core.Hub
import com.kemahub.core.HubStatus
import com.kemahub.core.ThemeMode
import com.kemahub.core.UiPrefs

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
            KemaTheme(ui) {
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    tick++
                    Hub.resolve(this@MainActivity)
                }
                val status by Hub.status.collectAsStateWithLifecycle()
                val log by Hub.log.collectAsStateWithLifecycle()
                val setup = remember(tick) { setupState() }

                if (!setup.done && !status.running) Setup(setup) else Main(status, log, ui)
            }
        }
    }

    @Composable
    private fun Main(status: HubStatus, log: String, ui: UiPrefs) {
        // "home", "profile:<id>", "devices", "settings"
        var route by rememberSaveable { mutableStateOf("home") }
        BackHandler(enabled = route != "home") { route = "home" }
        val onEdit: ((com.kemahub.core.Settings) -> com.kemahub.core.Settings) -> Unit = { change -> Hub.edit(this, change) }

        when {
            route.startsWith("profile:") -> ProfileScreen(
                profileId = route.removePrefix("profile:"),
                status = status,
                onBack = { route = "home" },
                onEdit = onEdit,
            )
            route == "devices" -> DevicesScreen(
                status = status,
                onBack = { route = "home" },
                onEdit = onEdit,
                onPairing = { on -> HubService.pairing(this, on) },
                onDisconnect = { address ->
                    Hub.edit(this) { it.setBlocked(address, true) }
                    BleHid.disconnect(address)
                },
                onAllow = { address -> Hub.edit(this) { it.setBlocked(address, false) } },
                onRemove = { address ->
                    Hub.edit(this) { it.forgetDevice(address) }
                    BleHid.disconnect(address)
                    BleHid.unpair(this, address)
                },
            )
            route == "settings" -> SettingsScreen(
                ui = ui,
                mods = status.settings.mods,
                onMods = { m -> Hub.edit(this) { it.setMods(m) } },
                language = remember(tick) { Locales.current(this) },
                onBack = { route = "home" },
                onUi = { change -> Hub.editUi(this, change) },
                onLanguage = { tag -> Locales.set(this, tag) },
            )
            else -> HomeScreen(
                status = status,
                log = log,
                onHosting = { on -> if (on) HubService.start(this) else HubService.stop(this) },
                onEdit = onEdit,
                onOpenProfile = { id -> route = "profile:$id" },
                onDevices = { route = "devices" },
                onSettings = { route = "settings" },
                onSendLog = ::sendLog,
            )
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
            onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            onAppInfo = ::openAppInfo,
        )
    }

    /** Opens the user's mail app with the log addressed to the developer; the user presses send there. */
    private fun sendLog(log: String) {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        val body = buildString {
            append("KeymaHub $version\n")
            append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")
            append(log)
        }
        val mail = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "KeymaHub diagnostic log ($version, ${Build.MODEL})")
            putExtra(Intent.EXTRA_TEXT, body)
            // Only mail apps: the selector limits the chooser to apps that handle mailto.
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        try {
            startActivity(mail)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.report_no_app, Toast.LENGTH_LONG).show()
        }
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
        val prefs = getSharedPreferences("kemahub", Context.MODE_PRIVATE)
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

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun setupState() = SetupState(
        notifications = !needed(Manifest.permission.POST_NOTIFICATIONS),
        bluetooth = BleHid.hasPermission(this),
        accessibility = KemaAccessibilityService.isEnabled(this),
    )

    private companion object {
        const val SUPPORT_EMAIL = "pqzmggg@gmail.com"
        val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}

/** Notifications are asked for but optional: KeymaHub runs without them (the notification is just hidden). */
data class SetupState(val notifications: Boolean, val bluetooth: Boolean, val accessibility: Boolean) {
    val runtimeDone get() = notifications && bluetooth
    val done get() = bluetooth && accessibility
}

@Composable
private fun KemaTheme(ui: UiPrefs, content: @Composable () -> Unit) {
    val dark = when (ui.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        ui.dynamicColor && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        ui.dynamicColor && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
