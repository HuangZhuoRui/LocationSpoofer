package com.suseoaa.locationspoofer.utils

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes config writes and rejects work from an obsolete spoofing session. */
class ConfigWriteGate {
    private val mutex = Mutex()
    private val generation = AtomicLong(0)

    fun currentGeneration(): Long = generation.get()

    fun beginNewGeneration(): Long = generation.incrementAndGet()

    suspend fun runIfCurrent(
        expectedGeneration: Long,
        block: suspend () -> Unit
    ): Boolean = mutex.withLock {
        if (expectedGeneration != generation.get()) {
            false
        } else {
            block()
            expectedGeneration == generation.get()
        }
    }
}
