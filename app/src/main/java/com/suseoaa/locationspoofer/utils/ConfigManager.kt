package com.suseoaa.locationspoofer.utils

import com.suseoaa.locationspoofer.data.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(private val rootManager: RootManager) {
    private val saveMutex = Mutex()

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
    ) = saveMutex.withLock {
        withContext(Dispatchers.IO) {
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
            android.util.Log.d(
                "OpenCellID",
                "saveConfig: active=$active mockCell=$mockCell lat=$lat lng=$lng cellJsonCount=$cellCount"
            )

            // 使用 quoted heredoc 写入，避免 JSON 中的引号、美元符号等被 shell 解析。
            // 两个目标都先完成 staging；发布任一副本后若后续步骤失败，则恢复旧副本，
            // 避免 app hook 与 system hook 观察到一次失败写入的不同状态。
            val jsonText = json.toString()
            val command = """
            set -e
            local_target=/data/local/tmp/locationspoofer_config.json
            system_target=/data/system/locationspoofer_config.json
            local_tmp=/data/local/tmp/.locationspoofer_config.json.tmp.$$
            system_tmp=/data/system/.locationspoofer_config.json.tmp.$$
            local_backup=/data/local/tmp/.locationspoofer_config.json.backup.$$
            system_backup=/data/system/.locationspoofer_config.json.backup.$$
            had_local=0
            had_system=0
            published_local=0
            published_system=0
            committed=0
            finish() {
              status=${'$'}?
              trap - EXIT HUP INT TERM
              set +e
              if [ "${'$'}committed" -ne 1 ]; then
                if [ "${'$'}published_local" -eq 1 ]; then
                  if [ "${'$'}had_local" -eq 1 ]; then
                    mv -f "${'$'}local_backup" "${'$'}local_target"
                    chmod 666 "${'$'}local_target"
                    chcon u:object_r:shell_data_file:s0 "${'$'}local_target" 2>/dev/null || true
                  else
                    rm -f "${'$'}local_target"
                  fi
                fi
                if [ "${'$'}published_system" -eq 1 ]; then
                  if [ "${'$'}had_system" -eq 1 ]; then
                    mv -f "${'$'}system_backup" "${'$'}system_target"
                    chown system:system "${'$'}system_target" 2>/dev/null || true
                    chmod 644 "${'$'}system_target"
                    chcon u:object_r:system_data_file:s0 "${'$'}system_target" 2>/dev/null || true
                  else
                    rm -f "${'$'}system_target"
                  fi
                fi
              fi
              rm -f "${'$'}local_tmp" "${'$'}system_tmp" "${'$'}local_backup" "${'$'}system_backup"
              exit "${'$'}status"
            }
            trap finish EXIT
            trap 'exit 129' HUP
            trap 'exit 130' INT
            trap 'exit 143' TERM

            cat > "${'$'}local_tmp" <<'LOCATIONSPOOFER_JSON'
            $jsonText
            LOCATIONSPOOFER_JSON
            chmod 666 "${'$'}local_tmp"
            chcon u:object_r:shell_data_file:s0 "${'$'}local_tmp" 2>/dev/null || true

            cat > "${'$'}system_tmp" <<'LOCATIONSPOOFER_JSON_SYSTEM'
            $jsonText
            LOCATIONSPOOFER_JSON_SYSTEM
            chown system:system "${'$'}system_tmp" 2>/dev/null || true
            chmod 644 "${'$'}system_tmp"
            chcon u:object_r:system_data_file:s0 "${'$'}system_tmp" 2>/dev/null || true

            if [ -e "${'$'}local_target" ]; then
              cp "${'$'}local_target" "${'$'}local_backup"
              had_local=1
            fi
            if [ -e "${'$'}system_target" ]; then
              cp "${'$'}system_target" "${'$'}system_backup"
              had_system=1
            fi

            mv -f "${'$'}local_tmp" "${'$'}local_target"
            published_local=1
            mv -f "${'$'}system_tmp" "${'$'}system_target"
            published_system=1
            committed=1
            """.trimIndent()

        val result = rootManager.executeCommand(command)
        if (result.startsWith("ERROR")) {
            android.util.Log.e(
                "OpenCellID",
                "saveConfig: failed to write config copies: ${result.take(500)}"
            )
            return@withContext false
        }
        android.util.Log.d(
                "OpenCellID",
                "saveConfig: wrote config copies to /data/local/tmp and /data/system, result=${result.take(200)}"
            )
            true
        }
    }
}
