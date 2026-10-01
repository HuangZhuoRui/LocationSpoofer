package com.vincenthzr.locationspoofer.utils

import android.content.Context
import android.location.Geocoder
import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import com.vincenthzr.locationspoofer.utils.CoordinateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val context: Context, private val rootManager: RootManager) {

    // system_hook_packages 是独立于每次模拟会话的常驻勾选项（在"系统级模拟应用"页面里配置），
    // 不随 lat/lng/active 这类瞬时状态一起在调用方逐层透传，这里每次发布时直接读取最新值即可。
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
    ): Boolean = withContext(Dispatchers.IO) {
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

    private val _writeFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 框架配置发布失败时发出，界面据此提示用户（core-data 没有字符串资源，不在这里直接弹提示） */
    val writeFailures: SharedFlow<Unit> = _writeFailures

    /**
     * 在上一次写入的配置基础上只改动部分字段，其余字段原样保留；常驻设置项每次都刷新为最新值。
     * 暂停、摇杆、周边环境数据、开关设置等局部更新都走这里，避免某个调用方用自己手里过时的整份状态
     * 覆盖掉别人刚写入的字段（例如悬浮窗刚把路线暂停，界面的周边数据刷新又把路线模式写回去）。
     * 还没有配置（未开始模拟）或发布失败时返回 false；未发布的最新状态会在框架重连后重试。
     */
    suspend fun patchConfig(mutate: (JSONObject) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // 读取、修改、写入整体放在锁内：并发的局部更新若各自基于同一份旧配置修改，后写的会冲掉先写的改动
        writeMutex.withLock {
            val base = lastJson ?: return@withLock false
            val json = JSONObject(base.toString())
            mutate(json)
            putPersistentSettings(json)
            writeLocked(json)
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

    /** 独立于每次模拟会话的常驻设置项，调用方不逐层透传，每次发布时直接读取最新值 */
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

    private val transportPrefs = context.getSharedPreferences("framework_config_state", Context.MODE_PRIVATE)
    private val publisher = FrameworkConfigPublisher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastJson: JSONObject? = transportPrefs.getString("desired_config", null)?.let {
        runCatching { JSONObject(it) }.getOrNull()
    }
    @Volatile private var publicationPending = lastJson != null
    private var nextCleanupAttemptAt = 0L

    init {
        // StateFlow delivers an already-connected service too, so initialization order is harmless.
        scope.launch {
            XposedModuleStatus.service.collect { service ->
                if (service != null) writeMutex.withLock {
                    publisher.onReconnect()
                    lastJson?.let { publishLocked(it, service) }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(3_000)
                if (publicationPending) writeMutex.withLock {
                    val service = XposedModuleStatus.mService
                    if (publicationPending && service != null) lastJson?.let {
                        publishLocked(it, service, notifyFailure = false)
                    }
                }
            }
        }
    }

    private suspend fun write(json: JSONObject): Boolean = writeMutex.withLock { writeLocked(json) }

    private fun writeLocked(json: JSONObject): Boolean {
        // Remember the desired state even if a stop/pause happens while the service is disconnected.
        // Reconnection must never replay an older active session over a newer stop request.
        if (!transportPrefs.edit().putString("desired_config", json.toString()).commit()) {
            _writeFailures.tryEmit(Unit)
            return false
        }
        lastJson = json
        publicationPending = true
        val service = XposedModuleStatus.mService
        if (service == null) {
            android.util.Log.w("LocationSpoofer", "Framework disconnected; latest configuration queued")
            _writeFailures.tryEmit(Unit)
            return false
        }
        return publishLocked(json, service)
    }

    private fun publishLocked(
        json: JSONObject,
        service: io.github.libxposed.service.XposedService,
        notifyFailure: Boolean = true
    ): Boolean {
        return try {
            val prefs = service.getRemotePreferences(FrameworkConfigChannel.GROUP)
            publisher.publish(json.toString(), object : FrameworkConfigStore {
                override fun commit(snapshot: String): Boolean =
                    prefs.edit().putString(FrameworkConfigChannel.SNAPSHOT_KEY, snapshot).commit()

                override fun writeFile(name: String, payload: ByteArray) {
                    ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { it.write(payload) }
                }

                override fun listFiles(): List<String> = service.listRemoteFiles().toList()
                override fun deleteFile(name: String) { service.deleteRemoteFile(name) }
            })
            publicationPending = false
            // Migration runs only after the replacement channel accepted a complete snapshot.
            val now = System.currentTimeMillis()
            if (!transportPrefs.getBoolean("legacy_files_removed", false) && now >= nextCleanupAttemptAt) {
                nextCleanupAttemptAt = now + 60_000
                // Root authorization/cleanup must not delay subsequent stop or coordinate updates.
                scope.launch {
                    if (rootManager.executeCommand(SystemFileCommands.removeLegacyConfigs()) != "ERROR") {
                        transportPrefs.edit().putBoolean("legacy_files_removed", true).apply()
                    }
                }
            }
            true
        } catch (error: Exception) {
            android.util.Log.e("LocationSpoofer", "Framework config publication failed", error)
            publicationPending = true
            if (notifyFailure) _writeFailures.tryEmit(Unit)
            false
        }
    }
}
