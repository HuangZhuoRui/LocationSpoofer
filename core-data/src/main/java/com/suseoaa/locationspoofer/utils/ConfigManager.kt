package com.suseoaa.locationspoofer.utils

import android.content.Context
import android.location.Geocoder
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.utils.CoordinateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val context: Context, private val rootManager: RootManager) {


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


        val dist = FloatArray(1)
        if (lastGeocodedLat != -999.0) {
            android.location.Location.distanceBetween(
                lastGeocodedLat,
                lastGeocodedLng,
                lat,
                lng,
                dist
            )
        }

        if (lastGeocodedLat == -999.0 || dist[0] > 500f) {
            lastGeocodedLat = lat
            lastGeocodedLng = lng
            try {
                val geocoder = android.location.Geocoder(context, java.util.Locale.CHINA)

                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
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

        val json = JSONObject().apply {
            put("province", cachedProvince)
            put("city", cachedCity)
            put("district", cachedDistrict)
            put("street", cachedStreet)
            put("streetNum", cachedStreetNum)
            put("address", cachedAddressText)
            put("country", cachedCountry)
            put("poiName", cachedPoiName)

            val wgs = CoordinateUtils.gcj02ToWgs84(lat, lng)
            val bd = CoordinateUtils.gcj02ToBd09(lat, lng)
            put("wgs84_lat", wgs.lat)
            put("wgs84_lng", wgs.lng)
            put("bd09_lat", bd.lat)
            put("bd09_lng", bd.lng)
            put("lat", lat)
            put("lng", lng)
            put("active", active)
            put("sim_mode", simMode)
            put("sim_bearing", simBearing.toDouble())
            put("speed_m_s", speedMs)
            put("start_timestamp", startTimestamp)
            put("route_points", routeArray)
            put("is_route_mode", isRouteMode)
            put("stop_at_destination", stopAtDestination)
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
            put("enable_step_simulation", enableStepSimulation)
            put("step_cadence_spm", stepCadenceSpm)
            put("is_auto_cadence", isAutoCadence)

            val coordSysObj = JSONObject()
            appCoordinateSystems.forEach { (pkg, sys) -> coordSysObj.put(pkg, sys) }
            put("app_coordinate_systems", coordSysObj)
        }
        val cellCount = json.optJSONArray("cell_json")?.length() ?: 0

        // 使用 stdin 写入，避免命令行过长 (ARG_MAX) 导致 su 执行失败，实现实时更新
        // 权限模型：DAC 只开到 644（owner=root 读写，其余只读，不再世界可写），
        // 真正决定"谁能读"的是 SELinux 专属 type + 域授权（见 RootManager.ensureSepolicyRules），
        // 不再依赖 shell_data_file/system_data_file 这类通用类型，也不再落一份到 /sdcard/Download 外部存储。
        val selinuxType = RootManager.CONFIG_SELINUX_TYPE
        val jsonText = json.toString()
        val command = """
            chmod 755 /data/local/tmp 2>/dev/null || true
            chmod 755 /data/local 2>/dev/null || true
            mkdir -p /data/data/com.suseoaa.locationspoofer/files 2>/dev/null || true
            chmod 755 /data/data/com.suseoaa.locationspoofer 2>/dev/null || true
            chmod 755 /data/data/com.suseoaa.locationspoofer/files 2>/dev/null || true
            cat > /data/local/tmp/locationspoofer_config_tmp.json
            chmod 644 /data/local/tmp/locationspoofer_config_tmp.json
            chcon u:object_r:$selinuxType:s0 /data/local/tmp/locationspoofer_config_tmp.json 2>/dev/null || true
            cp /data/local/tmp/locationspoofer_config_tmp.json /data/system/locationspoofer_config_tmp.json
            chown system:system /data/system/locationspoofer_config_tmp.json 2>/dev/null || true
            chmod 644 /data/system/locationspoofer_config_tmp.json
            chcon u:object_r:$selinuxType:s0 /data/system/locationspoofer_config_tmp.json 2>/dev/null || true
            cp /data/local/tmp/locationspoofer_config_tmp.json /data/data/com.suseoaa.locationspoofer/files/locationspoofer_config.json
            chmod 644 /data/data/com.suseoaa.locationspoofer/files/locationspoofer_config.json 2>/dev/null || true
            chcon u:object_r:$selinuxType:s0 /data/data/com.suseoaa.locationspoofer/files/locationspoofer_config.json 2>/dev/null || true
            mv /data/local/tmp/locationspoofer_config_tmp.json /data/local/tmp/locationspoofer_config.json
            mv /data/system/locationspoofer_config_tmp.json /data/system/locationspoofer_config.json
            chmod 644 /data/local/tmp/locationspoofer_config.json 2>/dev/null || true
            chmod 644 /data/system/locationspoofer_config.json 2>/dev/null || true
            chcon u:object_r:$selinuxType:s0 /data/local/tmp/locationspoofer_config.json 2>/dev/null || true
            chcon u:object_r:$selinuxType:s0 /data/system/locationspoofer_config.json 2>/dev/null || true
        """.trimIndent()

        val result = rootManager.executeCommandWithInput(command, jsonText)
    }
}
