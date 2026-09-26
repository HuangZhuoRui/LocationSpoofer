package com.vincenthzr.locationspoofer.utils

import android.content.Context
import android.location.Geocoder
import com.vincenthzr.locationspoofer.data.BuildConfig
import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val context: Context, private val rootManager: RootManager) {

    // system_hook_packages 是独立于每次模拟会话的常驻勾选项（在"系统级模拟应用"页面里配置），
    // 不随 lat/lng/active 这类瞬时状态一起在调用方逐层透传，这里每次落盘时直接读取最新值即可。
    private val settingsManager = SettingsManager(context)

    private var lastGeocodedLat = -999.0
    private var lastGeocodedLng = -999.0
    private var cachedProvince = ""
    private var cachedCity = ""
    private var cachedDistrict = ""
    private var cachedStreet = ""
    private var cachedStreetNum = ""
    private var cachedAddressText = ""
    private var cachedCountry = ""
    private var cachedPoiName = ""

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
        satelliteCount: Int = 20,
        speedMs: Double = 0.0,
        stopAtDestination: Boolean = false,
        enableStepSimulation: Boolean = true,
        stepCadenceSpm: Int = 165,
        isAutoCadence: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val routeArray = JSONArray()
        routePoints.forEach { p ->
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lng", p.lng)
            routeArray.put(obj)
        }


        val json = JSONObject().apply {
            putPosition(this, lat, lng)
            put("active", active)
            put("sim_mode", simMode)
            put("sim_bearing", simBearing.toDouble())
            put("speed_m_s", speedMs)
            put("start_timestamp", startTimestamp)
            put("route_points", routeArray)
            put("is_route_mode", isRouteMode)
            put("stop_at_destination", stopAtDestination)
            putEnvironment(this, wifiJson, cellJson, bluetoothJson)
            put("mock_wifi", mockWifi)
            put("mock_cell", mockCell)
            put("mock_bluetooth", mockBluetooth)
            put("enable_jitter", enableJitter)
            put("altitude", altitude)
            put("satellite_count", satelliteCount)
            put("enable_step_simulation", enableStepSimulation)
            put("step_cadence_spm", stepCadenceSpm)
            put("is_auto_cadence", isAutoCadence)

            val coordSysObj = JSONObject()
            appCoordinateSystems.forEach { (pkg, sys) -> coordSysObj.put(pkg, sys) }
            put("app_coordinate_systems", coordSysObj)

            put("route_distance_offset", SpoofingState.routeDistanceOffset)
            putPersistentSettings(this)
        }
        write(json)
    }

    /**
     * 在上一次写入的配置基础上只改动部分字段，其余字段原样保留；常驻设置项每次都刷新为最新值。
     * 暂停、摇杆、周边环境数据、开关设置等局部更新都走这里，避免某个调用方用自己手里过时的整份状态
     * 覆盖掉别人刚写入的字段（例如悬浮窗刚把路线暂停，界面的周边数据刷新又把路线模式写回去）。
     * 还没有写过配置（未开始模拟）时返回 false。
     */
    suspend fun patchConfig(mutate: (JSONObject) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // 读取、修改、写入整体放在锁内：并发的局部更新若各自基于同一份旧配置修改，后写的会冲掉先写的改动
        writeMutex.withLock {
            val base = lastJson ?: return@withLock false
            val json = JSONObject(base.toString())
            mutate(json)
            putPersistentSettings(json)
            writeLocked(json)
            true
        }
    }

    /** 写入坐标及其派生字段（WGS-84 / BD-09 坐标、逆地理编码地址）；移动超过 500 米才重新逆地理编码。需在 IO 线程调用 */
    fun putPosition(json: JSONObject, lat: Double, lng: Double) {
        refreshGeocodeIfMoved(lat, lng)
        json.put("province", cachedProvince)
        json.put("city", cachedCity)
        json.put("district", cachedDistrict)
        json.put("street", cachedStreet)
        json.put("streetNum", cachedStreetNum)
        json.put("address", cachedAddressText)
        json.put("country", cachedCountry)
        json.put("poiName", cachedPoiName)

        val wgs = CoordinateUtils.gcj02ToWgs84(lat, lng)
        val bd = CoordinateUtils.gcj02ToBd09(lat, lng)
        json.put("wgs84_lat", wgs.lat)
        json.put("wgs84_lng", wgs.lng)
        json.put("bd09_lat", bd.lat)
        json.put("bd09_lng", bd.lng)
        json.put("lat", lat)
        json.put("lng", lng)
    }

    fun putEnvironment(json: JSONObject, wifiJson: String, cellJson: String, bluetoothJson: String) {
        val wifiObj = try {
            JSONObject(wifiJson)
        } catch (e: Exception) {
            JSONObject().apply {
                put("isConnected", false)
                put("connectedWifi", JSONObject.NULL)
                put("nearbyWifi", JSONArray())
            }
        }
        json.put("wifi_json", wifiObj)
        json.put("cell_json", JSONArray(cellJson))
        json.put("bluetooth_json", JSONArray(bluetoothJson))
    }

    /** 独立于每次模拟会话的常驻设置项，调用方不逐层透传，每次落盘时直接读取最新值 */
    private fun putPersistentSettings(json: JSONObject) {
        val systemHookPackagesArr = JSONArray()
        settingsManager.getSystemHookPackages().forEach { systemHookPackagesArr.put(it) }
        json.put("system_hook_packages", systemHookPackagesArr)
        json.put("system_hook_global_mode", settingsManager.isSystemHookGlobalMode)
        json.put("vendor_override", settingsManager.vendorOverride)
        json.put("force_location_enabled", settingsManager.forceLocationEnabled)
        json.put("debug_dump_system_services", settingsManager.debugDumpSystemServices)
        // 运动真实度：Xposed 端据此给速度、步频、海拔、加速度加起伏；随机强度与速度浮动在会话内取开始时的快照
        json.put("realism_level", SpoofingState.realismLevel.takeIf { it >= 0 } ?: settingsManager.realismLevel)
        json.put("speed_fluctuation_pct", SpoofingState.speedFluctuationPct.takeIf { it >= 0 } ?: settingsManager.speedFluctuationPct)
        json.put("gait_template", if (settingsManager.useGaitTemplate) settingsManager.gaitTemplate else "")
        json.put("altitude_variation_m", settingsManager.altitudeVariationM)
    }

    private fun refreshGeocodeIfMoved(lat: Double, lng: Double) {
        val dist = FloatArray(1)
        if (lastGeocodedLat != -999.0) {
            android.location.Location.distanceBetween(lastGeocodedLat, lastGeocodedLng, lat, lng, dist)
        }
        if (lastGeocodedLat != -999.0 && dist[0] <= 500f) return
        lastGeocodedLat = lat
        lastGeocodedLng = lng
        try {
            val geocoder = android.location.Geocoder(context, java.util.Locale.CHINA)
            // lat/lng 是 GCJ-02，而 Geocoder 按 Android 规范接收 WGS-84；直接传 GCJ-02 会被后端再加密一次，
            // 偏出数百米、反查到错误的街道（issue #62：广州塔 → 赏湖街）
            val wgs = CoordinateUtils.gcj02ToWgs84(lat, lng)

            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(wgs.lat, wgs.lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                cachedProvince = addr.adminArea ?: ""
                cachedCity = addr.locality ?: addr.subAdminArea ?: ""
                cachedDistrict = addr.subLocality ?: ""
                cachedStreet = addr.thoroughfare ?: ""
                cachedStreetNum = addr.subThoroughfare ?: ""
                cachedAddressText = addr.getAddressLine(0) ?: ""
                cachedCountry = addr.countryName ?: "中国"
                cachedPoiName = addr.featureName ?: ""
            }
        } catch (e: Exception) {
            // 逆地理编码失败时静默降级
        }
    }

    private val writeMutex = Mutex()

    @Volatile
    private var lastJson: JSONObject? = null

    /**
     * 使用 stdin 写入，避免命令行过长 (ARG_MAX) 导致 su 执行失败，实现实时更新。
     * 权限模型：DAC 只开到 644（owner=root 读写，其余只读，不再世界可写），不再落一份到 /sdcard/Download 外部存储。
     * 两种模拟方案的"谁来读配置"不同，SELinux 标签策略也不同，见下面两个 xxxWriteCommand。
     * 写入串行化：多个来源（界面、悬浮窗、周边数据刷新）并发写同一组文件时，保证最后落盘的是最后一次写入。
     */
    private suspend fun write(json: JSONObject) = writeMutex.withLock { writeLocked(json) }

    private suspend fun writeLocked(json: JSONObject) {
        check(context.filesDir.isDirectory) { "应用配置目录无法创建" }
        val command = if (BuildConfig.GLOBAL_SCHEME) globalWriteCommand() else scopedWriteCommand()
        check(rootManager.executeCommandWithInput(command, json.toString()) != "ERROR") {
            "配置写入失败，请检查 Root 权限和系统目录访问权限"
        }
        lastJson = json
    }

    /**
     * 非全局方案：配置由各目标 App 进程自己读取，统一打项目专属 SELinux type，
     * 再由 RootManager.ensureSepolicyRules 给 untrusted_app 等域授权读取。
     */
    private fun scopedWriteCommand(): String {
        val selinuxType = RootManager.CONFIG_SELINUX_TYPE
        return """
            chmod 755 /data/local/tmp 2>/dev/null || true
            chmod 755 /data/local 2>/dev/null || true
            mkdir -p /data/data/com.vincenthzr.locationspoofer/files 2>/dev/null || true
            chmod 755 /data/data/com.vincenthzr.locationspoofer 2>/dev/null || true
            chmod 755 /data/data/com.vincenthzr.locationspoofer/files 2>/dev/null || true
            cat > /data/local/tmp/locationspoofer_config_tmp.json
            chmod 644 /data/local/tmp/locationspoofer_config_tmp.json
            chcon u:object_r:$selinuxType:s0 /data/local/tmp/locationspoofer_config_tmp.json 2>/dev/null || true
            cp /data/local/tmp/locationspoofer_config_tmp.json /data/system/locationspoofer_config_tmp.json
            chown system:system /data/system/locationspoofer_config_tmp.json 2>/dev/null || true
            chmod 644 /data/system/locationspoofer_config_tmp.json
            chcon u:object_r:$selinuxType:s0 /data/system/locationspoofer_config_tmp.json 2>/dev/null || true
            cp /data/local/tmp/locationspoofer_config_tmp.json /data/data/com.vincenthzr.locationspoofer/files/locationspoofer_config.json
            chmod 644 /data/data/com.vincenthzr.locationspoofer/files/locationspoofer_config.json 2>/dev/null || true
            chcon u:object_r:$selinuxType:s0 /data/data/com.vincenthzr.locationspoofer/files/locationspoofer_config.json 2>/dev/null || true
            mv /data/local/tmp/locationspoofer_config_tmp.json /data/local/tmp/locationspoofer_config.json
            mv /data/system/locationspoofer_config_tmp.json /data/system/locationspoofer_config.json
            chmod 644 /data/local/tmp/locationspoofer_config.json 2>/dev/null || true
            chmod 644 /data/system/locationspoofer_config.json 2>/dev/null || true
            chcon u:object_r:$selinuxType:s0 /data/local/tmp/locationspoofer_config.json 2>/dev/null || true
            chcon u:object_r:$selinuxType:s0 /data/system/locationspoofer_config.json 2>/dev/null || true
        """.trimIndent()
    }

    /**
     * 全局方案：配置由 system_server / com.android.phone / com.android.bluetooth 三个系统进程读取，
     * 各自落一份到本域可读的数据目录，并使用该域原生的 SELinux 标签与 uid 所有权。
     */
    private fun globalWriteCommand(): String = SystemFileCommands.writeGlobal()

    fun syncDomainConfigs() {
        if (!BuildConfig.GLOBAL_SCHEME) return
        if (rootManager.executeCommand(SystemFileCommands.syncGlobal()) == "ERROR") {
            android.util.Log.e("LocationSpoofer", "Failed to synchronize phone/Bluetooth configuration")
        }
    }
}
