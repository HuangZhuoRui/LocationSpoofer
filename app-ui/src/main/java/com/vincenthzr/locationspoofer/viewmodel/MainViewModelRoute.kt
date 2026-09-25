package com.vincenthzr.locationspoofer.viewmodel

import com.vincenthzr.locationspoofer.utils.MotionRealism
import com.vincenthzr.locationspoofer.ui.BuildConfig
import java.util.Locale
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.model.RoutePlanStage
import com.vincenthzr.locationspoofer.data.model.RouteRunMode
import com.vincenthzr.locationspoofer.data.model.SavedLocation
import com.vincenthzr.locationspoofer.data.model.SimMode
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// MainViewModel 的路线规划、收藏位置/路线与自动巡航相关扩展函数

internal fun MainViewModel.enterRoutePlanning() {
    _uiState.update {
        it.copy(
            routePlanStage = RoutePlanStage.SELECTING,
            routePoints = emptyList()
        )
    }
}

/** 地图中心确认添加路点 */

internal fun MainViewModel.addRoutePoint(lat: Double, lng: Double) {
    _uiState.update { it.copy(routePoints = it.routePoints + RoutePoint(lat, lng)) }
}

/** 撤销最后一个路点 */

internal fun MainViewModel.undoLastRoutePoint() {
    _uiState.update { state ->
        if (state.routePoints.isEmpty()) state
        else state.copy(routePoints = state.routePoints.dropLast(1))
    }
}

/** 结束选点 → READY */

internal fun MainViewModel.finishSelectingPoints() {
    if (_uiState.value.routePoints.size < 2) return
    _uiState.update { it.copy(routePlanStage = RoutePlanStage.READY) }
}

/** 重新选点：清空路点，回到 SELECTING */

internal fun MainViewModel.restartSelectingPoints() {
    _uiState.update {
        it.copy(
            routePoints = emptyList(),
            routePlanStage = RoutePlanStage.SELECTING
        )
    }
}

/** 设置路线运行模式 */

internal fun MainViewModel.setRouteRunMode(mode: RouteRunMode) {
    _uiState.update { it.copy(routeRunMode = mode) }
}

internal fun MainViewModel.saveRoute(name: String, points: List<RoutePoint>) {
    viewModelScope.launch(Dispatchers.IO) {
        locationRepository.insertSavedRoute(name, points)
    }
}

internal fun MainViewModel.deleteSavedRoute(route: com.vincenthzr.locationspoofer.data.model.SavedRoute) {
    viewModelScope.launch(Dispatchers.IO) {
        // 我们通过寻找匹配名称的实体进行删除
        // （有点取巧，但目前有效，或者我们可以在 DAO 中添加按名称删除的功能）
        val routes = locationRepository.getSavedRoutes().first()
        val entity = routes.find { it.name == route.name }
        if (entity != null) {
            locationRepository.deleteSavedRoute(entity)
        }
    }
}

/** 设置循环模式速度 */

internal fun MainViewModel.setRouteSimMode(mode: SimMode) {
    _uiState.update { it.copy(routeSimMode = mode) }
}

/** 设置自定义速度 (m/s) */

internal fun MainViewModel.setCustomSpeedMs(speed: Double) {
    _uiState.update { it.copy(customSpeedMs = speed.coerceIn(0.1, 100.0)) }
}

/** 获取实际生效的速度 (m/s) */

private fun MainViewModel.getEffectiveSpeedMs(): Double {
    val state = _uiState.value
    return if (state.routeSimMode == SimMode.CUSTOM) state.customSpeedMs
    else state.routeSimMode.speedMs
}

/** 摇杆推满时的速度；自定义速度未填或为 0 时回退到步行速度，避免摇杆推不动 */
internal fun MainViewModel.joystickMaxSpeedMs(): Float =
    getEffectiveSpeedMs().takeIf { it > 0.0 }?.toFloat() ?: SimMode.WALKING.speedMs.toFloat()

/** 首页地图确认选点 */

internal fun MainViewModel.confirmMapPoint(lat: Double, lng: Double, isDragging: Boolean = false) {
    _uiState.update {
        it.copy(
            latitudeInput = String.format("%.6f", lat),
            longitudeInput = String.format("%.6f", lng),
            mapConfirmedPoint = Pair(lat, lng),
            showCoordinateError = false
        )
    }
    evaluateMockCapabilities()
    val state = _uiState.value
    if (state.isSpoofingActive) {
        settingsRepository.lastSpoofedLat = lat.toString()
        settingsRepository.lastSpoofedLng = lng.toString()
        viewModelScope.launch {
            if (state.mockWifi && !hasLocalWifiWithin50m(lat, lng) && !isDragging) {
                fetchWifiFromWigleSync(lat, lng)
            }
            if (state.mockCell && !hasLocalCellsWithin50m(lat, lng) && !isDragging) {
                fetchCellFromOpenCellIdSync(lat, lng)
            }
            evaluateMockCapabilitiesSuspend(lat, lng)
            val updatedState = _uiState.value
            locationRepository.updateConfig(
                lat = lat,
                lng = lng,
                simMode = "STILL",
                simBearing = 0f,
                startTime = SpoofingState.startTimestamp,
                routePoints = emptyList(),
                isRouteMode = false,
                appCoordinateSystems = updatedState.appCoordinateSystems,
                wifiJson = updatedState.collectedWifiJson,
                cellJson = updatedState.collectedCellJson,
                bluetoothJson = updatedState.collectedBluetoothJson,
                mockWifi = updatedState.mockWifi && (BuildConfig.GLOBAL_SCHEME || updatedState.canMockWifi),
                mockCell = updatedState.mockCell,
                mockBluetooth = updatedState.mockBluetooth && (BuildConfig.GLOBAL_SCHEME || updatedState.canMockBluetooth),
                enableJitter = updatedState.enableJitter
            )
        }
    }
}

/** 清除地图选点状态 */

internal fun MainViewModel.setUseRealRoute(use: Boolean) {
    _uiState.update { it.copy(useRealRoute = use) }
}

internal fun MainViewModel.setStopAtDestination(stop: Boolean) {
    _uiState.update { it.copy(stopAtDestination = stop) }
}

internal fun MainViewModel.setEnableStepSimulation(enable: Boolean) {
    _uiState.update { it.copy(enableStepSimulation = enable) }
}

internal fun MainViewModel.setStepCadenceSpm(spm: Int) {
    _uiState.update { it.copy(stepCadenceSpm = spm) }
}

internal fun MainViewModel.setIsAutoCadence(auto: Boolean) {
    _uiState.update { it.copy(isAutoCadence = auto) }
}

/**
 * 开始路线模拟。
 * - 手动模式：启动 spoofing（STILL），由摇杆驱动 moveByJoystick 实时更新坐标。
 * - 循环模式：启动 spoofing，自动沿路线点按速度移动，到终点后反向循环。
 */

internal fun MainViewModel.startRoutePlanning() {
    val state = _uiState.value
    if (state.isContinuousScanning) {
        android.widget.Toast.makeText(
            context,
            context.getString(com.vincenthzr.locationspoofer.ui.R.string.disable_continuous_scan_route_first),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return
    }
    if (state.routePoints.size < 2) return

    if (state.useRealRoute) {
        _uiState.update { it.copy(isFetchingRoute = true) }
        fetchRealRouteAndStart(state.routePoints, state)
    } else {
        startSimulationWithPoints(state.routePoints, state)
    }
}

private fun MainViewModel.fetchRealRouteAndStart(points: List<RoutePoint>, state: AppState) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            com.amap.api.services.core.ServiceSettings.updatePrivacyShow(context, true, true)
            com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(context, true)

            val routeSearch = com.amap.api.services.route.RouteSearch(context)
            val allRealPoints = mutableListOf<RoutePoint>()
            var hasError = false

            for (i in 0 until points.size - 1) {
                // 从起点到终点，中间点作为途经点
                val start = com.amap.api.services.core.LatLonPoint(points[i].lat, points[i].lng)
                val end =
                    com.amap.api.services.core.LatLonPoint(points[i + 1].lat, points[i + 1].lng)
                val fromAndTo = com.amap.api.services.route.RouteSearch.FromAndTo(start, end)

                // 创建驾车路线查询 (0: 速度优先，不考虑路况)
                val query = com.amap.api.services.route.RouteSearch.DriveRouteQuery(
                    fromAndTo,
                    com.amap.api.services.route.RouteSearch.DrivingDefault,
                    null,
                    null,
                    ""
                )

                val result = routeSearch.calculateDriveRoute(query)
                if (result != null && result.paths.isNotEmpty()) {
                    val path = result.paths[0]
                    val segmentPoints = mutableListOf<RoutePoint>()
                    val stepEndIndices = mutableListOf<Int>()
                    for (step in path.steps) {
                        for (polyline in step.polyline) {
                            segmentPoints.add(
                                RoutePoint(
                                    polyline.latitude,
                                    polyline.longitude,
                                    0.0
                                )
                            )
                        }
                        if (segmentPoints.isNotEmpty()) {
                            stepEndIndices.add(segmentPoints.size - 1)
                        }
                    }
                    val trafficLights = path.totalTrafficlights
                    if (trafficLights > 0 && stepEndIndices.isNotEmpty()) {
                        stepEndIndices.shuffled().take(trafficLights).forEach { idx ->
                            segmentPoints[idx] = segmentPoints[idx].copy(waitSec = 15.0)
                        }
                    }
                    allRealPoints.addAll(segmentPoints)
                } else {
                    hasError = true
                    break
                }
            }

            if (!hasError && allRealPoints.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isFetchingRoute = false) }
                    startSimulationWithPoints(allRealPoints, state)
                }
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isFetchingRoute = false) }
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.route_plan_failed_fallback_straight),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    startSimulationWithPoints(points, state)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isFetchingRoute = false) }
                var msg = context.getString(R.string.route_request_exception, e.message ?: "")
                if (e is com.amap.api.services.core.AMapException) {
                    val errCode = e.errorCode
                    val errMsg = e.errorMessage ?: ""
                    msg = context.getString(R.string.amap_api_exception_format, errCode, errMsg)
                    if (errCode == 10003 || errCode == 10012 || errCode == 10013 || errCode == 1800 || errCode == 18000 ||
                        errMsg.contains("额度") || errMsg.contains("limit", ignoreCase = true)
                    ) {
                        msg = context.getString(R.string.amap_quota_exhausted_fallback_format, errMsg)
                    }
                }
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG)
                    .show()
                startSimulationWithPoints(points, state)
            }
        }
    }
}

private fun MainViewModel.startSimulationWithPoints(pointsToRun: List<RoutePoint>, state: AppState) {
    val startPoint = pointsToRun.first()

    _uiState.update {
        it.copy(
            latitudeInput = String.format("%.6f", startPoint.lat),
            longitudeInput = String.format("%.6f", startPoint.lng),
            routePlanStage = RoutePlanStage.RUNNING,
            routePoints = pointsToRun
        )
    }

    val isLoop = _uiState.value.routeRunMode == RouteRunMode.LOOP
    val now = System.currentTimeMillis()
    val speed = getEffectiveSpeedMs()

    SpoofingState.startTimestamp = now
    SpoofingState.latitude = startPoint.lat
    SpoofingState.longitude = startPoint.lng
    SpoofingState.simBearing = 0f

    viewModelScope.launch {
        locationRepository.startSpoofing(
            context,
            startPoint.lat,
            startPoint.lng,
            if (isLoop) _uiState.value.routeSimMode.name else "STILL",
            0f,
            now,
            pointsToRun,
            isLoop,
            _uiState.value.appCoordinateSystems,
            _uiState.value.collectedWifiJson,
            _uiState.value.collectedCellJson,
            _uiState.value.collectedBluetoothJson,
            _uiState.value.mockWifi,
            _uiState.value.mockCell,
            _uiState.value.mockBluetooth,
            _uiState.value.enableJitter,
            speedMs = speed,
            stopAtDestination = _uiState.value.stopAtDestination,
            enableStepSimulation = _uiState.value.enableStepSimulation,
            stepCadenceSpm = _uiState.value.stepCadenceSpm,
            isAutoCadence = _uiState.value.isAutoCadence
        )
        _uiState.update {
            it.copy(isSpoofingActive = true)
        }
    }

    if (isLoop) {
        startAutoRouteLoop()
    }
}

/** 停止路线模拟，重置所有状态 */

internal fun MainViewModel.cancelRoutePlanning() {
    _uiState.update {
        it.copy(
            routePlanStage = RoutePlanStage.IDLE,
            routePoints = emptyList(),
            routeRunMode = RouteRunMode.LOOP
        )
    }
}

internal fun MainViewModel.stopRoutePlanning() {
    settingsRepository.isSpoofingActive = false
    locationSyncJob?.cancel()
    locationSyncJob = null
    autoRouteJob?.cancel()
    autoRouteJob = null
    viewModelScope.launch {
        locationRepository.stopSpoofing(context)
        _uiState.update {
            it.copy(
                isSpoofingActive = false,
                routePlanStage = RoutePlanStage.IDLE,
                routePoints = emptyList(),
                routeRunMode = RouteRunMode.LOOP
            )
        }
    }
}

// 保存位置
internal fun MainViewModel.saveCurrentLocation(name: String) {
    val lng = _uiState.value.longitudeInput.toDoubleOrNull() ?: return
    val lat = _uiState.value.latitudeInput.toDoubleOrNull() ?: return
    val state = _uiState.value
    settingsRepository.addSavedLocation(
        SavedLocation(
            name,
            lat,
            lng,
            state.collectedWifiJson,
            state.collectedCellJson,
            // 此前漏了这一段，导致从定位页保存的收藏点丢失蓝牙指纹
            state.collectedBluetoothJson
        )
    )
    _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
}

/** 与卡片标题（LocalDataItem.primaryTitle）保持一致的取名优先级：地名优先，其次备注——
 *  否则收藏成功的 Toast 报的名字会和用户在列表上看到的标题对不上。 */

private fun MainViewModel.resolveFavoriteName(record: com.vincenthzr.locationspoofer.data.db.CompleteLocation): String {
    val lat = record.location.lat
    val lng = record.location.lng
    return when {
        record.location.placeName.isNotBlank() -> record.location.placeName
        record.location.remark.isNotBlank() -> record.location.remark
        else -> String.format(Locale.US, "(%.5f, %.5f)", lat, lng)
    }
}

/** 串行化收藏切换：避免短时间内两次点击的"判断是否已收藏"互相在对方写入落盘前读取，
 *  导致本该互相抵消的两次操作都走进同一个分支。 */

/**
 * 收藏/取消收藏一条采集记录。"本地采集数据源"弹窗和"管理采集数据"页共用同一个入口，
 * 避免两处各自维护一份几乎相同的逻辑而互相漂移。
 */
internal fun MainViewModel.toggleCollectedLocationFavorite(
    locationId: Long,
    onResult: (FavoriteToggleResult) -> Unit
) {
    viewModelScope.launch {
        favoriteToggleMutex.withLock {
            val record = withContext(Dispatchers.IO) {
                environmentDao.getCompleteLocationById(locationId)
            }
            if (record == null) {
                onResult(FavoriteToggleResult.Failed)
                return@withLock
            }

            val lat = record.location.lat
            val lng = record.location.lng
            // 优先按来源 id 关联：这样即便之后编辑了这条采集点的坐标，依然认得出
            // "这是同一条"，不会静默失联。只有老版本写入、没有 sourceLocationId 的
            // 收藏才退回按坐标匹配。删除时会精确匹配 sourceLocationId 或
            // name+lat+lng（见 SettingsManager.isSameSavedLocation），不会波及
            // 同坐标下其他名字/其他来源的收藏。
            val existing = settingsRepository.getSavedLocations().firstOrNull {
                it.sourceLocationId == locationId ||
                    (it.sourceLocationId == null && it.lat == lat && it.lng == lng)
            }

            if (existing != null) {
                settingsRepository.removeSavedLocation(existing)
                _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
                onResult(FavoriteToggleResult.Removed(existing.name))
            } else {
                val name = resolveFavoriteName(record)
                val (wifiJson, cellJson, btJson) = locationToJson(listOf(record), lat, lng)
                settingsRepository.addSavedLocation(
                    SavedLocation(name, lat, lng, wifiJson, cellJson, btJson, sourceLocationId = locationId)
                )
                _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
                onResult(FavoriteToggleResult.Added(name))
            }
        }
    }
}

/**
 * 编辑采集点坐标后，同步更新它对应的收藏记录坐标（按 sourceLocationId 关联），
 * 避免收藏因为坐标变了而找不到关联、变成孤儿数据。老版本收藏（没有 sourceLocationId）
 * 本来就是按坐标关联的独立快照，不受这次编辑影响，这里也不会去动它们。
 */

internal fun MainViewModel.syncFavoriteCoordinateIfExists(locationId: Long, newLat: Double, newLng: Double) {
    val existing = settingsRepository.getSavedLocations()
        .firstOrNull { it.sourceLocationId == locationId } ?: return
    if (existing.lat == newLat && existing.lng == newLng) return
    settingsRepository.removeSavedLocation(existing)
    settingsRepository.addSavedLocation(existing.copy(lat = newLat, lng = newLng))
    _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
}

internal fun MainViewModel.parseWifiCount(wifiJson: String?): Int {
    if (wifiJson.isNullOrBlank()) return 0
    return try {
        val obj = org.json.JSONObject(wifiJson)
        val nearbyCount = obj.optJSONArray("nearbyWifi")?.length() ?: 0
        val connectedCount = if (obj.optBoolean("isConnected", false) && !obj.isNull("connectedWifi")) 1 else 0
        nearbyCount + connectedCount
    } catch (e: Exception) {
        try {
            org.json.JSONArray(wifiJson).length()
        } catch (e2: Exception) {
            0
        }
    }
}

internal fun MainViewModel.loadSavedLocation(loc: SavedLocation) {
    val wifiCount = parseWifiCount(loc.wifiJson)
    _uiState.update {
        it.copy(
            latitudeInput = String.format(java.util.Locale.US, "%.6f", loc.lat),
            longitudeInput = String.format(java.util.Locale.US, "%.6f", loc.lng),
            collectedWifiJson = loc.wifiJson,
            collectedCellJson = loc.cellJson,
            wifiApCount = wifiCount,
            wifiLoadStatus = if (wifiCount > 0) com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.DONE else com.vincenthzr.locationspoofer.data.model.WifiLoadStatus.IDLE
        )
    }
}

internal fun MainViewModel.removeSavedLocation(location: SavedLocation) {
    settingsRepository.removeSavedLocation(location)
    _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
}

internal fun MainViewModel.addSavedRoute(name: String) {
    val points = _uiState.value.routePoints
    if (points.size >= 2) {
        settingsRepository.addSavedRoute(
            com.vincenthzr.locationspoofer.data.model.SavedRoute(
                name,
                points
            )
        )
        _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
    }
}

internal fun MainViewModel.removeSavedRoute(route: com.vincenthzr.locationspoofer.data.model.SavedRoute) {
    settingsRepository.removeSavedRoute(route)
    _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
}

internal fun MainViewModel.loadSavedRoute(route: com.vincenthzr.locationspoofer.data.model.SavedRoute) {
    _uiState.update {
        it.copy(
            routePoints = route.points,
            routePlanStage = com.vincenthzr.locationspoofer.data.model.RoutePlanStage.READY
        )
    }
}

// 搜索


// 内部工具

/**
 * 循环模式自动移动。
 * 按路点顺序移动，到终点后反向，不断循环。
 * 同时实时同步坐标到 SpoofingState。
 */

private fun MainViewModel.startAutoRouteLoop() {
    autoRouteJob?.cancel()
    autoRouteJob = viewModelScope.launch(Dispatchers.Default) {
        val points = _uiState.value.routePoints
        if (points.size < 2) return@launch

        val isClosedLoop = haversineMeters(points.first(), points.last()) <= 5.0

        val speedMs = getEffectiveSpeedMs()
        if (speedMs <= 0.0) return@launch

        val tickMs = 100L
        var forward = true
        var segmentIndex = 0
        var progress = 0.0 // 当前段上已走过的距离（米）
        // 与 Xposed 端 RouteEngine 用同一个"按时间算累计距离"的函数（含速度浮动），两边算出的位置一致
        val realism = MotionRealism.session(
            SpoofingState.startTimestamp,
            SpoofingState.realismLevel.takeIf { it >= 0 } ?: settingsRepository.realismLevel,
            SpoofingState.speedFluctuationPct.takeIf { it >= 0 } ?: settingsRepository.speedFluctuationPct
        )
        var lastTotalDist = 0.0

        while (isActive) {
            val fromIdx = if (forward) segmentIndex else segmentIndex + 1
            val toIdx = if (forward) segmentIndex + 1 else segmentIndex
            val from = points[fromIdx]
            val to = points[toIdx]
            val segLen = haversineMeters(from, to)

            val elapsedSec = (System.currentTimeMillis() - SpoofingState.startTimestamp).coerceAtLeast(0L) / 1000.0
            val totalDist = realism.distance(speedMs, elapsedSec)
            progress += (totalDist - lastTotalDist).coerceAtLeast(0.0)
            lastTotalDist = totalDist

            if (progress >= segLen) {
                // 到达当前段终点
                progress -= segLen
                if (forward) {
                    segmentIndex++
                    if (segmentIndex >= points.lastIndex) {
                        if (_uiState.value.stopAtDestination) {
                            // 到达终点后停下
                            val lastPt = points.last()
                            val prevPt =
                                if (points.size >= 2) points[points.size - 2] else lastPt
                            val lastBearing = bearingBetween(prevPt, lastPt).toFloat()
                            updatePosition(lastPt.lat, lastPt.lng, lastBearing)
                            return@launch
                        } else if (isClosedLoop) {
                            // 闭环路线（起点与终点小于5m）：到达终点后不折返，从起点继续往终点正向循环
                            forward = true
                            segmentIndex = 0
                            progress = 0.0
                        } else {
                            // 开放路线：到达终点，反向折返
                            forward = false
                            segmentIndex = points.lastIndex - 1
                            progress = 0.0
                        }
                    }
                } else {
                    segmentIndex--
                    if (segmentIndex < 0) {
                        // 回到起点，正向
                        forward = true
                        segmentIndex = 0
                        progress = 0.0
                    }
                }
                // 重新获取段信息并继续
                val newFrom = if (forward) points[segmentIndex] else points[segmentIndex + 1]
                val bearing = if (forward) {
                    val nextIdx = (segmentIndex + 1).coerceAtMost(points.lastIndex)
                    bearingBetween(newFrom, points[nextIdx]).toFloat()
                } else {
                    bearingBetween(newFrom, points[segmentIndex]).toFloat()
                }
                updatePosition(newFrom.lat, newFrom.lng, bearing)
            } else {
                // 在段中间插值
                val ratio = if (segLen > 0) progress / segLen else 0.0
                val lat = from.lat + (to.lat - from.lat) * ratio
                val lng = from.lng + (to.lng - from.lng) * ratio
                val bearing = bearingBetween(from, to).toFloat()
                updatePosition(lat, lng, bearing)
            }

            delay(tickMs)
        }
    }
}

/** 更新当前模拟位置到 UI 和 SpoofingState */
private fun MainViewModel.updatePosition(lat: Double, lng: Double, bearing: Float) {
    _uiState.update {
        it.copy(
            latitudeInput = String.format("%.6f", lat),
            longitudeInput = String.format("%.6f", lng),
            simBearing = bearing,
            showCoordinateError = false
        )
    }
    SpoofingState.latitude = lat
    SpoofingState.longitude = lng
    SpoofingState.simBearing = bearing

    // 检查是否需要查询数据库（例如：自上次查询以来移动了超过 20 米）
    val dLat = Math.toRadians(lat - lastDbQueryLat)
    val dLng = Math.toRadians(lng - lastDbQueryLng)
    val a = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(
        Math.toRadians(lastDbQueryLat)
    ) * kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.sin(dLng / 2).let { it * it }
    val distance =
        2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

    if (distance > 20.0) {
        lastDbQueryLat = lat
        lastDbQueryLng = lng
        val isRouteRunning =
            _uiState.value.routePlanStage == com.vincenthzr.locationspoofer.data.model.RoutePlanStage.RUNNING
        val simModeToUse = if (isRouteRunning) _uiState.value.routeSimMode.name else "STILL"
        val speedToUse = getEffectiveSpeedMs()

        viewModelScope.launch(Dispatchers.IO) {
            val records = environmentDao.getNearestLocations(lat, lng, 3)
            if (records.isNotEmpty()) {
                val record = records[0]
                // 检查最近的记录是否实际上在大约 50 米内
                val rLat = Math.toRadians(record.location.lat - lat)
                val rLng = Math.toRadians(record.location.lng - lng)
                val rA = kotlin.math.sin(rLat / 2).let { it * it } + kotlin.math.cos(
                    Math.toRadians(lat)
                ) * kotlin.math.cos(Math.toRadians(record.location.lat)) * kotlin.math.sin(rLng / 2)
                    .let { it * it }
                val rDist = 2 * 6378137.0 * kotlin.math.atan2(
                    kotlin.math.sqrt(rA),
                    kotlin.math.sqrt(1 - rA)
                )

                if (rDist <= 50.0) {
                    val jsons = locationToJson(records, lat, lng)
                    SpoofingState.cellJson = jsons.second
                    // 保存配置文件，写入新的 cell_json、wifi_json 和 bluetoothJson
                    locationRepository.updateConfig(
                        lat = lat,
                        lng = lng,
                        simMode = simModeToUse,
                        simBearing = bearing,
                        startTime = SpoofingState.startTimestamp,
                        routePoints = _uiState.value.routePoints,
                        isRouteMode = isRouteRunning,
                        appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
                        wifiJson = jsons.first,
                        cellJson = jsons.second,
                        bluetoothJson = jsons.third,
                        speedMs = speedToUse,
                        stopAtDestination = _uiState.value.stopAtDestination,
                        enableStepSimulation = _uiState.value.enableStepSimulation,
                        stepCadenceSpm = _uiState.value.stepCadenceSpm,
                        isAutoCadence = _uiState.value.isAutoCadence
                    )
                } else {
                    // 回退到随机基站生成
                    SpoofingState.cellJson = "[]"
                    locationRepository.updateConfig(
                        lat = lat,
                        lng = lng,
                        simMode = simModeToUse,
                        simBearing = bearing,
                        startTime = SpoofingState.startTimestamp,
                        routePoints = _uiState.value.routePoints,
                        isRouteMode = isRouteRunning,
                        appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
                        wifiJson = "[]",
                        cellJson = "[]",
                        bluetoothJson = "[]",
                        speedMs = speedToUse,
                        stopAtDestination = _uiState.value.stopAtDestination,
                        enableStepSimulation = _uiState.value.enableStepSimulation,
                        stepCadenceSpm = _uiState.value.stepCadenceSpm,
                        isAutoCadence = _uiState.value.isAutoCadence
                    )
                }
            } else {
                SpoofingState.cellJson = "[]"
                locationRepository.updateConfig(
                    lat = lat,
                    lng = lng,
                    simMode = simModeToUse,
                    simBearing = bearing,
                    startTime = SpoofingState.startTimestamp,
                    routePoints = _uiState.value.routePoints,
                    isRouteMode = isRouteRunning,
                    appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
                    wifiJson = "[]",
                    cellJson = "[]",
                    bluetoothJson = "[]",
                    speedMs = speedToUse,
                    stopAtDestination = _uiState.value.stopAtDestination,
                    enableStepSimulation = _uiState.value.enableStepSimulation,
                    stepCadenceSpm = _uiState.value.stepCadenceSpm,
                    isAutoCadence = _uiState.value.isAutoCadence
                )
            }
        }
    }
}

private fun MainViewModel.haversineMeters(a: RoutePoint, b: RoutePoint): Double {
    val R = 6378137.0
    val lat1 = Math.toRadians(a.lat);
    val lat2 = Math.toRadians(b.lat)
    val dLat = Math.toRadians(b.lat - a.lat);
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * kotlin.math.sin(dLng / 2)
        .let { it * it }
    return 2 * R * kotlin.math.atan2(kotlin.math.sqrt(h), kotlin.math.sqrt(1 - h))
}

private fun MainViewModel.bearingBetween(from: RoutePoint, to: RoutePoint): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLng = Math.toRadians(to.lng - from.lng)
    val x = kotlin.math.sin(dLng) * kotlin.math.cos(lat2)
    val y = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
            kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLng)
    return (Math.toDegrees(kotlin.math.atan2(x, y)) + 360) % 360
}
