package com.suseoaa.locationspoofer.utils

import org.json.JSONArray
import org.json.JSONObject

data class WifiPayloadInfo(
    val hasData: Boolean,
    val nearbyCount: Int
)

data class EnvironmentJsonPayload(
    val wifiJson: String,
    val cellJson: String,
    val bluetoothJson: String
)

object EnvironmentPayloads {
    fun usableWifiOverride(json: String?): String? =
        json?.takeIf { inspectWifi(it).hasData }

    fun usableArrayOverride(json: String?): String? =
        json?.takeIf { hasArrayItems(it) }

    fun merge(
        localWifiJson: String,
        localCellJson: String,
        localBluetoothJson: String,
        wifiJsonOverride: String?,
        cellJsonOverride: String?,
        bluetoothJsonOverride: String?
    ): EnvironmentJsonPayload = EnvironmentJsonPayload(
        wifiJson = wifiJsonOverride ?: localWifiJson,
        cellJson = cellJsonOverride ?: localCellJson,
        bluetoothJson = bluetoothJsonOverride ?: localBluetoothJson
    )

    fun inspectWifi(json: String): WifiPayloadInfo {
        return try {
            val obj = JSONObject(json)
            val nearbyCount = obj.optJSONArray("nearbyWifi")?.length() ?: 0
            val hasConnectedWifi = obj.optJSONObject("connectedWifi") != null
            WifiPayloadInfo(hasConnectedWifi || nearbyCount > 0, nearbyCount)
        } catch (_: Exception) {
            WifiPayloadInfo(hasData = false, nearbyCount = 0)
        }
    }

    fun hasArrayItems(json: String): Boolean {
        return try {
            JSONArray(json).length() > 0
        } catch (_: Exception) {
            false
        }
    }
}
