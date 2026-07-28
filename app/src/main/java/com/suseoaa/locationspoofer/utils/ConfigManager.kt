package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val rootManager: RootManager) {

    suspend fun saveConfig(
        lat: Double,
        lng: Double,
        active: Boolean,
        simMode: String = "STILL",
        simBearing: Float = 0f,
        startTimestamp: Long = System.currentTimeMillis(),
        routePoints: List<RoutePoint> = emptyList(),
        isRouteMode: Boolean = false,
        wifiJson: String = "[]",
        appCoordinateSystems: Map<String, String> = emptyMap(),
        cellJson: String = "[]",
        bluetoothJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        altitude: Double = 0.0,
        satelliteCount: Int = 20
    ) = withContext(Dispatchers.IO) {
        val routeArray = JSONArray()
        routePoints.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lng", p.lng)
            routeArray.put(obj)
        }

        val json = JSONObject().apply {
            put("lat", lat)
            put("lng", lng)
            put("active", active)
            put("sim_mode", simMode)
            put("sim_bearing", simBearing.toDouble())
            put("start_timestamp", startTimestamp)
            put("route_points", routeArray)
            put("is_route_mode", isRouteMode)
            val wifiObj = try {
                JSONObject(wifiJson)
            } catch (e: Exception) {
                JSONObject().apply {
                    put("isConnected", false)
                    put("connectedWifi", JSONObject.NULL)
                    put("nearbyWifi", JSONArray())
                }
            }
            put("wifi_json", wifiObj)
            put("cell_json", JSONArray(cellJson))
            put("bluetooth_json", JSONArray(bluetoothJson))
            put("mock_wifi", mockWifi)
            put("mock_cell", mockCell)
            put("mock_bluetooth", mockBluetooth)
            put("enable_jitter", enableJitter)
            put("altitude", altitude)
            put("satellite_count", satelliteCount)

            val coordSysObj = JSONObject()
            appCoordinateSystems.forEach { (pkg, sys) -> coordSysObj.put(pkg, sys) }
            put("app_coordinate_systems", coordSysObj)
        }
        val cellCount = json.optJSONArray("cell_json")?.length() ?: 0

        // 使用 stdin 写入，避免命令行过长 (ARG_MAX) 导致 su 执行失败，实现实时更新
        val jsonText = json.toString()
        val command = """
            cat > /data/local/tmp/locationspoofer_config_tmp.json
            chmod 666 /data/local/tmp/locationspoofer_config_tmp.json
            chcon u:object_r:shell_data_file:s0 /data/local/tmp/locationspoofer_config_tmp.json 2>/dev/null || true
            cp /data/local/tmp/locationspoofer_config_tmp.json /data/system/locationspoofer_config_tmp.json
            chown system:system /data/system/locationspoofer_config_tmp.json 2>/dev/null || true
            chmod 644 /data/system/locationspoofer_config_tmp.json
            chcon u:object_r:system_data_file:s0 /data/system/locationspoofer_config_tmp.json 2>/dev/null || true
            mv /data/local/tmp/locationspoofer_config_tmp.json /data/local/tmp/locationspoofer_config.json
            mv /data/system/locationspoofer_config_tmp.json /data/system/locationspoofer_config.json
        """.trimIndent()

        val result = rootManager.executeCommandWithInput(command, jsonText)
    }
}
