package com.vincenthzr.locationspoofer.utils

import org.json.JSONObject
import java.util.UUID

/** The framework owns storage; the app publishes one atomic snapshot pointer. */
internal interface FrameworkConfigStore {
    fun commit(snapshot: String): Boolean
    fun writeFile(name: String, payload: ByteArray)
    fun listFiles(): List<String>
    fun deleteFile(name: String)
}

internal class FrameworkConfigPublisher {
    private var cleanupNeeded = true

    fun publish(payload: String, store: FrameworkConfigStore): Long {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val publishedAt = System.currentTimeMillis()
        val file = if (bytes.size > FrameworkConfigChannel.INLINE_LIMIT_BYTES) {
            "${FrameworkConfigChannel.FILE_PREFIX}${UUID.randomUUID()}.json"
        } else null
        var commitAttempted = false
        try {
            // Finish and close the immutable file before exposing its name to readers.
            if (file != null) store.writeFile(file, bytes)
            val snapshot = JSONObject().apply {
                put("version", FrameworkConfigChannel.VERSION)
                put("id", UUID.randomUUID().toString())
                put("published_at", publishedAt)
                if (file == null) put("payload", payload) else put("file", file)
            }
            commitAttempted = true
            check(store.commit(snapshot.toString())) { "Framework rejected config publication" }
        } catch (error: Exception) {
            // A lost Binder response does not prove the commit was rejected. Keep the file
            // until a later successful publication, so either possible pointer remains readable.
            if (file != null && !commitAttempted) runCatching { store.deleteFile(file) }
            cleanupNeeded = true
            throw error
        }
        // A delayed reader can retry the current pointer if an older file was removed.
        if (cleanupNeeded || file != null) runCatching {
            store.listFiles().filter { FrameworkConfigChannel.isConfigFile(it) && it != file }
                .forEach { store.deleteFile(it) }
            cleanupNeeded = file != null
        }
        return publishedAt
    }

    fun onReconnect() { cleanupNeeded = true }
}
