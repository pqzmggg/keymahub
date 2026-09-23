package com.kemahub.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import com.kemahub.ble.BleHid
import com.kemahub.core.Hub

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.settings(this) // load saved settings for the first frame
        setContent {
            KemaTheme {
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
                val status by Hub.status.collectAsStateWithLifecycle()
                val log by Hub.log.collectAsStateWithLifecycle()
                val setup = remember(tick) { setupState() }

                if (!setup.done && !status.running) {
                    // Host mode setup. On opening, ask for the runtime permissions right away, then
                    // show the accessibility disclosure; each automatically at most once per visit.
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
                } else {
                    HomeScreen(
                        status = status,
                        log = log,
                        onStart = { HubService.start(this) },
                        onStop = { HubService.stop(this) },
                        onEdit = { change -> Hub.edit(this, change) },
                    )
                }
            }
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
        val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}

/** Notifications are asked for but optional: KemaHub runs without them (the notification is just hidden). */
data class SetupState(val notifications: Boolean, val bluetooth: Boolean, val accessibility: Boolean) {
    val runtimeDone get() = notifications && bluetooth
    val done get() = bluetooth && accessibility
}

@Composable
private fun KemaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
