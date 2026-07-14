package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.SavedLocation
import org.json.JSONArray
import org.json.JSONObject

object SavedLocationJsonCodec {
    fun decode(json: String): List<SavedLocation> {
        val result = mutableListOf<SavedLocation>()
        val array = JSONArray(json)
        for (index in 0 until array.length()) {
            val obj = array.getJSONObject(index)
            result += SavedLocation(
                name = obj.getString("name"),
                lat = obj.getDouble("lat"),
                lng = obj.getDouble("lng"),
                wifiJson = obj.optString("wifiJson", "[]"),
                cellJson = obj.optString("cellJson", "[]"),
                bluetoothJson = obj.optString("bluetoothJson", "[]")
            )
        }
        return result
    }

    fun encode(locations: List<SavedLocation>): String {
        val array = JSONArray()
        locations.forEach { location ->
            array.put(JSONObject().apply {
                put("name", location.name)
                put("lat", location.lat)
                put("lng", location.lng)
                put("wifiJson", location.wifiJson)
                put("cellJson", location.cellJson)
                put("bluetoothJson", location.bluetoothJson)
            })
        }
        return array.toString()
    }
}
