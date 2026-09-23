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
data class Hotkey(val mods: Int, val code: Int)

/**
 * The hotkeys: one modifier combination for the whole app, plus a fixed number key per slot:
 * 1 = this phone, 2..9 and 0 = receivers 1..9.
 */
object Hotkeys {
    const val DEFAULT_MODS = Mods.SHIFT or Mods.ALT

    /** evdev KEY_1..KEY_9 = 2..10, KEY_0 = 11: slot i uses code 2 + i. */
    fun code(slot: Int) = 2 + slot

    fun slotOf(code: Int): Int? = (code - 2).takeIf { it in 0 until Profile.SLOTS }

    /** Needs Ctrl, Alt or Meta: Shift alone (or nothing) would steal typing. */
    fun validMods(mods: Int) = mods and (Mods.CTRL or Mods.ALT or Mods.META) != 0 && mods and 0xF.inv() == 0
}

/**
 * A Bluetooth device that has connected to this phone at least once. [name] is the alias the
 * user sees; [lastUsed] is epoch millis (0 = never); a [blocked] device was disconnected by
 * the user and may not reconnect until allowed again.
 */
data class Device(val address: String, val name: String, val lastUsed: Long = 0, val blocked: Boolean = false)

/**
 * A hosting setup: which receiver sits on which hotkey slot, and when the profile applies.
 *
 * Slot 0 is this phone, slots 1..9 are receivers (see [Hotkeys]).
 * An empty [name] is the unnamed default profile (the UI shows a translated name).
 * Conditions: [time] and/or [whenConnected] (any of these devices connected); with both, both
 * must hold. A profile without conditions is only used when activated by hand.
 */
data class Profile(
    val id: String,
    val name: String,
    val receivers: Map<Int, String> = emptyMap(),
    val time: TimeRule? = null,
    val whenConnected: Set<String> = emptySet(),
) {
    fun addressOf(slot: Int): String? = receivers[slot]

    fun slotOf(address: String): Int? = receivers.entries.firstOrNull { it.value == address }?.key

    val hasConditions get() = time != null || whenConnected.isNotEmpty()

    /** [day] ISO 1..7, [minute] of the day, [connected] the devices connected right now. */
    fun matches(day: Int, minute: Int, connected: Set<String>): Boolean =
        (time?.matches(day, minute) ?: true) && (whenConnected.isEmpty() || whenConnected.any { it in connected })

    companion object {
        const val SLOTS = 10
        const val PHONE = 0
    }
}

/** How the active profile is picked. */
enum class ActivationMode {
    /** The profile with the most of its receivers connected (ties: higher in the list). */
    AUTO,

    /** The first profile, in list order, whose conditions (time, connected devices) hold. */
    RULES,

    /** Only the profile the user activates. */
    MANUAL,
}

/**
 * Everything the user configures. Immutable: every change returns a new value.
 *
 * Which profile is active:
 * - In [ActivationMode.AUTO] and [ActivationMode.RULES], a profile may match right now
 *   ([matchedId]); it is used.
 * - Otherwise (and always in [ActivationMode.MANUAL]) the profile the user activated last
 *   ([chosenId]) is used.
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
    /** Modifiers held with the number keys ([Hotkeys]); the same in every profile. */
    val mods: Int = Hotkeys.DEFAULT_MODS,
    val mode: ActivationMode = ActivationMode.AUTO,
) {
    fun hotkey(slot: Int) = Hotkey(mods, Hotkeys.code(slot))

    /** The slot [h] selects, or null if it is not a hotkey. */
    fun slotFor(h: Hotkey): Int? = if (h.mods == mods) Hotkeys.slotOf(h.code) else null

    fun setMode(m: ActivationMode) = copy(mode = m, overriddenId = null)

    /** Ignored unless valid ([Hotkeys.validMods]). */
    fun setMods(m: Int) = if (Hotkeys.validMods(m)) copy(mods = m) else this

    val active: Profile get() = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    fun device(address: String) = devices.firstOrNull { it.address == address }

    fun profile(id: String) = profiles.firstOrNull { it.id == id }

    /** Picks the active profile for this moment. */
    fun resolve(day: Int, minute: Int, connected: Set<String>): Settings {
        val matched = when (mode) {
            // maxByOrNull keeps the first of equal counts: the higher profile wins ties.
            ActivationMode.AUTO -> profiles
                .map { p -> p to p.receivers.values.count { it in connected } }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }?.first?.id
            ActivationMode.RULES -> profiles.firstOrNull { it.hasConditions && it.matches(day, minute, connected) }?.id
            ActivationMode.MANUAL -> null
        }
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
        profiles = profiles.map { p ->
            p.copy(receivers = p.receivers.filterValues { it != address }, whenConnected = p.whenConnected - address)
        },
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

    // ---------------------------------------------------------------- profiles

    /**
     * Adds a profile named [name] at the top (highest priority), with the active profile's
     * receivers but no conditions. Returns the settings and the new id.
     */
    fun addProfile(name: String): Pair<Settings, String> {
        val n = (profiles.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val p = active.copy(id = "p$n", name = clean(name), time = null, whenConnected = emptySet())
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

    /** The profile applies while any of [addresses] is connected (empty: no such condition). */
    fun setWhenConnected(id: String, addresses: Set<String>): Settings {
        val p = profile(id) ?: return this
        return withProfile(p.copy(whenConnected = addresses.filter { device(it) != null }.toSet()))
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
        append("mods\t").append(mods).append('\n')
        append("mode\t").append(mode.name).append('\n')
        for (d in devices) append("device\t${d.address}\t${d.name}\t${d.lastUsed}\t${if (d.blocked) 1 else 0}\n")
        for (p in profiles) {
            val recv = p.receivers.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            val time = p.time?.let { "${it.days},${it.start},${it.end}" }.orEmpty()
            val connected = p.whenConnected.joinToString(",")
            // The empty 4th field held per-profile hotkeys in early builds.
            // Empty 4th and 7th fields: per-profile hotkeys and places of early builds.
            append("profile\t${p.id}\t${p.name}\t\t$recv\t$time\t\t$connected\n")
        }
    }

    companion object {
        /** Tolerates junk: bad lines are skipped, and missing parts fall back to defaults. */
        fun decode(text: String?): Settings {
            var active = ""
            var chosen = ""
            var matched: String? = null
            var overridden: String? = null
            var mods = Hotkeys.DEFAULT_MODS
            var mode = ActivationMode.AUTO
            val devices = mutableListOf<Device>()
            val profiles = mutableListOf<Profile>()
            for (line in text.orEmpty().lines()) {
                val f = line.split('\t')
                when (f[0]) {
                    "active" -> active = f.getOrElse(1) { "" }
                    "chosen" -> chosen = f.getOrElse(1) { "" }
                    "matched" -> matched = f.getOrNull(1)
                    "overridden" -> overridden = f.getOrNull(1)
                    "mode" -> mode = ActivationMode.entries.firstOrNull { it.name == f.getOrNull(1) } ?: mode
                    "mods" -> mods = f.getOrNull(1)?.toIntOrNull()?.takeIf(Hotkeys::validMods) ?: mods
                    "device" -> if (f.size >= 3 && f[1].isNotEmpty() && devices.none { it.address == f[1] }) {
                        devices += Device(f[1], f[2], f.getOrNull(3)?.toLongOrNull() ?: 0, f.getOrNull(4) == "1")
                    }
                    "profile" -> if (f.size >= 3 && f[1].isNotEmpty() && profiles.none { it.id == f[1] }) {
                        profiles += Profile(
                            f[1], f[2], receivers = receivers(f.getOrNull(4)),
                            time = time(f.getOrNull(5)),
                            whenConnected = f.getOrNull(7).orEmpty().split(',').filter { it.isNotEmpty() }.toSet(),
                        )
                    }
                }
            }
            if (profiles.isEmpty()) profiles += Profile("p1", "")
            val known = devices.map { it.address }.toSet()
            val cleaned = profiles.map { p ->
                p.copy(receivers = p.receivers.filterValues { it in known }, whenConnected = p.whenConnected.filter { it in known }.toSet())
            }
            fun valid(id: String?) = id?.takeIf { i -> cleaned.any { it.id == i } }
            val activeId = valid(active) ?: cleaned.first().id
            return Settings(devices, cleaned, activeId, valid(chosen) ?: activeId, valid(matched), valid(overridden), mods, mode)
        }

        private fun time(text: String?): TimeRule? {
            val v = text.orEmpty().split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } ?: return null
            return TimeRule(v[0], v[1], v[2]).takeIf { it.isValid }
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
