package app.keymahub.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import app.keymahub.Locales
import app.keymahub.R
import app.keymahub.core.ActivationMode
import app.keymahub.core.Hub

/**
 * Switches profile activation to manual, activates a profile and closes, without showing anything but a toast.
 *
 * Opened by the per-profile app shortcuts ([ProfileShortcuts]) — which is how Galaxy
 * Modes & Routines ("Open an app → shortcut"), the launcher long-press menu and home-screen
 * shortcuts reach it — and by automation apps (Tasker etc.) with an explicit intent:
 * action [ACTION], extra [EXTRA_ID] (profile id) or [EXTRA_NAME] (profile name, case-insensitive).
 */
class ActivateProfileActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Locales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Hub.settings(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val name = intent.getStringExtra(EXTRA_NAME)?.trim()
        val profile = id?.let { settings.profile(it) }
            ?: name?.let { n -> settings.profiles.firstOrNull { it.name.equals(n, ignoreCase = true) } }
        if (profile == null) {
            Hub.log("Shortcut: no profile id=$id name=$name")
            Toast.makeText(this, R.string.shortcut_profile_missing, Toast.LENGTH_SHORT).show()
        } else {
            // A routine picked this profile on purpose: keep it until the user changes it (no auto switching).
            Hub.edit(this) { it.setMode(ActivationMode.MANUAL).activate(profile.id) }
            Hub.log("Shortcut: activated profile ${profile.name}")
            Toast.makeText(this, getString(R.string.shortcut_profile_activated, profile.name), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        const val ACTION = "app.keymahub.action.ACTIVATE_PROFILE"
        const val EXTRA_ID = "app.keymahub.extra.PROFILE_ID"
        const val EXTRA_NAME = "app.keymahub.extra.PROFILE_NAME"
    }
}
