package com.vincenthzr.locationspoofer.utils

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FrameworkConfigPublisherTest {
    private class Store : FrameworkConfigStore {
        var snapshot: String? = null
        var accept = true
        var failWrite = false
        val files = linkedMapOf<String, ByteArray>()
        val events = mutableListOf<String>()
        override fun commit(snapshot: String): Boolean {
            events += "commit"
            if (accept) this.snapshot = snapshot
            return accept
        }
        override fun writeFile(name: String, payload: ByteArray) {
            events += "file"
            if (failWrite) error("File write failed")
            files[name] = payload
        }
        override fun listFiles(): List<String> = files.keys.toList()
        override fun deleteFile(name: String) { files.remove(name) }
    }

    @Test fun inlineConfigAndStopArePublishedAsCompleteSnapshots() {
        val store = Store()
        val publisher = FrameworkConfigPublisher()
        publisher.publish("{\"active\":true,\"lat\":30}", store)
        val first = JSONObject(store.snapshot!!)
        assertEquals(FrameworkConfigChannel.VERSION, first.getInt("version"))
        assertTrue(JSONObject(first.getString("payload")).getBoolean("active"))
        publisher.publish("{\"active\":false,\"lat\":30}", store)
        val stopped = JSONObject(store.snapshot!!)
        assertFalse(JSONObject(stopped.getString("payload")).getBoolean("active"))
        assertNotEquals(first.getString("id"), stopped.getString("id"))
        assertTrue(store.files.isEmpty())
    }

    @Test fun utf8PayloadUsesFileBeforePublishingItsPointer() {
        val payload = JSONObject().put("name", "中".repeat(50_000)).toString()
        assertTrue(payload.length < FrameworkConfigChannel.INLINE_LIMIT_BYTES)
        val store = Store()
        FrameworkConfigPublisher().publish(payload, store)
        val envelope = JSONObject(store.snapshot!!)
        val file = envelope.getString("file")
        assertFalse(envelope.has("payload"))
        assertEquals(payload, store.files.getValue(file).toString(Charsets.UTF_8))
        assertEquals(listOf("file", "commit"), store.events)
    }

    @Test fun failedPublicationPreservesPreviousSnapshotAndFile() {
        val store = Store()
        val publisher = FrameworkConfigPublisher()
        val payload = JSONObject().put("route", "x".repeat(140_000)).toString()
        publisher.publish(payload, store)
        val previous = store.snapshot
        val previousFiles = store.files.keys.toSet()
        store.accept = false
        assertThrows(IllegalStateException::class.java) { publisher.publish(payload, store) }
        assertEquals(previous, store.snapshot)
        assertTrue(store.files.keys.containsAll(previousFiles))
        store.accept = true
        publisher.publish("{\"active\":false}", store)
        assertTrue(store.files.isEmpty())
    }

    @Test fun incompleteFileIsNeverPublished() {
        val store = Store()
        store.snapshot = "previous"
        store.failWrite = true
        assertThrows(IllegalStateException::class.java) {
            FrameworkConfigPublisher().publish("x".repeat(140_000), store)
        }
        assertEquals("previous", store.snapshot)
        assertEquals(listOf("file"), store.events)
    }

    @Test fun cleanupKeepsUnrelatedFrameworkFiles() {
        val store = Store()
        store.files["user-export.json"] = byteArrayOf(1)
        val publisher = FrameworkConfigPublisher()
        publisher.publish("x".repeat(140_000), store)
        assertEquals(2, store.files.size)
        publisher.publish("{\"active\":false}", store)
        assertEquals(setOf("user-export.json"), store.files.keys)
    }
}
