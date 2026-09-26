package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

/** Per-scan synthetic timing, independent from Android so lifecycle edge cases are testable. */
internal class BleDeliveryState<T>(startedAt: Long) {
    private data class Seen<T>(var value: T, var lastSeen: Long)
    private val seen = linkedMapOf<String, Seen<T>>()
    private var lastBatchAt = startedAt

    data class Changes<T>(val found: List<T>, val lost: List<T>)
    fun track(results: Map<String, T>, now: Long, lostAfter: Long = 10000): Changes<T> {
        val found = mutableListOf<T>()
        for ((address, result) in results) {
            if (address !in seen) found += result
            seen[address] = Seen(result, now)
        }
        val lost = mutableListOf<T>()
        val iterator = seen.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in results && now - entry.value.lastSeen >= lostAfter) {
                lost += entry.value.value
                iterator.remove()
            }
        }
        return Changes(found, lost)
    }
    fun batchDue(now: Long, delay: Long, flush: Boolean = false): Boolean {
        if (!flush && now - lastBatchAt < delay.coerceAtLeast(1000)) return false
        lastBatchAt = now
        return true
    }
}
