package com.kemahub.core

/** A paired target device and the hotkey slot (1..9, 0 = none free) it answers to. */
data class Target(val address: String, val slot: Int, val name: String)

/**
 * Which target sits in which slot. New targets take the lowest free slot; changing a slot
 * swaps with whoever had it, so a slot never holds two targets.
 */
class SlotTable(entries: List<Target> = emptyList()) {
    private val byAddress = LinkedHashMap<String, Target>().apply { entries.forEach { put(it.address, it) } }

    val all: List<Target> get() = byAddress.values.sortedWith(compareBy({ if (it.slot == 0) 10 else it.slot }, { it.name }))

    fun get(address: String) = byAddress[address]

    fun addressOf(slot: Int): String? = if (slot == 0) null else byAddress.values.firstOrNull { it.slot == slot }?.address

    /** Returns the known target, or registers it in the lowest free slot. */
    fun ensure(address: String, name: String): Target {
        byAddress[address]?.let { return it }
        val used = byAddress.values.map { it.slot }.toSet()
        val slot = (1..9).firstOrNull { it !in used } ?: 0
        return Target(address, slot, clean(name)).also { byAddress[address] = it }
    }

    fun rename(address: String, name: String) {
        byAddress[address]?.let { byAddress[address] = it.copy(name = clean(name).ifEmpty { it.name }) }
    }

    fun setSlot(address: String, slot: Int) {
        require(slot in 0..9)
        val me = byAddress[address] ?: return
        if (slot != 0) {
            byAddress.values.firstOrNull { it.slot == slot && it.address != address }?.let {
                byAddress[it.address] = it.copy(slot = me.slot)
            }
        }
        byAddress[address] = me.copy(slot = slot)
    }

    fun remove(address: String) {
        byAddress.remove(address)
    }

    fun encode(): String = byAddress.values.joinToString("\n") { "${it.address}\t${it.slot}\t${it.name}" }

    companion object {
        fun decode(text: String?): SlotTable = SlotTable(
            text.orEmpty().lines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val slot = parts[1].toIntOrNull()?.takeIf { it in 0..9 } ?: return@mapNotNull null
                Target(parts[0], slot, parts[2])
            },
        )

        private fun clean(name: String) = name.replace('\t', ' ').replace('\n', ' ').trim().take(40)
    }
}
