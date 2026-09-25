package com.vincenthzr.locationspoofer.viewmodel

import com.vincenthzr.locationspoofer.ui.BuildConfig
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.db.LocationRecord
import com.vincenthzr.locationspoofer.data.model.RoutePlanStage
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// MainViewModel 的模拟开关、摇杆移动与持续扫描相关扩展函数

internal fun MainViewModel.startSpoofing() {
    val state = _uiState.value

    if (state.isContinuousScanning) {
        android.widget.Toast.makeText(
            context,
            context.getString(com.vincenthzr.locationspoofer.ui.R.string.disable_continuous_scan_first),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }

    val lng = state.longitudeInput.toDoubleOrNull()
    val lat = state.latitudeInput.toDoubleOrNull()
    if (lng == null || lat == null || lng !in -180.0..180.0 || lat !in -90.0..90.0) {
        _uiState.update { it.copy(showCoordinateError = true) }
        return
    }

    settingsRepository.isSpoofingActive = true
    settingsRepository.lastSpoofedLat = lat.toString()
    settingsRepository.lastSpoofedLng = lng.toString()

    viewModelScope.launch {
        _uiState.update { it.copy(isSavingConfig = true) }

        if (state.mockWifi && !hasLocalWifiWithin50m(lat, lng)) {
            fetchWifiFromWigleSync(lat, lng)
        }
        if (state.mockCell && !hasLocalCellsWithin50m(lat, lng)) {
            fetchCellFromOpenCellIdSync(lat, lng)
        }

        evaluateMockCapabilitiesSuspend(lat, lng)

        val updatedState = _uiState.value
        val now = System.currentTimeMillis()
        locationRepository.startSpoofing(
            context, lat, lng,
            "STILL", 0f, now,
            emptyList(), false,
            updatedState.appCoordinateSystems,
            updatedState.collectedWifiJson,
            updatedState.collectedCellJson,
            updatedState.collectedBluetoothJson,
            updatedState.mockWifi && (BuildConfig.GLOBAL_SCHEME || updatedState.canMockWifi),
            updatedState.mockCell,
            updatedState.mockBluetooth && (BuildConfig.GLOBAL_SCHEME || updatedState.canMockBluetooth),
            updatedState.enableJitter
        )

        // 稍作等待，确保 root shell 完全同步到磁盘
        kotlinx.coroutines.delay(200)

        if (updatedState.restartAppsOnSpoof) {
            restartHookedAppsSilently()
        }

        _uiState.update {
            it.copy(isSpoofingActive = true, isSavingConfig = false)
        }
    }
}

/**
 * 强制重启已勾选作用域的目标 App，逼迫它们以刚写入的最新配置/sepolicy 规则重新走一次
 * 全新的 SELinux 判定——不再受历史 AVC 缓存或其他模块 sepolicy 操作的影响。
 * 由"开始模拟"弹窗里的开关驱动，用户已经通过默认打开的开关预先同意，这里不再二次确认。
 */

private suspend fun MainViewModel.restartHookedAppsSilently() {
    if (!BuildConfig.GLOBAL_SCHEME) {
        // 非全局方案：重启 LSPosed 作用域里勾选的全部 App（包括承担融合定位的 GMS）
        val apps = lsposedManager.getHookedApps(context)
        if (apps.isNotEmpty()) {
            locationRepository.checkRootAccess() // 重新下发 sepolicy 规则，确保重启后读到的是最新的
            locationRepository.forceStopApps(apps.map { it.packageName })
        }
        return
    }
    val targetPackages = mutableSetOf<String>()
    // 1. LSPosed 传统作用域勾选的应用
    targetPackages.addAll(lsposedManager.getHookedApps(context).map { it.packageName })
    // 2. 系统级 Hook 目标应用包名列表
    targetPackages.addAll(settingsRepository.getSystemHookPackages())

    // 严格排除系统核心组件、电话服务、SystemUI 与自身，避免误杀核心服务或导致自身退出
    val exempt = setOf(
        context.packageName,
        "android",
        "system",
        "system_server",
        "com.android.systemui",
        "com.android.phone",
        "com.android.bluetooth",
        "com.android.server.telecom",
        "com.xiaomi.metoknlp",
        "com.google.android.gms"
    )
    val toKill = targetPackages.filter { it.isNotBlank() && !exempt.contains(it) }
    if (toKill.isNotEmpty()) {
        locationRepository.checkRootAccess() // 重新下发 sepolicy 规则，确保重启后读到的是最新的
        locationRepository.forceStopApps(toKill)
    }
}

internal fun MainViewModel.stopSpoofing() {
    settingsRepository.isSpoofingActive = false
    locationSyncJob?.cancel()
    locationSyncJob = null
    autoRouteJob?.cancel()
    autoRouteJob = null
    viewModelScope.launch {
        locationRepository.stopSpoofing(context)
        _uiState.update {
            it.copy(isSpoofingActive = false)
        }
    }
}

// 摇杆控制
internal fun MainViewModel.moveByJoystick(bearing: Double, intensity: Float, maxSpeedMs: Float) {
    val elapsedSec = 0.1
    val distance = maxSpeedMs * intensity * elapsedSec
    val R = 6378137.0
    val bearingRad = Math.toRadians(bearing)
    val lat = _uiState.value.latitudeInput.toDoubleOrNull() ?: return
    val lng = _uiState.value.longitudeInput.toDoubleOrNull() ?: return
    val latRad = Math.toRadians(lat)
    val lngRad = Math.toRadians(lng)
    val newLatRad = Math.asin(
        kotlin.math.sin(latRad) * kotlin.math.cos(distance / R) +
                kotlin.math.cos(latRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(
            bearingRad
        )
    )
    val newLngRad = lngRad + kotlin.math.atan2(
        kotlin.math.sin(bearingRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(latRad),
        kotlin.math.cos(distance / R) - kotlin.math.sin(latRad) * kotlin.math.sin(newLatRad)
    )
    val newLat = Math.toDegrees(newLatRad)
    val newLng = Math.toDegrees(newLngRad)
    _uiState.update {
        it.copy(
            latitudeInput = String.format("%.6f", newLat),
            longitudeInput = String.format("%.6f", newLng),
            simBearing = bearing.toFloat(),
            showCoordinateError = false
        )
    }
    // 实时同步给 SpoofingState
    val now = System.currentTimeMillis()
    SpoofingState.latitude = newLat
    SpoofingState.longitude = newLng
    SpoofingState.simBearing = bearing.toFloat()
    SpoofingState.startTimestamp = now

    // 被 Hook 的 App 只认配置文件，不写文件它们就一直停在起点（issue #63）。
    // 落盘是 root 写文件，按节流间隔写入；两次写入之间由 Xposed 端 RouteEngine 按方向 + 速度推算位置
    if (now - lastJoystickSyncTime >= JOYSTICK_SYNC_INTERVAL_MS) {
        lastJoystickSyncTime = now
        syncJoystickConfig(newLat, newLng, bearing.toFloat(), (maxSpeedMs * intensity).toDouble(), now)
    }
}

private const val JOYSTICK_SYNC_INTERVAL_MS = 1000L

/** 松开摇杆：立即写入速度 0，让 Xposed 端停止推算位置 */
internal fun MainViewModel.stopJoystick() {
    val lat = _uiState.value.latitudeInput.toDoubleOrNull() ?: return
    val lng = _uiState.value.longitudeInput.toDoubleOrNull() ?: return
    lastJoystickSyncTime = 0L
    syncJoystickConfig(lat, lng, _uiState.value.simBearing, 0.0, System.currentTimeMillis())
}

private fun MainViewModel.syncJoystickConfig(lat: Double, lng: Double, bearing: Float, speedMs: Double, timestamp: Long) {
    val state = _uiState.value
    if (!state.isSpoofingActive) return
    viewModelScope.launch {
        joystickSyncMutex.withLock {
            locationRepository.updateConfig(
                lat = lat,
                lng = lng,
                simMode = "JOYSTICK",
                simBearing = bearing,
                startTime = timestamp,
                routePoints = emptyList(),
                isRouteMode = false,
                appCoordinateSystems = state.appCoordinateSystems,
                wifiJson = state.collectedWifiJson,
                cellJson = state.collectedCellJson,
                bluetoothJson = state.collectedBluetoothJson,
                mockWifi = state.mockWifi && (BuildConfig.GLOBAL_SCHEME || state.canMockWifi),
                mockCell = state.mockCell,
                mockBluetooth = state.mockBluetooth && (BuildConfig.GLOBAL_SCHEME || state.canMockBluetooth),
                enableJitter = state.enableJitter,
                speedMs = speedMs,
                enableStepSimulation = state.enableStepSimulation,
                stepCadenceSpm = state.stepCadenceSpm,
                isAutoCadence = state.isAutoCadence
            )
        }
    }
}

// 路线规划状态机

/** 进入全屏地图，进入选点阶段 */

internal fun MainViewModel.toggleMockWifi() {
    val newVal = !_uiState.value.mockWifi
    settingsRepository.mockWifi = newVal
    _uiState.update { it.copy(mockWifi = newVal) }
    syncMockSettings()
}

internal fun MainViewModel.toggleMockCell() {
    val newVal = !_uiState.value.mockCell
    settingsRepository.mockCell = newVal
    _uiState.update { it.copy(mockCell = newVal) }
    syncMockSettings()
}

internal fun MainViewModel.toggleMockBluetooth() {
    val newVal = !_uiState.value.mockBluetooth
    settingsRepository.mockBluetooth = newVal
    _uiState.update { it.copy(mockBluetooth = newVal) }
    syncMockSettings()
}

internal fun MainViewModel.toggleEnableJitter() {
    val newVal = !_uiState.value.enableJitter
    settingsRepository.enableJitter = newVal
    _uiState.update { it.copy(enableJitter = newVal) }
    syncMockSettings()
}

/** 只影响下一次"开始模拟"的行为，不需要像其他 mock 开关那样实时同步到运行中的配置 */

internal fun MainViewModel.toggleRestartAppsOnSpoof() {
    val newVal = !_uiState.value.restartAppsOnSpoof
    settingsRepository.restartAppsOnSpoof = newVal
    _uiState.update { it.copy(restartAppsOnSpoof = newVal) }
}

private fun MainViewModel.syncMockSettings() {
    if (_uiState.value.isSpoofingActive) {
        val state = _uiState.value
        val lat = state.latitudeInput.toDoubleOrNull() ?: return
        val lng = state.longitudeInput.toDoubleOrNull() ?: return
        viewModelScope.launch {
            locationRepository.updateConfig(
                lat = lat,
                lng = lng,
                simMode = if (state.routePlanStage == com.vincenthzr.locationspoofer.data.model.RoutePlanStage.RUNNING) state.routeSimMode.name else "STILL",
                simBearing = state.simBearing,
                startTime = SpoofingState.startTimestamp,
                routePoints = state.routePoints,
                isRouteMode = state.routePlanStage == com.vincenthzr.locationspoofer.data.model.RoutePlanStage.RUNNING,
                appCoordinateSystems = state.appCoordinateSystems,
                wifiJson = state.collectedWifiJson,
                cellJson = state.collectedCellJson,
                bluetoothJson = state.collectedBluetoothJson,
                mockWifi = state.mockWifi,
                mockCell = state.mockCell,
                mockBluetooth = state.mockBluetooth,
                enableJitter = state.enableJitter
            )
        }
    }
}

internal fun MainViewModel.toggleContinuousScanning() {
    if (_uiState.value.isSpoofingActive) {
        android.widget.Toast.makeText(
            context,
            context.getString(com.vincenthzr.locationspoofer.ui.R.string.disable_continuous_scan_route_first),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }

    if (_uiState.value.isSpoofingActive) {
        android.widget.Toast.makeText(
            context,
            context.getString(com.vincenthzr.locationspoofer.ui.R.string.stop_spoofing_before_scan),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }

    val currentState = _uiState.value.isContinuousScanning
    _uiState.update { it.copy(isContinuousScanning = !currentState) }

    if (!currentState) {
        // Start scanning
        _uiState.update {
            it.copy(
                scannedWifiCount = 0,
                scannedCellCount = 0,
                scannedBluetoothCount = 0
            )
        }
        continuousScanJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val realLoc = fetchRealLocationSilent(context)
                if (realLoc != null) {
                    val lat = realLoc.first
                    val lng = realLoc.second

                    // 用户点"停止采集"时 job 会被 cancel()，如果扫描/落库中途被取消，
                    // 这一轮已经扫到的数据会直接丢失（对应 issue #59）。
                    // 用 NonCancellable 包住这段，保证一轮扫描+保存要么完整做完要么还没开始，
                    // 取消信号只会在下一次 delay 处生效。
                    var saveFailed = false
                    withContext(NonCancellable) {
                        val wifiJson = environmentScanner.scanWifi()
                        val cellJson = environmentScanner.scanCell()
                        val bluetoothJson = environmentScanner.scanBluetooth()

                        val wCount = parseWifiCount(wifiJson)
                        val cCount = try {
                            org.json.JSONArray(cellJson).length()
                        } catch (e: Exception) {
                            0
                        }
                        val bCount = try {
                            org.json.JSONArray(bluetoothJson).length()
                        } catch (e: Exception) {
                            0
                        }

                        try {
                            saveEnvironmentData(lat, lng, wifiJson, cellJson, bluetoothJson)
                        } catch (e: Exception) {
                            // 之前这里的异常会未捕获地冒泡出去，直接把整个采集协程杀死，
                            // 但 isContinuousScanning 不会被重置，UI 会一直显示"采集中"，
                            // 用户毫无感知（对应 issue #60 里"有时会采集失败或保存失败却没有提示"）。
                            e.printStackTrace()
                            saveFailed = true
                        }

                        if (!saveFailed) {
                            val count = environmentDao.getRecordCount()
                            _uiState.update {
                                it.copy(
                                    environmentRecordCount = count,
                                    scannedWifiCount = it.scannedWifiCount + wCount,
                                    scannedCellCount = it.scannedCellCount + cCount,
                                    scannedBluetoothCount = it.scannedBluetoothCount + bCount
                                )
                            }
                        }
                    }

                    if (saveFailed) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(com.vincenthzr.locationspoofer.ui.R.string.collection_save_failed),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        _uiState.update { it.copy(isContinuousScanning = false) }
                        continuousScanJob = null
                        break
                    }
                }

                // 扫描之间延迟 10 秒
                delay(10000)
            }
        }
    } else {
        // Stop scanning
        continuousScanJob?.cancel()
        continuousScanJob = null
    }
}

internal fun MainViewModel.refreshRecordCount() {
    viewModelScope.launch(Dispatchers.IO) {
        val count = environmentDao.getRecordCount()
        _uiState.update { it.copy(environmentRecordCount = count) }
    }
}

internal suspend fun MainViewModel.getAllLocations(): List<com.vincenthzr.locationspoofer.data.db.LocationRecord> {
    return environmentDao.getAllLocations()
}

internal fun MainViewModel.onManageDataChanged() {
    refreshRecordCount()
    evaluateMockCapabilities()
}
