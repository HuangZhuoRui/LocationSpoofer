package com.suseoaa.locationspoofer.utils

import android.content.Context
import android.content.SharedPreferences
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.SavedRoute
import org.json.JSONArray
import org.json.JSONObject

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var isDarkMode: Boolean
        get() = prefs.getBoolean("is_dark_mode", true)
        set(value) = prefs.edit().putBoolean("is_dark_mode", value).apply()

    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit().putString("language", value).apply()

    var isLanguageSet: Boolean
        get() = prefs.getBoolean("is_language_set", false)
        set(value) = prefs.edit().putBoolean("is_language_set", value).apply()

    var amapApiKey: String
        get() = prefs.getString("amap_api_key", "") ?: ""
        set(value) = prefs.edit().putString("amap_api_key", value).apply()

    var baiduApiKey: String
        get() = prefs.getString("baidu_api_key", "") ?: ""
        set(value) = prefs.edit().putString("baidu_api_key", value).apply()

    var googleApiKey: String
        get() = prefs.getString("google_api_key", "") ?: ""
        set(value) = prefs.edit().putString("google_api_key", value).apply()

    var wigleApiToken: String
        get() = prefs.getString("wigle_api_token", "") ?: ""
        set(value) = prefs.edit().putString("wigle_api_token", value).apply()

    var opencellidApiToken: String
        get() = prefs.getString("opencellid_api_token", "") ?: ""
        set(value) = prefs.edit().putString("opencellid_api_token", value).apply()

    var mapType: String
        get() = prefs.getString("map_type", "NORMAL") ?: "NORMAL"
        set(value) = prefs.edit().putString("map_type", value).apply()

    var mapEngine: String
        get() = prefs.getString("map_engine", "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString("map_engine", value).apply()

    var rootSolution: String
        get() = prefs.getString("root_solution", "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString("root_solution", value).apply()

    var ignoredVersion: String
        get() = prefs.getString("ignored_version", "") ?: ""
        set(value) = prefs.edit().putString("ignored_version", value).apply()

    var checkBetaUpdates: Boolean
        get() = prefs.getBoolean("check_beta_updates", false)
        set(value) = prefs.edit().putBoolean("check_beta_updates", value).apply()

    var isSpoofingActive: Boolean
        get() = prefs.getBoolean("is_spoofing_active", false)
        set(value) = prefs.edit().putBoolean("is_spoofing_active", value).apply()

    var lastSpoofedLat: String
        get() = prefs.getString("last_spoofed_lat", "0") ?: "0"
        set(value) = prefs.edit().putString("last_spoofed_lat", value).apply()

    var lastSpoofedLng: String
        get() = prefs.getString("last_spoofed_lng", "0") ?: "0"
        set(value) = prefs.edit().putString("last_spoofed_lng", value).apply()

    var mockWifi: Boolean
        get() = prefs.getBoolean("mock_wifi", true)
        set(value) = prefs.edit().putBoolean("mock_wifi", value).apply()

    var mockCell: Boolean
        get() = prefs.getBoolean("mock_cell", true)
        set(value) = prefs.edit().putBoolean("mock_cell", value).apply()

    var mockBluetooth: Boolean
        get() = prefs.getBoolean("mock_bluetooth", true)
        set(value) = prefs.edit().putBoolean("mock_bluetooth", value).apply()

    var enableJitter: Boolean
        get() = prefs.getBoolean("enable_jitter", true)
        set(value) = prefs.edit().putBoolean("enable_jitter", value).apply()

    /** 开始模拟时是否强制重启已勾选作用域的目标 App，让它们以最新 sepolicy 规则重新走一次权限判定 */
    var restartAppsOnSpoof: Boolean
        get() = prefs.getBoolean("restart_apps_on_spoof", true)
        set(value) = prefs.edit().putBoolean("restart_apps_on_spoof", value).apply()

    var altitude: String
        get() = prefs.getString("altitude", "0.0") ?: "0.0"
        set(value) = prefs.edit().putString("altitude", value).apply()

    var satelliteCount: String
        get() = prefs.getString("satellite_count", "10") ?: "10"
        set(value) = prefs.edit().putString("satellite_count", value).apply()

    fun getSavedLocations(): List<SavedLocation> {
        val jsonString = prefs.getString("saved_locations", "[]") ?: "[]"
        val list = mutableListOf<SavedLocation>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SavedLocation(
                        name = obj.optString("name", ""),
                        lat = obj.optDouble("lat", 0.0),
                        lng = obj.optDouble("lng", 0.0),
                        wifiJson = obj.optString("wifiJson", "[]"),
                        cellJson = obj.optString("cellJson", "[]"),
                        bluetoothJson = obj.optString("bluetoothJson", "[]"),
                        // 老版本写入的收藏没有这个 key，has() 为 false 时保持 null
                        sourceLocationId = if (obj.has("sourceLocationId")) obj.optLong("sourceLocationId") else null
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * 两条收藏是否视为"同一条"：都来自同一条采集记录（sourceLocationId 相同且非空）时按
     * 来源关联判断——这样编辑过采集点坐标也认得出来；否则退回按 name+lat+lng 精确匹配
     * （手动收藏、或老版本写入的没有来源 id 的收藏）。不能只按坐标匹配，那样会把同一坐标
     * 下其他名字/其他来源的收藏一并命中。
     */
    private fun isSameSavedLocation(a: SavedLocation, b: SavedLocation): Boolean {
        if (a.sourceLocationId != null && b.sourceLocationId != null) {
            return a.sourceLocationId == b.sourceLocationId
        }
        return a.name == b.name && a.lat == b.lat && a.lng == b.lng
    }

    fun addSavedLocation(location: SavedLocation) {
        val list = getSavedLocations().toMutableList()
        list.removeAll { isSameSavedLocation(it, location) }
        list.add(location)
        saveLocationList(list)
    }

    fun removeSavedLocation(location: SavedLocation) {
        val list = getSavedLocations().toMutableList()
        list.removeAll { isSameSavedLocation(it, location) }
        saveLocationList(list)
    }

    fun saveLocationList(list: List<SavedLocation>) {
        val jsonArray = JSONArray()
        list.forEach {
            val obj = JSONObject()
            obj.put("name", it.name)
            obj.put("lat", it.lat)
            obj.put("lng", it.lng)
            obj.put("wifiJson", it.wifiJson)
            obj.put("cellJson", it.cellJson)
            obj.put("bluetoothJson", it.bluetoothJson)
            if (it.sourceLocationId != null) {
                obj.put("sourceLocationId", it.sourceLocationId)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_locations", jsonArray.toString()).apply()
    }

    fun getSavedRoutes(): List<SavedRoute> {
        val jsonString = prefs.getString("saved_routes", "[]") ?: "[]"
        val list = mutableListOf<SavedRoute>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pointsArray = obj.getJSONArray("points")
                val points = (0 until pointsArray.length()).map { j ->
                    val p = pointsArray.getJSONObject(j)
                    RoutePoint(p.getDouble("lat"), p.getDouble("lng"))
                }
                list.add(SavedRoute(obj.getString("name"), points))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addSavedRoute(route: SavedRoute) {
        val list = getSavedRoutes().toMutableList()
        list.add(route)
        saveRouteList(list)
    }

    fun removeSavedRoute(route: SavedRoute) {
        val list = getSavedRoutes().toMutableList()
        list.removeAll { it.name == route.name }
        saveRouteList(list)
    }

    private fun saveRouteList(list: List<SavedRoute>) {
        val jsonArray = JSONArray()
        list.forEach { route ->
            val obj = JSONObject()
            obj.put("name", route.name)
            val pointsArray = JSONArray()
            route.points.forEach { p ->
                val pObj = JSONObject()
                pObj.put("lat", p.lat)
                pObj.put("lng", p.lng)
                pointsArray.put(pObj)
            }
            obj.put("points", pointsArray)
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_routes", jsonArray.toString()).apply()
    }

    fun getAppCoordinateSystems(): Map<String, String> {
        val jsonString = prefs.getString("app_coordinate_systems", "{}") ?: "{}"
        val map = mutableMapOf<String, String>()
        try {
            val jsonObj = JSONObject(jsonString)
            for (key in jsonObj.keys()) {
                map[key] = jsonObj.getString(key)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun setAppCoordinateSystems(map: Map<String, String>) {
        val jsonObj = JSONObject()
        map.forEach { (k, v) -> jsonObj.put(k, v) }
        prefs.edit().putString("app_coordinate_systems", jsonObj.toString()).apply()
    }
}
