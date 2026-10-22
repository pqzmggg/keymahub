package app.keymahub.ui

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import app.keymahub.HubService
import app.keymahub.KeymaAccessibilityService
import app.keymahub.Locales
import app.keymahub.R
import app.keymahub.ble.BleHid
import app.keymahub.core.Hub

/**
 * Turns hosting on or off and closes, without showing anything but a toast.
 *
 * Opened by the "hosting on" / "hosting off" app shortcuts ([ProfileShortcuts]) — which is how
 * Galaxy Modes & Routines ("Open an app → shortcut") reaches it, e.g. on when a routine starts and
 * off when it ends — and by automation apps (Tasker etc.) with an explicit intent: action
 * [ACTION_ON] or [ACTION_OFF].
 */
class HostingShortcutActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            ACTION_ON -> {
                // What would make the start fail, said now: a routine has no screen to show it on later.
                val problem = startProblem()
                if (problem != null) {
                    Hub.log("Shortcut: hosting not started: ${getString(problem)}")
                    Hub.update { it.copy(problem = problem) }
                    Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                } else {
                    // From a foreground activity, so starting the foreground service is allowed.
                    HubService.start(this)
                    Hub.log("Shortcut: hosting on")
                    Toast.makeText(this, R.string.shortcut_hosting_started, Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_OFF -> {
                HubService.stop(this)
                Hub.log("Shortcut: hosting off")
                Toast.makeText(this, R.string.shortcut_hosting_stopped, Toast.LENGTH_SHORT).show()
            }
            else -> Hub.log("Shortcut: unknown hosting action ${intent.action}")
        }
        finish()
    }

    private fun startProblem(): Int? {
        if (HubService.running) return null
        if (KeymaAccessibilityService.instance == null) return R.string.problem_a11y_off
        if (!BleHid.hasPermission(this)) return R.string.problem_bt_permission
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return R.string.problem_bt_unsupported
        if (!adapter.isEnabled) return R.string.problem_bt_off
        return null
    }

    companion object {
        const val ACTION_ON = "app.keymahub.action.HOSTING_ON"
        const val ACTION_OFF = "app.keymahub.action.HOSTING_OFF"
    }
}
