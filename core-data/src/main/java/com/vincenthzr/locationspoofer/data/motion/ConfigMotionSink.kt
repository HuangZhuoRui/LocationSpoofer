package com.vincenthzr.locationspoofer.data.motion

import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.repository.LocationRepository
import com.vincenthzr.locationspoofer.utils.ConfigManager
import org.json.JSONArray
import org.json.JSONObject

/** [MotionController] 的生产实现：只改配置里的运动字段，路线保存到"已保存路线" */
class ConfigMotionSink(
    private val locationRepository: LocationRepository,
    private val configManager: ConfigManager
) : MotionController.MotionSink {

    /** 非路线模式，Xposed 端在两次写入之间按方向和速度推算位置（见 RouteEngine 的 JOYSTICK 分支） */
    override suspend fun writeManual(lat: Double, lng: Double, bearing: Float, speedMs: Double, timestamp: Long) {
        locationRepository.patchConfig { json ->
            configManager.putPosition(json, lat, lng)
            json.put("is_route_mode", false)
            json.put("sim_mode", "JOYSTICK")
            json.put("sim_bearing", bearing.toDouble())
            json.put("speed_m_s", speedMs)
            json.put("start_timestamp", timestamp)
        }
    }

    override suspend fun writeRoute(
        lat: Double,
        lng: Double,
        bearing: Float,
        preset: MotionController.SpeedPreset,
        points: List<RoutePoint>,
        startTimestamp: Long,
        distanceOffset: Double,
        stopAtDestination: Boolean
    ) {
        val routeArray = JSONArray().apply { points.forEach { put(JSONObject().put("lat", it.lat).put("lng", it.lng)) } }
        locationRepository.patchConfig { json ->
            configManager.putPosition(json, lat, lng)
            json.put("is_route_mode", true)
            json.put("route_points", routeArray)
            json.put("sim_mode", preset.name)
            json.put("sim_bearing", bearing.toDouble())
            json.put("speed_m_s", preset.speedMs)
            json.put("start_timestamp", startTimestamp)
            json.put("route_distance_offset", distanceOffset)
            json.put("stop_at_destination", stopAtDestination)
        }
    }

    override suspend fun saveRoute(name: String, points: List<RoutePoint>) {
        locationRepository.insertSavedRoute(name, points)
    }
}
