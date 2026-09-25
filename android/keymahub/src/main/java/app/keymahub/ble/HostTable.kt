package app.keymahub.ble

/**
 * Which hosts (PCs, tablets) are linked, which of them are targets, and which take input: the
 * connection logic of [BleHid], free of Android types so it can be tested.
 *
 * HOGP makes notification settings (CCCDs) part of the bond: a paired host enables input once,
 * and on later connections expects it still enabled without writing it again (Windows does just
 * that; Android hosts write it again anyway). So subscriptions are kept per host across
 * connections and app restarts, and a paired host that subscribed before is ready as soon as its
 * link is up. They are dropped only when the host unsubscribes or is unpaired.
 *
 * A host is
 * - linked: its link is up and the phone's GATT server has it;
 * - a target: linked and recognized as a HID host (a paired host that subscribed before, or one
 *   that uses the HID service: any paired host, and new ones while pairing mode is on);
 * - ready: a target with keyboard input enabled.
 *
 * Thread-safe.
 */
class HostTable(
    saved: Map<String, Set<Report>> = emptyMap(),
    /** Called with every change of the saved subscriptions (under the table's lock). */
    private val persist: (Map<String, Set<Report>>) -> Unit = {},
) {
    enum class Report { KEYBOARD, MOUSE, CONSUMER, BATTERY }

    /** How a host's readiness changed. */
    enum class Change { NONE, READY, GONE }

    private val subs = HashMap<String, MutableSet<Report>>().apply {
        for ((address, set) in saved) if (set.isNotEmpty()) put(address, set.toMutableSet())
    }
    private val linked = LinkedHashSet<String>()
    private val targets = HashSet<String>()
    private val ready = LinkedHashSet<String>()

    /**
     * A link came up. A paired host that subscribed before is a target at once and, with its
     * saved subscriptions, ready ([Change.READY]).
     */
    @Synchronized
    fun linkUp(address: String, bonded: Boolean): Change {
        linked.add(address)
        if (bonded && Report.KEYBOARD in subs[address].orEmpty()) targets.add(address)
        return update(address)
    }

    /** A link went down. Its subscriptions stay saved for the next connection. */
    @Synchronized
    fun linkDown(address: String): Change {
        linked.remove(address)
        targets.remove(address)
        return update(address)
    }

    /**
     * The host uses the HID service. Returns whether it is a target: a paired host, or a new one
     * while [pairing] is on. Requests arrive over a link, so this also counts it linked (the link
     * may predate the GATT server, which then never reported it).
     */
    @Synchronized
    fun useHid(address: String, bonded: Boolean, pairing: Boolean): Boolean {
        if (address in targets) return true
        if (!bonded && !pairing) return false
        linked.add(address)
        targets.add(address)
        return true
    }

    /** A target enabled or disabled notifications of [report]; others are not recorded. */
    @Synchronized
    fun subscribe(address: String, report: Report, on: Boolean): Change {
        if (address !in targets) return Change.NONE
        val set = subs.getOrPut(address) { HashSet() }
        val changed = if (on) set.add(report) else set.remove(report)
        if (set.isEmpty()) subs.remove(address)
        if (changed) persist(saved())
        return update(address)
    }

    /** What a CCCD read answers. */
    @Synchronized
    fun isSubscribed(address: String, report: Report) = report in subs[address].orEmpty()

    /** Whether [address] enabled keyboard input at some point (and has not disabled it since). */
    @Synchronized
    fun knows(address: String) = Report.KEYBOARD in subs[address].orEmpty()

    @Synchronized
    fun isLinked(address: String) = address in linked

    @Synchronized
    fun isTarget(address: String) = address in targets

    /** The host was unpaired: forget its subscriptions. */
    @Synchronized
    fun forget(address: String): Change {
        targets.remove(address)
        if (subs.remove(address) != null) persist(saved())
        return update(address)
    }

    /** Forgets hosts that are no longer paired. */
    @Synchronized
    fun retainPaired(paired: Set<String>) {
        if (subs.keys.retainAll(paired)) persist(saved())
    }

    /** Every link was let go (hosting stopped, Bluetooth off). Returns the hosts that were ready. */
    @Synchronized
    fun dropLinks(): List<String> {
        val gone = ready.toList()
        linked.clear()
        targets.clear()
        ready.clear()
        return gone
    }

    /** Ready hosts, in the order they became ready. */
    @Synchronized
    fun ready(): Set<String> = LinkedHashSet(ready)

    @Synchronized
    fun linked(): Set<String> = LinkedHashSet(linked)

    private fun saved(): Map<String, Set<Report>> = subs.mapValues { it.value.toSet() }

    private fun update(address: String): Change {
        val now = address in linked && address in targets && Report.KEYBOARD in subs[address].orEmpty()
        return when {
            now && ready.add(address) -> Change.READY
            !now && ready.remove(address) -> Change.GONE
            else -> Change.NONE
        }
    }

    companion object {
        /** One host per line: `address=KEYBOARD,MOUSE`. */
        fun encode(saved: Map<String, Set<Report>>): String =
            saved.entries.sortedBy { it.key }.joinToString("\n") { (a, s) -> "$a=${s.sorted().joinToString(",")}" }

        fun decode(text: String?): Map<String, Set<Report>> {
            if (text.isNullOrBlank()) return emptyMap()
            val out = HashMap<String, Set<Report>>()
            for (line in text.lines()) {
                val address = line.substringBefore('=', "").trim()
                if (address.isEmpty()) continue
                val set = line.substringAfter('=').split(',')
                    .mapNotNull { name -> Report.entries.firstOrNull { it.name == name.trim() } }
                    .toSet()
                if (set.isNotEmpty()) out[address] = set
            }
            return out
        }
    }
}
