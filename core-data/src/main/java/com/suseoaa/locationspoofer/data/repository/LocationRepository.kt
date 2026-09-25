package com.suseoaa.locationspoofer.data.repository

import android.content.Context
import android.location.LocationManager
import android.os.Build
import com.suseoaa.locationspoofer.data.db.SavedRouteDao
import com.suseoaa.locationspoofer.data.db.SavedRouteEntity
import com.suseoaa.locationspoofer.data.model.RootSetupTestResult
import com.suseoaa.locationspoofer.data.model.RootSolution
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.state.SpoofingState
import com.suseoaa.locationspoofer.utils.ConfigManager
import com.suseoaa.locationspoofer.utils.LSPosedManager
import com.suseoaa.locationspoofer.utils.RootManager
import com.suseoaa.locationspoofer.utils.SettingsManager
import org.json.JSONArray
import org.json.JSONObject

class LocationRepository(
    private val configManager: ConfigManager,
    private val rootManager: RootManager,
    private val lsposedManager: LSPosedManager,
    private val settingsManager: SettingsManager,
    private val savedRouteDao: SavedRouteDao,
    private val spoofingServiceController: SpoofingServiceController
) {
    /** 用户在设置里选择的 root 方案；解析失败（脏数据/尚未设置）时退回 AUTO 全集探测 */
    private fun currentRootSolution(): RootSolution =
        try {
            RootSolution.valueOf(settingsManager.rootSolution)
        } catch (e: Exception) {
            RootSolution.AUTO
        }

    suspend fun checkRootAccess(): Boolean = rootManager.checkRootAccess(currentRootSolution())

    /** 检测 root 权限与 sepolicy 规则注入是否正常，返回完整诊断结果供设置页"测试"按钮展示 */
    suspend fun testRootSetup(): RootSetupTestResult = rootManager.testRootSetup(currentRootSolution())

    /** 强制停止目标 App，逼迫它们下次启动时以最新的 sepolicy 规则重新走一次访问判定 */
    suspend fun forceStopApps(packages: List<String>) = rootManager.forceStopApps(packages)

    /**
     * 设备重启 / App 更新后的统一自愈入口：重新下发 live sepolicy 规则，
     * 并在模拟定位此前处于开启状态时重新调用 startSpoofing() 把配置完整落盘。
     * 供 BootCompletedReceiver 和 MainViewModel.initialize() 共同复用，
     * 避免这段逻辑两处各写一份。
     */
    suspend fun recoverAfterBoot(context: Context): Boolean {
        val hasRoot = rootManager.checkRootAccess(currentRootSolution())

        if (settingsManager.isSpoofingActive) {
            val lastLat = settingsManager.lastSpoofedLat.toDoubleOrNull() ?: 0.0
            val lastLng = settingsManager.lastSpoofedLng.toDoubleOrNull() ?: 0.0
            if (lastLat != 0.0 && lastLng != 0.0) {
                startSpoofing(
                    context, lastLat, lastLng,
                    "STILL", 0f, System.currentTimeMillis(),
                    emptyList(), false,
                    settingsManager.getAppCoordinateSystems(),
                    mockWifi = settingsManager.mockWifi,
                    mockCell = settingsManager.mockCell,
                    mockBluetooth = settingsManager.mockBluetooth,
                    enableJitter = settingsManager.enableJitter
                )
            }
        } else if (spoofingServiceController.isRunning) {
            stopSpoofing(context)
        }

        return hasRoot
    }

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
        enableJitter: Boolean = true,
        speedMs: Double = 0.0,
        stopAtDestination: Boolean = false,
        enableStepSimulation: Boolean = true,
        stepCadenceSpm: Int = 165,
        isAutoCadence: Boolean = true
    ) {
        SpoofingState.isActive = true
        SpoofingState.latitude = lat
        SpoofingState.longitude = lng
        SpoofingState.startTimestamp = startTime
        SpoofingState.simMode = simMode
        SpoofingState.simBearing = simBearing
        SpoofingState.wifiJson = wifiJson
        SpoofingState.cellJson = cellJson
        SpoofingState.bluetoothJson = bluetoothJson
        SpoofingState.routeJson = routePointsToJson(routePoints)
        SpoofingState.isRouteMode = isRouteMode
        SpoofingState.enableJitter = enableJitter

        // 与 stopSpoofing() 里的 revokeMockLocation() 对称：上一次停止模拟时会把
        // android:mock_location 重置为 default（用于避免被系统安全中心一直记录为"模拟中"），
        // 如果这里不重新授予，第二次开始模拟时该 AppOps 就会一直停留在 default，
        // 导致"模拟一次后关闭再开就再也无法模拟"。
        rootManager.grantMockLocation()

        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        configManager.saveConfig(
            lat,
            lng,
            true,
            simMode,
            simBearing,
            startTime,
            routePoints,
            isRouteMode,
            SpoofingState.wifiJson,
            appCoordinateSystems,
            SpoofingState.cellJson,
            SpoofingState.bluetoothJson,
            mockWifi,
            mockCell,
            mockBluetooth,
            enableJitter,
            alt,
            satCount,
            speedMs,
            stopAtDestination,
            enableStepSimulation,
            stepCadenceSpm,
            isAutoCadence
        )

        spoofingServiceController.startForeground(context, lat, lng)
    }

    suspend fun stopSpoofing(context: Context) {
        SpoofingState.isActive = false
        SpoofingState.latitude = 0.0
        SpoofingState.longitude = 0.0
        SpoofingState.wifiJson = "[]"
        SpoofingState.cellJson = "[]"
        SpoofingState.bluetoothJson = "[]"
        SpoofingState.routeJson = "[]"
        SpoofingState.isRouteMode = false
        configManager.saveConfig(0.0, 0.0, false)

        // 1. 同步在当前进程清理所有可能残留的 TestProvider
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm != null) {
            try {
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            } catch (e: Throwable) {
            }
            try {
                lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (e: Throwable) {
            }
            try {
                lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
            } catch (e: Throwable) {
            }
            try {
                lm.removeTestProvider(LocationManager.NETWORK_PROVIDER)
            } catch (e: Throwable) {
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lm.setTestProviderEnabled(LocationManager.FUSED_PROVIDER, false)
                    lm.removeTestProvider(LocationManager.FUSED_PROVIDER)
                }
            } catch (e: Throwable) {
            }
        }

        // 2. 停止前台模拟服务
        spoofingServiceController.stop(context)

        // 3. 彻底重置系统的 mock_location 状态，防止被澎湃OS/系统安全中心记录为模拟中
        rootManager.revokeMockLocation()
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
        wifiJson: String = SpoofingState.wifiJson,
        cellJson: String = SpoofingState.cellJson,
        bluetoothJson: String = SpoofingState.bluetoothJson,
        mockWifi: Boolean = true,
        mockCell: Boolean = true,
        mockBluetooth: Boolean = true,
        enableJitter: Boolean = true,
        speedMs: Double = 0.0,
        stopAtDestination: Boolean = false,
        enableStepSimulation: Boolean = true,
        stepCadenceSpm: Int = 165,
        isAutoCadence: Boolean = true
    ) {
        SpoofingState.latitude = lat
        SpoofingState.longitude = lng
        SpoofingState.startTimestamp = startTime
        SpoofingState.simMode = simMode
        SpoofingState.simBearing = simBearing
        SpoofingState.routeJson = routePointsToJson(routePoints)
        SpoofingState.isRouteMode = isRouteMode
        SpoofingState.wifiJson = wifiJson
        SpoofingState.cellJson = cellJson
        SpoofingState.bluetoothJson = bluetoothJson
        SpoofingState.enableJitter = enableJitter
        val alt = settingsManager.altitude.toDoubleOrNull() ?: 0.0
        val satCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        configManager.saveConfig(
            lat,
            lng,
            true,
            simMode,
            simBearing,
            startTime,
            routePoints,
            isRouteMode,
            SpoofingState.wifiJson,
            appCoordinateSystems,
            SpoofingState.cellJson,
            SpoofingState.bluetoothJson,
            mockWifi,
            mockCell,
            mockBluetooth,
            enableJitter,
            alt,
            satCount,
            speedMs,
            stopAtDestination,
            enableStepSimulation,
            stepCadenceSpm,
            isAutoCadence
        )
    }

    suspend fun updateWifiJson(wifiJson: String, appCoordinateSystems: Map<String, String>) {
        SpoofingState.wifiJson = wifiJson
        // 同步写入配置文件,确保Xposed端能读取到WiFi数据
        configManager.saveConfig(
            SpoofingState.latitude,
            SpoofingState.longitude,
            SpoofingState.isActive,
            SpoofingState.simMode,
            SpoofingState.simBearing,
            startTimestamp = SpoofingState.startTimestamp,
            wifiJson = wifiJson,
            appCoordinateSystems = appCoordinateSystems,
            cellJson = SpoofingState.cellJson,
            bluetoothJson = SpoofingState.bluetoothJson,
            altitude = settingsManager.altitude.toDoubleOrNull() ?: 0.0,
            satelliteCount = settingsManager.satelliteCount.toIntOrNull() ?: 20
        )
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

    fun getSavedRoutes(): kotlinx.coroutines.flow.Flow<List<SavedRouteEntity>> {
        return savedRouteDao.getAllSavedRoutes()
    }

    suspend fun getAllSavedRoutesList(): List<SavedRouteEntity> {
        return savedRouteDao.getAllSavedRoutesList()
    }

    suspend fun insertSavedRoute(name: String, points: List<RoutePoint>) {
        val pointsJson = routePointsToJson(points)
        savedRouteDao.insertSavedRoute(
            SavedRouteEntity(
                name = name,
                pointsJson = pointsJson
            )
        )
    }

    suspend fun insertSavedRouteEntity(route: SavedRouteEntity) {
        savedRouteDao.insertSavedRoute(route)
    }

    suspend fun deleteSavedRoute(route: SavedRouteEntity) {
        savedRouteDao.deleteSavedRoute(route)
    }
}
