package app.keymahub.core

/** When hosting turns on by itself (Settings > Behavior), see [AutoHostingRule]. */
enum class AutoHostingMode {
    OFF,
    /** Any physical keyboard or mouse connecting. */
    ANY_INPUT,
    /** One of [UiPrefs.autoHostingDevices] connecting. */
    CHOSEN,
}

/**
 * A physical keyboard or mouse: [id] is its InputDevice descriptor, the same across reconnects and
 * restarts (Android tells no Bluetooth address); [name] is what it calls itself, kept to show it
 * while it is not connected.
 */
data class InputKey(val id: String, val name: String) {
    companion object {
        /** One per line, "id<TAB>name" (names lose tabs and line breaks). */
        fun encode(keys: List<InputKey>): String =
            keys.joinToString("\n") { "${it.id}\t${it.name.replace(Regex("[\t\r\n]"), " ")}" }

        fun decode(text: String?): List<InputKey> =
            text.orEmpty().lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else InputKey(line.substring(0, tab), line.substring(tab + 1))
            }.distinctBy { it.id }.toList()
    }
}

/**
 * Tells, from the keyboards and mice connected each time they change, when one that counts
 * ([AutoHostingMode]) has just connected, and when the last one that counts went away.
 *
 * Only a change counts: what was connected the time before is matched with the settings of now, so
 * changing the settings, or the first look (the accessibility service starting, e.g. after an
 * update), never turns hosting on by itself; nor does a device that stays connected after hosting
 * was turned off by hand. It does once that device connects again.
 */
class AutoHostingRule {
    private var last: List<InputKey>? = null

    /** What changed: [arrived], a device that counts and just connected; [allGone], the last one that counted went away. */
    data class Change(val arrived: InputKey? = null, val allGone: Boolean = false)

    fun update(mode: AutoHostingMode, chosen: Set<String>, connected: List<InputKey>): Change {
        val before = last
        last = connected
        if (before == null) return Change()
        val was = counted(mode, chosen, before).map { it.id }.toSet()
        val now = counted(mode, chosen, connected)
        return Change(
            arrived = now.firstOrNull { it.id !in was },
            allGone = was.isNotEmpty() && now.isEmpty(),
        )
    }

    companion object {
        fun counted(mode: AutoHostingMode, chosen: Set<String>, connected: List<InputKey>): List<InputKey> = when (mode) {
            AutoHostingMode.OFF -> emptyList()
            AutoHostingMode.ANY_INPUT -> connected
            AutoHostingMode.CHOSEN -> connected.filter { it.id in chosen }
        }
    }
}
