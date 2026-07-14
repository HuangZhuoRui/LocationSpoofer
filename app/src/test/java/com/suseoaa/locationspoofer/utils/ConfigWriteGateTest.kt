package com.suseoaa.locationspoofer.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigWriteGateTest {
    @Test
    fun stopGenerationBecomesFinalWriterAfterBlockedOldWrite() = runBlocking {
        val gate = ConfigWriteGate()
        val oldGeneration = gate.currentGeneration()
        val oldWriteStarted = CompletableDeferred<Unit>()
        val releaseOldWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()

        val oldWrite = async {
            gate.runIfCurrent(oldGeneration) {
                oldWriteStarted.complete(Unit)
                releaseOldWrite.await()
                writes += "active-old"
            }
        }
        oldWriteStarted.await()

        val stopGeneration = gate.beginNewGeneration()
        val stopWrite = async {
            gate.runIfCurrent(stopGeneration) {
                writes += "inactive-stop"
            }
        }

        releaseOldWrite.complete(Unit)

        assertFalse(oldWrite.await())
        assertTrue(stopWrite.await())
        assertEquals(listOf("active-old", "inactive-stop"), writes)
    }

    @Test
    fun queuedWriteFromObsoleteGenerationIsSkipped() = runBlocking {
        val gate = ConfigWriteGate()
        val obsoleteGeneration = gate.currentGeneration()
        val currentGeneration = gate.beginNewGeneration()
        val writes = mutableListOf<String>()

        val obsoleteResult = gate.runIfCurrent(obsoleteGeneration) {
            writes += "obsolete"
        }
        val currentResult = gate.runIfCurrent(currentGeneration) {
            writes += "current"
        }

        assertFalse(obsoleteResult)
        assertTrue(currentResult)
        assertEquals(listOf("current"), writes)
    }
}
