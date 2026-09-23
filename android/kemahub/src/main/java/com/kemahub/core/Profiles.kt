package com.kemahub.core

/** Modifier bits of a [Hotkey]. Left and right keys count the same. */
object Mods {
    const val CTRL = 1
    const val ALT = 2
    const val SHIFT = 4
    const val META = 8

    /** The modifier bit of evdev key [code], or 0 if it is not a modifier. */
    fun of(code: Int): Int = when (code) {
        29, 97 -> CTRL
        56, 100 -> ALT
        42, 54 -> SHIFT
        125, 126 -> META
        else -> 0
    }
}

/** A key chord: exactly these modifiers ([Mods] bits) held, then evdev key [code]. */
data class Hotkey(val mods: Int, val code: Int) {
    /** Needs Ctrl, Alt or Meta (Shift alone would steal typing) and a non-modifier key. */
    val isValid get() = mods and (Mods.CTRL or Mods.ALT or Mods.META) != 0 && Mods.of(code) == 0 && code > 0
}

/** A Bluetooth device that has connected to this phone at least once. */
data class Device(val address: String, val name: String)

/**
 * A hosting setup: which receiver sits on which hotkey, and the hotkeys themselves.
 *
 * Slot 0 is this phone, slots 1..9 are receivers. [hotkeys] has one entry per slot
 * (default Ctrl+Alt+1 = phone, Ctrl+Alt+2..9, 0 = receivers 1..9).
 * An empty [name] is the unnamed default profile (the UI shows a translated name).
 */
data class Profile(
    val id: String,
    val name: String,
    val hotkeys: List<Hotkey> = DEFAULT_HOTKEYS,
    val receivers: Map<Int, String> = emptyMap(),
) {
    fun slotFor(hotkey: Hotkey): Int? = hotkeys.indexOf(hotkey).takeIf { it >= 0 }

    fun addressOf(slot: Int): String? = receivers[slot]

    fun slotOf(address: String): Int? = receivers.entries.firstOrNull { it.value == address }?.key

    companion object {
        const val SLOTS = 10
        const val PHONE = 0

        /** evdev KEY_1..KEY_9 = 2..10, KEY_0 = 11. */
        val DEFAULT_HOTKEYS: List<Hotkey> = (2..11).map { Hotkey(Mods.CTRL or Mods.ALT, it) }
    }
}

/** Everything the user configures. Immutable: every change returns a new value. */
data class Settings(
    val devices: List<Device> = emptyList(),
    val profiles: List<Profile> = listOf(Profile("p1", "")),
    val activeId: String = profiles.first().id,
) {
    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    fun device(address: String) = devices.firstOrNull { it.address == address }

    fun profile(id: String) = profiles.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- devices

    /** A device became ready: remember it, and give it the first free receiver slot of the active profile. */
    fun deviceConnected(address: String, name: String): Settings {
        val known = if (device(address) != null) this else copy(devices = devices + Device(address, clean(name)))
        val p = known.active
        if (p.slotOf(address) != null) return known
        val free = (1 until Profile.SLOTS).firstOrNull { it !in p.receivers } ?: return known
        return known.withProfile(p.copy(receivers = p.receivers + (free to address)))
    }

    fun renameDevice(address: String, name: String): Settings {
        val n = clean(name).ifEmpty { return this }
        return copy(devices = devices.map { if (it.address == address) it.copy(name = n) else it })
    }

    /** Forgets the device everywhere. */
    fun forgetDevice(address: String) = copy(
        devices = devices.filter { it.address != address },
        profiles = profiles.map { p -> p.copy(receivers = p.receivers.filterValues { it != address }) },
    )

    // ---------------------------------------------------------------- slots and hotkeys

    /**
     * Puts [address] (null = nobody) on receiver [slot] of profile [id]. If the device already
     * had another slot, whoever was on [slot] moves there (the two swap).
     */
    fun assign(id: String, slot: Int, address: String?): Settings {
        require(slot in 1 until Profile.SLOTS)
        val p = profile(id) ?: return this
        val r = p.receivers.toMutableMap()
        val occupant = r.remove(slot)
        if (address != null) {
            val old = p.slotOf(address)
            if (old != null && old != slot) {
                r.remove(old)
                if (occupant != null) r[old] = occupant
            }
            r[slot] = address
        }
        return withProfile(p.copy(receivers = r))
    }

    /** Sets the hotkey of [slot]; if another slot used that chord, the two swap. Invalid chords are ignored. */
    fun setHotkey(id: String, slot: Int, hotkey: Hotkey): Settings {
        require(slot in 0 until Profile.SLOTS)
        val p = profile(id) ?: return this
        if (!hotkey.isValid) return this
        val keys = p.hotkeys.toMutableList()
        val other = p.slotFor(hotkey)
        if (other != null) keys[other] = keys[slot]
        keys[slot] = hotkey
        return withProfile(p.copy(hotkeys = keys))
    }

    fun resetHotkeys(id: String): Settings {
        val p = profile(id) ?: return this
        return withProfile(p.copy(hotkeys = Profile.DEFAULT_HOTKEYS))
    }

    // ---------------------------------------------------------------- profiles

    /** Adds a copy of the active profile named [name] and makes it active. */
    fun addProfile(name: String): Settings {
        val n = (profiles.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val p = active.copy(id = "p$n", name = clean(name))
        return copy(profiles = profiles + p, activeId = p.id)
    }

    fun renameProfile(id: String, name: String): Settings {
        val p = profile(id) ?: return this
        return withProfile(p.copy(name = clean(name)))
    }

    /** The last profile cannot be deleted. */
    fun deleteProfile(id: String): Settings {
        if (profiles.size <= 1 || profile(id) == null) return this
        val rest = profiles.filter { it.id != id }
        return copy(profiles = rest, activeId = if (activeId == id) rest.first().id else activeId)
    }

    fun select(id: String) = if (profile(id) == null) this else copy(activeId = id)

    private fun withProfile(p: Profile) = copy(profiles = profiles.map { if (it.id == p.id) p else it })

    // ---------------------------------------------------------------- storage

    /** Line format, tab-separated; names never contain tabs or newlines. */
    fun encode(): String = buildString {
        append("kemahub-settings\t1\n")
        append("active\t").append(activeId).append('\n')
        for (d in devices) append("device\t${d.address}\t${d.name}\n")
        for (p in profiles) {
            val keys = p.hotkeys.joinToString(",") { "${it.mods}:${it.code}" }
            val recv = p.receivers.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            append("profile\t${p.id}\t${p.name}\t$keys\t$recv\n")
        }
    }

    companion object {
        /** Tolerates junk: bad lines are skipped, and missing parts fall back to defaults. */
        fun decode(text: String?): Settings {
            var active = ""
            val devices = mutableListOf<Device>()
            val profiles = mutableListOf<Profile>()
            for (line in text.orEmpty().lines()) {
                val f = line.split('\t')
                when (f[0]) {
                    "active" -> active = f.getOrElse(1) { "" }
                    "device" -> if (f.size >= 3 && f[1].isNotEmpty() && devices.none { it.address == f[1] }) {
                        devices += Device(f[1], f[2])
                    }
                    "profile" -> if (f.size >= 3 && f[1].isNotEmpty() && profiles.none { it.id == f[1] }) {
                        profiles += Profile(f[1], f[2], hotkeys(f.getOrNull(3)), receivers(f.getOrNull(4)))
                    }
                }
            }
            if (profiles.isEmpty()) profiles += Profile("p1", "")
            val known = devices.map { it.address }.toSet()
            val cleaned = profiles.map { p -> p.copy(receivers = p.receivers.filterValues { it in known }) }
            return Settings(devices, cleaned, cleaned.firstOrNull { it.id == active }?.id ?: cleaned.first().id)
        }

        private fun hotkeys(text: String?): List<Hotkey> {
            val keys = text.orEmpty().split(',').map { item ->
                val (m, c) = item.split(':').takeIf { it.size == 2 } ?: return Profile.DEFAULT_HOTKEYS
                Hotkey(m.toIntOrNull() ?: return Profile.DEFAULT_HOTKEYS, c.toIntOrNull() ?: return Profile.DEFAULT_HOTKEYS)
            }
            val ok = keys.size == Profile.SLOTS && keys.all { it.isValid } && keys.toSet().size == keys.size
            return if (ok) keys else Profile.DEFAULT_HOTKEYS
        }

        private fun receivers(text: String?): Map<Int, String> {
            val r = LinkedHashMap<Int, String>()
            for (item in text.orEmpty().split(',')) {
                val (s, a) = item.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                val slot = s.toIntOrNull()?.takeIf { it in 1 until Profile.SLOTS } ?: continue
                if (a.isNotEmpty() && a !in r.values) r[slot] = a
            }
            return r
        }

        private fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(40)
    }
}
