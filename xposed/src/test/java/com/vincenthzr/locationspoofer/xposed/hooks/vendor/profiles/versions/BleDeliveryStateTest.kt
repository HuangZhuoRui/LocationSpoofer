package com.vincenthzr.locationspoofer.xposed.hooks.vendor.profiles.versions

import org.junit.Assert.*
import org.junit.Test

class BleDeliveryStateTest {
    @Test fun `first match is emitted once per device per scan`() {
        val state = BleDeliveryState<String>(0)
        assertEquals(listOf("a"), state.track(mapOf("AA" to "a"), 0).found)
        assertTrue(state.track(mapOf("AA" to "updated"), 1000).found.isEmpty())
        assertEquals(listOf("b"), state.track(mapOf("AA" to "updated", "BB" to "b"), 2000).found)
    }
    @Test fun `match lost waits for timeout and uses the last result`() {
        val state = BleDeliveryState<String>(0)
        state.track(mapOf("AA" to "a"), 0)
        state.track(mapOf("AA" to "updated"), 1000)
        assertTrue(state.track(emptyMap(), 10999).lost.isEmpty())
        assertEquals(listOf("updated"), state.track(emptyMap(), 11000).lost)
        assertTrue(state.track(emptyMap(), 12000).lost.isEmpty())
        assertEquals(listOf("returned"), state.track(mapOf("AA" to "returned"), 13000).found)
    }
    @Test fun `batch delivery honors delay and explicit flush`() {
        val state = BleDeliveryState<String>(1000)
        assertFalse(state.batchDue(2000, 5000))
        assertTrue(state.batchDue(6000, 5000))
        assertFalse(state.batchDue(7000, 5000))
        assertTrue(state.batchDue(7000, 5000, flush = true))
        assertFalse(state.batchDue(11000, 5000))
        assertTrue(state.batchDue(12000, 5000))
    }
    @Test fun `a new scanner session does not inherit first match or batch timing`() {
        val old = BleDeliveryState<String>(0)
        old.track(mapOf("AA" to "a"), 0)
        old.batchDue(10000, 5000)
        val replacement = BleDeliveryState<String>(10000)
        assertEquals(listOf("a"), replacement.track(mapOf("AA" to "a"), 10000).found)
        assertFalse(replacement.batchDue(10001, 5000))
    }
}
