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
import kotlinx.coroutines.withContext

// MainViewModel 的模拟开关、摇杆移动与持续扫描相关扩展函数

internal fun MainViewModel.startSpoofing() {
    val state = _uiState.value

    if (state.isContinuousScanning || state.isStoppingCollection) {
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
            updatedState.mockWifi && updatedState.canMockWifi,
            updatedState.mockCell,
            updatedState.mockBluetooth && updatedState.canMockBluetooth,
            updatedState.enableJitter
        )
        motionController.onStaticStarted(lat, lng)

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
 * 强制重启已勾选作用域的目标 App，让它们加载当前模块并订阅框架配置。
 * 由"开始模拟"弹窗里的开关驱动，用户已经通过默认打开的开关预先同意，这里不再二次确认。
 */

private suspend fun MainViewModel.restartHookedAppsSilently() {
    if (!BuildConfig.GLOBAL_SCHEME) {
        // 非全局方案：重启 LSPosed 作用域里勾选的全部 App（包括承担融合定位的 GMS）
        val apps = lsposedManager.getHookedApps(context)
        if (apps.isNotEmpty()) {
            locationRepository.checkRootAccess() // 确认 Root 授权，以便强制停止目标应用
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
        locationRepository.checkRootAccess() // 确认 Root 授权，以便强制停止目标应用
        locationRepository.forceStopApps(toKill)
    }
}

internal fun MainViewModel.stopSpoofing() {
    settingsRepository.isSpoofingActive = false
    locationSyncJob?.cancel()
    locationSyncJob = null
    motionController.onStopped()
    viewModelScope.launch {
        locationRepository.stopSpoofing(context)
        _uiState.update {
            it.copy(isSpoofingActive = false)
        }
    }
}

/**
 * 悬浮摇杆只服务于"摇杆手动控制"的路线模拟，没有"显示在其他应用上层"权限时无法操作。
 * 缺权限时提示并跳到系统授权页，返回 false，由调用方放弃这次启动；用户授权回来后重新开始即可。
 */
internal fun MainViewModel.ensureOverlayPermission(): Boolean {
    if (android.provider.Settings.canDrawOverlays(context)) return true
    android.widget.Toast.makeText(
        context,
        context.getString(R.string.overlay_permission_required),
        android.widget.Toast.LENGTH_LONG
    ).show()
    context.startActivity(
        android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    return false
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
    if (!_uiState.value.isSpoofingActive) return
    val state = _uiState.value
    // 只改开关字段，不动位置与路线状态（整份重写会用界面里过时的状态覆盖掉悬浮窗 / 路线的运动状态）
    viewModelScope.launch {
        locationRepository.patchConfig { json ->
            json.put("mock_wifi", state.mockWifi && state.canMockWifi)
            json.put("mock_cell", state.mockCell)
            json.put("mock_bluetooth", state.mockBluetooth && state.canMockBluetooth)
            json.put("enable_jitter", state.enableJitter)
        }
    }
}

internal fun MainViewModel.toggleContinuousScanning() {
    val state = _uiState.value
    if (state.isStoppingCollection || state.pendingCollectionLocations.isNotEmpty() || state.pendingCollectionRoute != null) return
    if (state.isSpoofingActive) {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.stop_spoofing_before_scan),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }

    if (state.isContinuousScanning) {
        _uiState.update { it.copy(isContinuousScanning = false, isStoppingCollection = true) }
        // 最后一轮 NonCancellable 扫描与保存结束后，finally 才显示信息弹窗。
        continuousScanJob?.cancel()
        return
    }

    if (state.isRouteCollection && (state.isDrawingCollectionRoute || state.collectionRoutePoints.size < 2)) return

    _uiState.update {
        it.copy(
            isContinuousScanning = true,
            scannedWifiCount = 0,
            scannedCellCount = 0,
            scannedBluetoothCount = 0
        )
    }
    continuousScanJob = viewModelScope.launch(Dispatchers.IO) {
        val savedIds = linkedSetOf<Long>()
        var routeId: Long? = null
        try {
            if (state.isRouteCollection) {
                val route = com.vincenthzr.locationspoofer.data.db.CollectionRouteRecord(
                    name = context.getString(R.string.collection_route_default_name,
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())),
                    pointsJson = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.vincenthzr.locationspoofer.data.model.RoutePoint.serializer()),
                        state.collectionRoutePoints),
                    halfWidthM = 50.0
                )
                withContext(NonCancellable) { routeId = environmentDao.insertCollectionRoute(route) }
            }
            while (isActive) {
                val realLoc = fetchRealLocationSilent(context)
                val insideRoute = !state.isRouteCollection || (realLoc != null &&
                    com.vincenthzr.locationspoofer.utils.PolylineCoverage.distanceMeters(
                        com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng(realLoc.first, realLoc.second),
                        state.collectionRoutePoints.map { com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng(it.lat, it.lng) }
                    ) <= 50.0)
                if (realLoc != null && insideRoute) {
                    val lat = realLoc.first
                    val lng = realLoc.second
                    withContext(NonCancellable) {
                        val wifiJson = environmentScanner.scanWifi()
                        val cellJson = environmentScanner.scanCell()
                        val bluetoothJson = environmentScanner.scanBluetooth()
                        val id = saveEnvironmentData(lat, lng, wifiJson, cellJson, bluetoothJson, mergeNearby = true, collectionRouteId = routeId)
                        savedIds += id
                        val wCount = parseWifiCount(wifiJson)
                        val cCount = org.json.JSONArray(cellJson).length()
                        val bCount = org.json.JSONArray(bluetoothJson).length()
                        _uiState.update {
                            it.copy(
                                scannedWifiCount = it.scannedWifiCount + wCount,
                                scannedCellCount = it.scannedCellCount + cCount,
                                scannedBluetoothCount = it.scannedBluetoothCount + bCount
                            )
                        }
                    }
                }
                delay(10_000)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error.printStackTrace()
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, R.string.collection_save_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(NonCancellable) {
                val records = try {
                    savedIds.mapNotNull { environmentDao.getCompleteLocationById(it)?.location }
                } catch (error: Exception) {
                    error.printStackTrace()
                    emptyList()
                }
                val savedRoute = routeId?.let { id ->
                    if (savedIds.isEmpty()) {
                        environmentDao.deleteCollectionRoute(id)
                        null
                    } else runCatching { environmentDao.getCollectionRoute(id)?.route }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    val requestedStop = _uiState.value.isStoppingCollection
                    continuousScanJob = null
                    _uiState.update {
                        it.copy(
                            isContinuousScanning = false,
                            isStoppingCollection = false,
                            pendingCollectionLocations = if (routeId == null) records else emptyList(),
                            pendingCollectionRoute = savedRoute
                        )
                    }
                    if (requestedStop && savedIds.isEmpty()) {
                        android.widget.Toast.makeText(context, R.string.collection_no_saved_points, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

internal fun MainViewModel.skipCollectionInfo() {
    if (_uiState.value.isSavingCollectionInfo) return
    _uiState.update { it.copy(pendingCollectionLocations = it.pendingCollectionLocations.drop(1)) }
}

internal fun MainViewModel.saveCollectionInfo(locationId: Long, placeName: String, remark: String) {
    val state = _uiState.value
    if (state.isSavingCollectionInfo || state.pendingCollectionLocations.firstOrNull()?.id != locationId) return
    _uiState.update { it.copy(isSavingCollectionInfo = true) }
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                environmentDao.updateBasicInfo(locationId, placeName.trim(), remark.trim())
            }
            _uiState.update {
                it.copy(
                    isSavingCollectionInfo = false,
                    pendingCollectionLocations = it.pendingCollectionLocations.drop(1),
                    pinnedLocationName = if (it.pinnedCollectedLocationId == locationId) {
                        remark.trim().ifBlank { placeName.trim() }.ifBlank { it.pinnedLocationName }
                    } else it.pinnedLocationName
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _uiState.update { it.copy(isSavingCollectionInfo = false) }
            android.widget.Toast.makeText(context, R.string.collection_info_save_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
