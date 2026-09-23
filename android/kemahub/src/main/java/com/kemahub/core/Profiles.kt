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

/**
 * A Bluetooth device that has connected to this phone at least once. [name] is the alias the
 * user sees; [lastUsed] is epoch millis (0 = never); a [blocked] device was disconnected by
 * the user and may not reconnect until allowed again.
 */
data class Device(val address: String, val name: String, val lastUsed: Long = 0, val blocked: Boolean = false)

/**
 * A hosting setup: which receiver sits on which hotkey, the hotkeys themselves, and when the
 * profile applies.
 *
 * Slot 0 is this phone, slots 1..9 are receivers. [hotkeys] has one entry per slot
 * (default Ctrl+Alt+1 = phone, Ctrl+Alt+2..9, 0 = receivers 1..9).
 * An empty [name] is the unnamed default profile (the UI shows a translated name).
 * Conditions: [time] and/or [place]; with both, both must hold; with neither, always.
 */
data class Profile(
    val id: String,
    val name: String,
    val hotkeys: List<Hotkey> = DEFAULT_HOTKEYS,
    val receivers: Map<Int, String> = emptyMap(),
    val time: TimeRule? = null,
    val place: PlaceRule? = null,
) {
    fun slotFor(hotkey: Hotkey): Int? = hotkeys.indexOf(hotkey).takeIf { it >= 0 }

    fun addressOf(slot: Int): String? = receivers[slot]

    fun slotOf(address: String): Int? = receivers.entries.firstOrNull { it.value == address }?.key

    val hasConditions get() = time != null || place != null

    /** [day] ISO 1..7, [minute] of the day, [location] null when unknown (a place then does not match). */
    fun matches(day: Int, minute: Int, location: GeoPoint?): Boolean =
        (time?.matches(day, minute) ?: true) && (place?.let { location != null && it.contains(location) } ?: true)

    companion object {
        const val SLOTS = 10
        const val PHONE = 0

        /** evdev KEY_1..KEY_9 = 2..10, KEY_0 = 11. */
        val DEFAULT_HOTKEYS: List<Hotkey> = (2..11).map { Hotkey(Mods.CTRL or Mods.ALT, it) }
    }
}

/**
 * Everything the user configures. Immutable: every change returns a new value.
 *
 * Which profile is active:
 * - [profiles] with conditions are checked in priority order (first = highest); the first
 *   whose conditions hold right now wins ([matchedId]).
 * - If none holds, the profile the user activated last ([chosenId]) is used. Profiles without
 *   conditions are only ever used this way.
 * - Activating a profile by hand also overrides the profile matching at that moment
 *   ([overriddenId]) until the matching changes (another profile matches, or none does).
 */
data class Settings(
    val devices: List<Device> = emptyList(),
    val profiles: List<Profile> = listOf(Profile("p1", "")),
    val activeId: String = profiles.first().id,
    val chosenId: String = profiles.first().id,
    val matchedId: String? = null,
    val overriddenId: String? = null,
) {
    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    fun device(address: String) = devices.firstOrNull { it.address == address }

    fun profile(id: String) = profiles.firstOrNull { it.id == id }

    /** Picks the active profile for this moment. */
    fun resolve(day: Int, minute: Int, location: GeoPoint?): Settings {
        val matched = profiles.firstOrNull { it.hasConditions && it.matches(day, minute, location) }?.id
        val overridden = overriddenId?.takeIf { it == matched }
        val chosen = profile(chosenId)?.id ?: profiles.first().id
        val active = if (matched != null && overridden == null) matched else chosen
        return copy(activeId = active, chosenId = chosen, matchedId = matched, overriddenId = overridden)
    }

    /** The user activated [id]: it is used now, and whenever no conditions match. */
    fun activate(id: String): Settings {
        if (profile(id) == null) return this
        return copy(chosenId = id, activeId = id, overriddenId = matchedId?.takeIf { it != id })
    }

    /** Ends a manual override: the matching profile takes over again (applied by the next [resolve]). */
    fun automatic() = copy(overriddenId = null)

    // ---------------------------------------------------------------- devices

    /** A device became ready: remember it, and give it the first free receiver slot of the active profile. */
    fun deviceConnected(address: String, name: String): Settings {
        val known = if (device(address) != null) this else copy(devices = devices + Device(address, clean(name)))
        val p = known.active
        if (p.slotOf(address) != null) return known
        val free = (1 until Profile.SLOTS).firstOrNull { it !in p.receivers } ?: return known
        return known.withProfile(p.copy(receivers = p.receivers + (free to address)))
    }

    fun touch(address: String, now: Long) = withDevice(address) { it.copy(lastUsed = now) }

    fun setBlocked(address: String, blocked: Boolean) = withDevice(address) { it.copy(blocked = blocked) }

    fun renameDevice(address: String, name: String): Settings {
        val n = clean(name).ifEmpty { return this }
        return withDevice(address) { it.copy(name = n) }
    }

    /** Forgets the device everywhere. */
    fun forgetDevice(address: String) = copy(
        devices = devices.filter { it.address != address },
        profiles = profiles.map { p -> p.copy(receivers = p.receivers.filterValues { it != address }) },
    )

    private fun withDevice(address: String, f: (Device) -> Device) =
        copy(devices = devices.map { if (it.address == address) f(it) else it })
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

    /**
     * Adds a profile named [name] at the top (highest priority), with the active profile's
     * receivers and hotkeys but no conditions. Returns the settings and the new id.
     */
    fun addProfile(name: String): Pair<Settings, String> {
        val n = (profiles.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val p = active.copy(id = "p$n", name = clean(name), time = null, place = null)
        return copy(profiles = listOf(p) + profiles) to p.id
    }

    fun renameProfile(id: String, name: String): Settings {
        val p = profile(id) ?: return this
        return withProfile(p.copy(name = clean(name)))
    }

    /** The last profile cannot be deleted. */
    fun deleteProfile(id: String): Settings {
        if (profiles.size <= 1 || profile(id) == null) return this
        val rest = profiles.filter { it.id != id }
        return copy(
            profiles = rest,
            activeId = if (activeId == id) rest.first().id else activeId,
            chosenId = if (chosenId == id) rest.first().id else chosenId,
            matchedId = matchedId?.takeIf { it != id },
            overriddenId = overriddenId?.takeIf { it != id },
        )
    }

    /** Moves profile [id] by [delta] places in the priority order (negative = higher). */
    fun moveProfile(id: String, delta: Int): Settings {
        val from = profiles.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return this
        val to = (from + delta).coerceIn(0, profiles.size - 1)
        if (to == from) return this
        val list = profiles.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(profiles = list)
    }

    fun setTime(id: String, rule: TimeRule?): Settings {
        val p = profile(id) ?: return this
        if (rule != null && !rule.isValid) return this
        return withProfile(p.copy(time = rule))
    }

    fun setPlace(id: String, rule: PlaceRule?): Settings {
        val p = profile(id) ?: return this
        if (rule != null && !rule.isValid) return this
        return withProfile(p.copy(place = rule?.copy(label = clean(rule.label))))
    }

    private fun withProfile(p: Profile) = copy(profiles = profiles.map { if (it.id == p.id) p else it })

    // ---------------------------------------------------------------- storage

    /** Line format, tab-separated; names never contain tabs or newlines. */
    fun encode(): String = buildString {
        append("kemahub-settings\t1\n")
        append("active\t").append(activeId).append('\n')
        append("chosen\t").append(chosenId).append('\n')
        matchedId?.let { append("matched\t").append(it).append('\n') }
        overriddenId?.let { append("overridden\t").append(it).append('\n') }
        for (d in devices) append("device\t${d.address}\t${d.name}\t${d.lastUsed}\t${if (d.blocked) 1 else 0}\n")
        for (p in profiles) {
            val keys = p.hotkeys.joinToString(",") { "${it.mods}:${it.code}" }
            val recv = p.receivers.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            val time = p.time?.let { "${it.days},${it.start},${it.end}" }.orEmpty()
            val place = p.place?.let { "${it.lat},${it.lon},${it.radius},${it.label}" }.orEmpty()
            append("profile\t${p.id}\t${p.name}\t$keys\t$recv\t$time\t$place\n")
        }
    }

    companion object {
        /** Tolerates junk: bad lines are skipped, and missing parts fall back to defaults. */
        fun decode(text: String?): Settings {
            var active = ""
            var chosen = ""
            var matched: String? = null
            var overridden: String? = null
            val devices = mutableListOf<Device>()
            val profiles = mutableListOf<Profile>()
            for (line in text.orEmpty().lines()) {
                val f = line.split('\t')
                when (f[0]) {
                    "active" -> active = f.getOrElse(1) { "" }
                    "chosen" -> chosen = f.getOrElse(1) { "" }
                    "matched" -> matched = f.getOrNull(1)
                    "overridden" -> overridden = f.getOrNull(1)
                    "device" -> if (f.size >= 3 && f[1].isNotEmpty() && devices.none { it.address == f[1] }) {
                        devices += Device(f[1], f[2], f.getOrNull(3)?.toLongOrNull() ?: 0, f.getOrNull(4) == "1")
                    }
                    "profile" -> if (f.size >= 3 && f[1].isNotEmpty() && profiles.none { it.id == f[1] }) {
                        profiles += Profile(
                            f[1], f[2], hotkeys(f.getOrNull(3)), receivers(f.getOrNull(4)),
                            time(f.getOrNull(5)), place(f.getOrNull(6)),
                        )
                    }
                }
            }
            if (profiles.isEmpty()) profiles += Profile("p1", "")
            val known = devices.map { it.address }.toSet()
            val cleaned = profiles.map { p -> p.copy(receivers = p.receivers.filterValues { it in known }) }
            fun valid(id: String?) = id?.takeIf { i -> cleaned.any { it.id == i } }
            val activeId = valid(active) ?: cleaned.first().id
            return Settings(devices, cleaned, activeId, valid(chosen) ?: activeId, valid(matched), valid(overridden))
        }

        private fun time(text: String?): TimeRule? {
            val v = text.orEmpty().split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } ?: return null
            return TimeRule(v[0], v[1], v[2]).takeIf { it.isValid }
        }

        private fun place(text: String?): PlaceRule? {
            val f = text.orEmpty().split(',', limit = 4).takeIf { it.size == 4 } ?: return null
            val rule = PlaceRule(
                f[0].toDoubleOrNull() ?: return null, f[1].toDoubleOrNull() ?: return null,
                f[2].toIntOrNull() ?: return null, f[3],
            )
            return rule.takeIf { it.isValid }
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
