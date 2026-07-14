package com.suseoaa.locationspoofer.data.repository

import android.content.Context
import android.content.Intent
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.service.SpoofingService
import com.suseoaa.locationspoofer.utils.ConfigManager
import com.suseoaa.locationspoofer.utils.LSPosedManager
import com.suseoaa.locationspoofer.utils.RootManager
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val settingsManager: com.suseoaa.locationspoofer.utils.SettingsManager,
    private val savedRouteDao: com.suseoaa.locationspoofer.data.db.SavedRouteDao
) {
    suspend fun checkRootAccess(): Boolean = rootManager.checkRootAccess()

    fun isModuleActive(): Boolean = lsposedManager.isModuleActive()

    suspend fun startSpoofing(
        context: Context,
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        appCoordinateSystems: Map<String, String>,
        wifiJson: String = "[]",
        cellJson: String = "[]",
        bluetoothJson: String = "[]",
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true
    ): Boolean {
        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        val configWritten = configManager.saveConfig(
            lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode,
            wifiJson, appCoordinateSystems, cellJson, bluetoothJson,
            mockWifi, mockCell, mockBluetooth, enableJitter, alt, satCount
        )
        if (!configWritten) return false

        SpooferProvider.isActive = true
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.wifiJson = wifiJson
        SpooferProvider.cellJson = cellJson
        SpooferProvider.bluetoothJson = bluetoothJson
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode
        SpooferProvider.enableJitter = enableJitter
        rootManager.grantMockLocation()

        context.startForegroundService(
            Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_START
                putExtra(SpoofingService.EXTRA_LAT, lat)
                putExtra(SpoofingService.EXTRA_LNG, lng)
            }
        )
        return true
    }

    suspend fun stopSpoofing(context: Context): Boolean {
        val configWritten = configManager.saveConfig(0.0, 0.0, false)
        SpooferProvider.isActive = false
        SpooferProvider.wifiJson = "[]"
        SpooferProvider.cellJson = "[]"
        SpooferProvider.bluetoothJson = "[]"
        SpooferProvider.routeJson = "[]"
        SpooferProvider.isRouteMode = false
        context.startService(Intent(context, SpoofingService::class.java).apply {
            action = SpoofingService.ACTION_STOP
        })
        rootManager.revokeMockLocation()
        return configWritten
    }

    suspend fun updateConfig(
        lat: Double,
        lng: Double,
        simMode: String,
        simBearing: Float,
        startTime: Long,
        routePoints: List<RoutePoint>,
        isRouteMode: Boolean,
        appCoordinateSystems: Map<String, String>,
        wifiJson: String = SpooferProvider.wifiJson,
        cellJson: String = SpooferProvider.cellJson,
        bluetoothJson: String = SpooferProvider.bluetoothJson,
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true
    ): Boolean {
        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        val configWritten = configManager.saveConfig(
            lat, lng, true, simMode, simBearing, startTime, routePoints, isRouteMode,
            wifiJson, appCoordinateSystems, cellJson, bluetoothJson,
            mockWifi, mockCell, mockBluetooth, enableJitter, alt, satCount
        )
        if (!configWritten) return false

        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.startTimestamp = startTime
        SpooferProvider.simMode = simMode
        SpooferProvider.simBearing = simBearing
        SpooferProvider.routeJson = routePointsToJson(routePoints)
        SpooferProvider.isRouteMode = isRouteMode
        SpooferProvider.wifiJson = wifiJson
        SpooferProvider.cellJson = cellJson
        SpooferProvider.bluetoothJson = bluetoothJson
        SpooferProvider.enableJitter = enableJitter
        return true
    }

    suspend fun updateWifiJson(wifiJson: String, appCoordinateSystems: Map<String, String>): Boolean {
        // 同步写入配置文件,确保Xposed端能读取到WiFi数据
        val configWritten = configManager.saveConfig(
            SpooferProvider.latitude,
            SpooferProvider.longitude,
            SpooferProvider.isActive,
            SpooferProvider.simMode,
            SpooferProvider.simBearing,
            startTimestamp = SpooferProvider.startTimestamp,
            wifiJson = wifiJson,
            appCoordinateSystems = appCoordinateSystems,
            cellJson = SpooferProvider.cellJson,
            bluetoothJson = SpooferProvider.bluetoothJson,
            altitude = settingsManager.altitude.toDoubleOrNull() ?: 0.0,
            satelliteCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        )
        if (configWritten) {
            SpooferProvider.wifiJson = wifiJson
        }
        return configWritten
    }

    private fun routePointsToJson(points: List<RoutePoint>): String {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat", p.lat)
                put("lng", p.lng)
            })
        }
        return arr.toString()
    }

    fun getSavedRoutes(): kotlinx.coroutines.flow.Flow<List<com.suseoaa.locationspoofer.data.db.SavedRouteEntity>> {
        return savedRouteDao.getAllSavedRoutes()
    }

    suspend fun insertSavedRoute(name: String, points: List<RoutePoint>) {
        val pointsJson = routePointsToJson(points)
        savedRouteDao.insertSavedRoute(
            com.suseoaa.locationspoofer.data.db.SavedRouteEntity(
                name = name,
                pointsJson = pointsJson
            )
        )
    }

    suspend fun deleteSavedRoute(route: com.suseoaa.locationspoofer.data.db.SavedRouteEntity) {
        savedRouteDao.deleteSavedRoute(route)
    }
}
