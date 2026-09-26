package app.keymahub.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.keymahub.R
import app.keymahub.core.Hub
import app.keymahub.core.Profile

/**
 * One dynamic app shortcut per profile ("activate this profile"), in list order.
 * Galaxy Modes & Routines offers an app's shortcuts as routine actions, so a routine can
 * switch profiles; the same shortcuts show on a long-press of the launcher icon.
 */
object ProfileShortcuts {
    private const val PREFIX = "profile:"

    fun sync(context: Context, profiles: List<Profile>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(1)
            val shown = profiles.take(max)
            val shortcuts = shown.mapIndexed { rank, p ->
                ShortcutInfoCompat.Builder(context, PREFIX + p.id)
                    .setShortLabel(context.getString(R.string.shortcut_activate_short, p.name))
                    .setLongLabel(context.getString(R.string.shortcut_activate_profile, p.name))
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setRank(rank)
                    .setIntent(
                        Intent(ActivateProfileActivity.ACTION)
                            .setClass(context, ActivateProfileActivity::class.java)
                            .putExtra(ActivateProfileActivity.EXTRA_ID, p.id),
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            // Home-screen copies of deleted profiles: grey them out instead of leaving dead buttons.
            val gone = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .map { it.id }
                .filter { it.startsWith(PREFIX) && profiles.none { p -> PREFIX + p.id == it } }
            if (gone.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.shortcut_profile_missing))
            }
        }.onFailure { Hub.log("Shortcuts: ${it.message}") }
    }
}
