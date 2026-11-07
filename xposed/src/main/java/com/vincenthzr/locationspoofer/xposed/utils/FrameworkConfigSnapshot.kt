package com.vincenthzr.locationspoofer.xposed.utils

import com.vincenthzr.locationspoofer.utils.FrameworkConfigChannel
import org.json.JSONObject

internal data class FrameworkConfigSnapshot(val config: JSONObject, val source: String, val publishedAt: Long) {
    companion object {
        fun decode(text: String, readFile: (String) -> String): FrameworkConfigSnapshot {
            val envelope = JSONObject(text)
            require(envelope.getInt("version") == FrameworkConfigChannel.VERSION) { "Unsupported config version" }
            require(envelope.getString("id").isNotBlank()) { "Missing snapshot identity" }
            val file = envelope.optString("file")
            val payload = if (file.isEmpty()) envelope.getString("payload") else {
                require(FrameworkConfigChannel.isConfigFile(file)) { "Invalid remote config filename" }
                readFile(file)
            }
            return FrameworkConfigSnapshot(
                JSONObject(payload),
                if (file.isEmpty()) "libxposed:remote-preferences" else "libxposed:remote-file",
                envelope.getLong("published_at")
            )
        }
    }
}
