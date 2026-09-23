package com.kemahub.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kemahub.HubService
import com.kemahub.KemaAccessibilityService
import com.kemahub.ble.BleHid
import com.kemahub.core.Hub

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.targets(this) // load saved targets for the first frame
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var tick by remember { mutableIntStateOf(0) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
                val status by Hub.status.collectAsStateWithLifecycle()
                val log by Hub.log.collectAsStateWithLifecycle()
                val setup = remember(tick) { setupState() }

                if (!setup.done && !status.running) {
                    SetupScreen(
                        setup = setup,
                        onNotifications = { request(Manifest.permission.POST_NOTIFICATIONS) },
                        onBluetooth = {
                            if (Build.VERSION.SDK_INT >= 31) {
                                request(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                            }
                        },
                        onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onAppInfo = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        },
                    )
                } else {
                    HomeScreen(
                        status = status,
                        log = log,
                        onStart = { HubService.start(this) },
                        onStop = { HubService.stop(this) },
                        onRename = { address, name -> Hub.editTargets(this) { it.rename(address, name) } },
                        onSlot = { address, slot -> Hub.editTargets(this) { it.setSlot(address, slot) } },
                        onForget = { address -> Hub.editTargets(this) { it.remove(address) } },
                    )
                }
            }
        }
    }

    private fun request(vararg perms: String) = permissions.launch(arrayOf(*perms))

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun setupState() = SetupState(
        notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
        bluetooth = BleHid.hasPermission(this),
        accessibility = KemaAccessibilityService.isEnabled(this),
    )
}

data class SetupState(val notifications: Boolean, val bluetooth: Boolean, val accessibility: Boolean) {
    val done get() = notifications && bluetooth && accessibility
}
