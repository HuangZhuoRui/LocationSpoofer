package com.suseoaa.locationspoofer.viewmodel

import android.content.Context
import java.util.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.google.android.gms.location.LocationServices
import com.suseoaa.locationspoofer.LocationApp
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.db.EnvironmentDao
import com.suseoaa.locationspoofer.data.db.LocationRecord
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.ImportExportCounts
import com.suseoaa.locationspoofer.data.model.ImportExportSelection
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.RoutePlanStage
import com.suseoaa.locationspoofer.data.model.RouteRunMode
import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.SimMode
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.data.model.MapEngine
import com.suseoaa.locationspoofer.data.model.SearchMode
import com.suseoaa.locationspoofer.data.repository.LocationRepository
import com.suseoaa.locationspoofer.data.repository.SettingsRepository
import com.suseoaa.locationspoofer.data.repository.WifiRepository
import com.suseoaa.locationspoofer.data.state.SpoofingState
import com.suseoaa.locationspoofer.service.SpoofingService
import com.suseoaa.locationspoofer.ui.screen.AppPoiItem
import com.suseoaa.locationspoofer.ui.screen.spoofing.SpoofingIntent
import com.suseoaa.locationspoofer.ui.screen.spoofing.SpoofingUiState
import com.suseoaa.locationspoofer.utils.EnvironmentScanner
import com.suseoaa.locationspoofer.utils.LSPosedManager
import com.suseoaa.locationspoofer.utils.OpenCellIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainViewModel(
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val lsposedManager: LSPosedManager,
    private val environmentScanner: EnvironmentScanner,
    private val environmentDao: EnvironmentDao,
    private val wifiRepository: WifiRepository,
    private val opencellidClient: OpenCellIdClient,
    private val context: Context
) : ViewModel() {
    private var lastMapMoveTime = 0L
    private var mapMoveJob: Job? = null

    private val _uiState = MutableStateFlow(
        AppState(
            mapType = try {
                AppMapType.valueOf(settingsRepository.getMapType())
            } catch (e: Exception) {
                AppMapType.NORMAL
            },
            mapEngine = try {
                MapEngine.valueOf(settingsRepository.getMapEngine())
            } catch (e: Exception) {
                MapEngine.AUTO
            },
            savedLocations = settingsRepository.getSavedLocations(),
            savedRoutes = emptyList(), // 将由 Room Flow 填充
            currentLanguage = settingsRepository.getLanguage(),
            isLanguageSet = settingsRepository.isLanguageSet(),
            appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
            mockWifi = settingsRepository.mockWifi,
            mockCell = settingsRepository.mockCell,
            mockBluetooth = settingsRepository.mockBluetooth,
            enableJitter = settingsRepository.enableJitter,
            altitudeInput = settingsRepository.altitude,
            satelliteCountInput = settingsRepository.satelliteCount,
            wigleToken = settingsRepository.getWigleApiToken(),
            opencellidToken = settingsRepository.getOpencellidApiToken()
        )
    )
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private val _spoofingUiState =
        MutableStateFlow(SpoofingUiState())
    val spoofingUiState: StateFlow<SpoofingUiState> =
        _spoofingUiState.asStateFlow()

    private var locationSyncJob: Job? = null
    private var autoRouteJob: Job? = null
    private var continuousScanJob: Job? = null

    init {
        initialize()
    }

    // 初始化

    private fun initialize() {
        viewModelScope.launch(Dispatchers.IO) {
            mergeLegacyRecords()
            val root = locationRepository.checkRootAccess()

            if (settingsRepository.isSpoofingActive) {
                val lastLat = settingsRepository.lastSpoofedLat.toDoubleOrNull() ?: 0.0
                val lastLng = settingsRepository.lastSpoofedLng.toDoubleOrNull() ?: 0.0
                if (lastLat != 0.0 && lastLng != 0.0) {
                    locationRepository.startSpoofing(
                        context, lastLat, lastLng,
                        "STILL", 0f, System.currentTimeMillis(),
                        emptyList(), false,
                        settingsRepository.getAppCoordinateSystems(),
                        mockWifi = settingsRepository.mockWifi,
                        mockCell = settingsRepository.mockCell,
                        mockBluetooth = settingsRepository.mockBluetooth,
                        enableJitter = settingsRepository.enableJitter
                    )
                }
            } else if (SpoofingService.isRunning) {
                locationRepository.stopSpoofing(context)
            }

            _uiState.update {
                it.copy(
                    isInitializing = false,
                    hasRootAccess = root,
                    isSpoofingActive = settingsRepository.isSpoofingActive,
                    latitudeInput = if (settingsRepository.isSpoofingActive) settingsRepository.lastSpoofedLat else it.latitudeInput,
                    longitudeInput = if (settingsRepository.isSpoofingActive) settingsRepository.lastSpoofedLng else it.longitudeInput,
                    routePlanStage = RoutePlanStage.IDLE,
                    amapApiKey = settingsRepository.getAmapApiKey(),
                    baiduApiKey = settingsRepository.getBaiduApiKey(),
                    googleApiKey = settingsRepository.getGoogleApiKey(),
                    appSha1 = getAppSignatureSHA1(),
                    checkBetaUpdates = settingsRepository.checkBetaUpdates
                )
            }
            if (!settingsRepository.isSpoofingActive) {
                fetchCurrentLocation(context)
            }
            refreshRecordCount()
        }

        viewModelScope.launch {
            LocationApp.isModuleActive.collect { active ->
                _uiState.update {
                    it.copy(
                        isLSPosedActive = active,
                        hookedApps = if (active) lsposedManager.getHookedApps(context) else emptyList()
                    )
                }
            }
        }

        viewModelScope.launch {
            locationRepository.getSavedRoutes().collect { entities ->
                val routes = entities.map { entity ->
                    val points = mutableListOf<RoutePoint>()
                    try {
                        val arr = org.json.JSONArray(entity.pointsJson)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            points.add(RoutePoint(obj.getDouble("lat"), obj.getDouble("lng")))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    com.suseoaa.locationspoofer.data.model.SavedRoute(entity.name, points).apply {
                        // 如果需要，动态附加 ID，或者直接使用名称进行删除
                        // 目前 MapScreen 的 showSavedRoutesDialog 使用 route.name
                    }
                }
                _uiState.update { it.copy(savedRoutes = routes) }
            }
        }
    }

    private suspend fun mergeLegacyRecords() {
        val allComplete = environmentDao.getAllCompleteLocations()
        if (allComplete.isEmpty()) return

        // 按近似坐标分组（四舍五入到4位小数，约11米）
        val grouped = allComplete.groupBy {
            Pair(
                String.format(java.util.Locale.US, "%.4f", it.location.lat),
                String.format(java.util.Locale.US, "%.4f", it.location.lng)
            )
        }.filter { it.value.size > 1 }

        for ((_, group) in grouped) {
            // 选取时间戳最新的记录作为主记录
            val primary = group.maxByOrNull { it.location.timestamp } ?: continue
            val others = group.filter { it.location.id != primary.location.id }

            for (other in others) {
                // 迁移已连接 Wi-Fi
                other.connectedWifi?.let {
                    environmentDao.insertConnectedWifi(it.copy(locationId = primary.location.id))
                }
                // 迁移周围 Wi-Fi 列表
                other.wifis.forEach { lw ->
                    environmentDao.insertLocationWifi(lw.locationWifi.copy(locationId = primary.location.id))
                }
                // 迁移周边蓝牙列表
                other.bluetooths.forEach { lb ->
                    environmentDao.insertLocationBluetooth(lb.locationBluetooth.copy(locationId = primary.location.id))
                }
                // 迁移基站列表
                other.cells.forEach { lc ->
                    environmentDao.insertLocationCell(lc.locationCell.copy(locationId = primary.location.id))
                }

                // 删除旧的独立记录
                environmentDao.deleteLocation(other.location.id)
            }
        }
    }

    fun updateLanguage(langCode: String) {
        settingsRepository.setLanguage(langCode)
        _uiState.update { it.copy(currentLanguage = langCode) }
    }

    fun setMapType(type: AppMapType) {
        settingsRepository.setMapType(type.name)
        _uiState.update { it.copy(mapType = type) }
    }

    fun setMapEngine(engine: MapEngine) {
        settingsRepository.setMapEngine(engine.name)
        _uiState.update { it.copy(mapEngine = engine) }
    }

    fun setSearchMode(mode: SearchMode) {
        _uiState.update { it.copy(searchMode = mode) }
    }

    data class ClusterData(
        val center: LocationRecord,
        var count: Int,
        var hasWifi: Boolean,
        var hasBluetooth: Boolean,
        var hasCell: Boolean
    )

    suspend fun performLocalSearch(): List<AppPoiItem> {
        val allRecords = environmentDao.getAllCompleteLocations()
        if (allRecords.isEmpty()) {
            return emptyList()
        }

        // 简单的聚类逻辑：按大约 150 米的距离进行分组
        val clusters = mutableListOf<ClusterData>()

        for (record in allRecords) {
            val loc = record.location
            val hasW = record.wifis.isNotEmpty()
            val hasB = record.bluetooths.isNotEmpty()
            val hasC = record.cells.isNotEmpty()

            var foundCluster = false
            for (i in clusters.indices) {
                val cluster = clusters[i]
                val dLat = Math.toRadians(cluster.center.lat - loc.lat)
                val dLng = Math.toRadians(cluster.center.lng - loc.lng)
                val a = kotlin.math.sin(dLat / 2).let { it * it } +
                        kotlin.math.cos(Math.toRadians(loc.lat)) *
                        kotlin.math.cos(Math.toRadians(cluster.center.lat)) *
                        kotlin.math.sin(dLng / 2).let { it * it }
                val distance =
                    2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

                if (distance <= 150.0) { // 150 米聚类半径
                    cluster.count += 1
                    cluster.hasWifi = cluster.hasWifi || hasW
                    cluster.hasBluetooth = cluster.hasBluetooth || hasB
                    cluster.hasCell = cluster.hasCell || hasC
                    foundCluster = true
                    break
                }
            }
            if (!foundCluster) {
                clusters.add(ClusterData(loc, 1, hasW, hasB, hasC))
            }
        }

        clusters.sortByDescending { it.count }

        return clusters.map { cluster ->
            val tags = mutableListOf<String>()
            if (cluster.hasWifi) tags.add("Wi-Fi")
            if (cluster.hasBluetooth) tags.add(context.getString(R.string.tag_bluetooth))
            if (cluster.hasCell) tags.add(context.getString(R.string.tag_cell))

            val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""

            val baseTitle = when {
                cluster.center.remark.isNotEmpty() -> cluster.center.remark
                cluster.center.placeName.isNotEmpty() -> cluster.center.placeName
                else -> context.getString(R.string.local_collected_hotspot)
            }

            val recordsSnippet = context.getString(R.string.contains_records_format, cluster.count)
            com.suseoaa.locationspoofer.ui.screen.AppPoiItem(
                title = "$baseTitle$tagStr",
                snippet = "$recordsSnippet (${
                    String.format(
                        "%.4f",
                        cluster.center.lat
                    )
                }, ${String.format("%.4f", cluster.center.lng)})",
                lat = cluster.center.lat,
                lng = cluster.center.lng
            )
        }
    }

    fun selectLanguage(languageCode: String) {
        settingsRepository.setLanguage(languageCode)
        settingsRepository.setLanguageSet(true)
        _uiState.update { it.copy(isLanguageSet = true, currentLanguage = languageCode) }
    }

    fun getSavedLanguage(): String = settingsRepository.getLanguage()

    fun setAltitude(altitude: String) {
        settingsRepository.altitude = altitude
        _uiState.update { it.copy(altitudeInput = altitude) }
    }

    fun setSatelliteCount(count: String) {
        settingsRepository.satelliteCount = count
        _uiState.update { it.copy(satelliteCountInput = count) }
    }

    // 当前位置获取

    fun isDomesticEnvironment(): Boolean = true

    fun fetchCurrentLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.Main) {
            val client = try {
                AMapLocationClient(ctx.applicationContext)
            } catch (e: Exception) {
                fallbackToNativeLocation(ctx, forceCallback, true)
                return@launch
            }
            client.setLocationOption(AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false // 禁用逆地理编码，防止因未开通Web服务导致 SERVICE_NOT_EXIST 鉴权错误
            })
            client.setLocationListener { loc ->
                if (loc != null && loc.errorCode == 0) {
                    if (_uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty() || forceCallback != null) {
                        _uiState.update {
                            it.copy(
                                latitudeInput = String.format("%.6f", loc.latitude),
                                longitudeInput = String.format("%.6f", loc.longitude),
                                showCoordinateError = false
                            )
                        }
                        forceCallback?.invoke(loc.latitude, loc.longitude)
                    }
                } else {
                    // 如果鉴权失败(如 SERVICE_NOT_EXIST)或其他错误，回退到原生定位
                    fallbackToNativeLocation(ctx, forceCallback, true)
                }
                client.stopLocation()
                client.onDestroy()
            }
            client.startLocation()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun fallbackToNativeLocation(
        ctx: Context,
        forceCallback: ((Double, Double) -> Unit)?,
        convertToGcj: Boolean
    ) {
        try {
            val locationManager =
                ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val provider =
                if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    android.location.LocationManager.NETWORK_PROVIDER
                } else {
                    android.location.LocationManager.GPS_PROVIDER
                }

            val lastLoc = locationManager.getLastKnownLocation(provider)
            if (lastLoc != null) {
                applyNativeLocation(ctx, lastLoc, forceCallback, convertToGcj)
            } else if (forceCallback != null) {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(com.suseoaa.locationspoofer.R.string.waiting_gps_signal),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    if (forceCallback != null) {
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(com.suseoaa.locationspoofer.R.string.native_location_success),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    applyNativeLocation(ctx, location, forceCallback, convertToGcj)
                    locationManager.removeUpdates(this)
                }

                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: android.os.Bundle?
                ) {
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager.requestSingleUpdate(
                provider,
                listener,
                android.os.Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            if (forceCallback != null) {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(com.suseoaa.locationspoofer.R.string.location_permission_denied),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyNativeLocation(
        ctx: Context,
        location: android.location.Location,
        forceCallback: ((Double, Double) -> Unit)?,
        convertToGcj: Boolean
    ) {
        var finalLat = location.latitude
        var finalLng = location.longitude

        if (convertToGcj) {
            val converter = com.amap.api.maps.CoordinateConverter(ctx).apply {
                from(com.amap.api.maps.CoordinateConverter.CoordType.GPS)
                coord(com.amap.api.maps.model.LatLng(location.latitude, location.longitude))
            }
            val gcj = converter.convert()
            finalLat = gcj.latitude
            finalLng = gcj.longitude
        }

        if (_uiState.value.longitudeInput.isEmpty() || _uiState.value.latitudeInput.isEmpty() || forceCallback != null) {
            _uiState.update {
                it.copy(
                    latitudeInput = String.format("%.6f", finalLat),
                    longitudeInput = String.format("%.6f", finalLng),
                    showCoordinateError = false
                )
            }
            forceCallback?.invoke(finalLat, finalLng)
        }
    }

    private suspend fun fetchRealLocationSilent(ctx: Context): Pair<Double, Double>? =
        suspendCoroutine { cont ->
            val client = try {
                com.amap.api.location.AMapLocationClient(ctx.applicationContext)
            } catch (e: Exception) {
                fallbackToNativeLocationSilent(ctx, true, cont)
                return@suspendCoroutine
            }
            client.setLocationOption(com.amap.api.location.AMapLocationClientOption().apply {
                locationMode =
                    com.amap.api.location.AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isNeedAddress = false
            })
            client.setLocationListener { loc ->
                if (loc != null && loc.errorCode == 0) {
                    cont.resume(Pair(loc.latitude, loc.longitude))
                } else {
                    fallbackToNativeLocationSilent(ctx, true, cont)
                }
                client.stopLocation()
                client.onDestroy()
            }
            client.startLocation()
        }

    @android.annotation.SuppressLint("MissingPermission")
    private fun fallbackToNativeLocationSilent(
        ctx: Context,
        convertToGcj: Boolean,
        cont: kotlin.coroutines.Continuation<Pair<Double, Double>?>
    ) {
        try {
            val locationManager =
                ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val provider =
                if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                    android.location.LocationManager.NETWORK_PROVIDER
                } else if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    android.location.LocationManager.GPS_PROVIDER
                } else {
                    cont.resume(null)
                    return
                }

            // 首先尝试最后已知位置
            val lastLoc = locationManager.getLastKnownLocation(provider)
            if (lastLoc != null) {
                val res = getNativeConverted(ctx, lastLoc, convertToGcj)
                cont.resume(res)
                return
            }

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    val res = getNativeConverted(ctx, location, convertToGcj)
                    cont.resume(res)
                    locationManager.removeUpdates(this)
                }

                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: android.os.Bundle?
                ) {
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            // 使用 main looper 处理 listener
            locationManager.requestSingleUpdate(
                provider,
                listener,
                android.os.Looper.getMainLooper()
            )

            // 5 秒后超时，以避免永远挂起
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                delay(5000)
                locationManager.removeUpdates(listener)
                if (cont.context.isActive) {
                    try {
                        cont.resume(null)
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            try {
                cont.resume(null)
            } catch (e: Exception) {
            }
        }
    }

    private fun getNativeConverted(
        ctx: Context,
        location: android.location.Location,
        convertToGcj: Boolean
    ): Pair<Double, Double> {
        var finalLat = location.latitude
        var finalLng = location.longitude
        if (convertToGcj) {
            val converter = com.amap.api.maps.CoordinateConverter(ctx).apply {
                from(com.amap.api.maps.CoordinateConverter.CoordType.GPS)
                coord(com.amap.api.maps.model.LatLng(location.latitude, location.longitude))
            }
            val gcj = converter.convert()
            finalLat = gcj.latitude
            finalLng = gcj.longitude
        }
        return Pair(finalLat, finalLng)
    }

    // 坐标输入

    fun updateLongitude(value: String) {
        if (isValidCoord(value)) {
            _uiState.update { it.copy(longitudeInput = value, showCoordinateError = false) }
            evaluateMockCapabilities()
        }
    }

    fun updateLatitude(value: String) {
        if (isValidCoord(value)) {
            _uiState.update { it.copy(latitudeInput = value, showCoordinateError = false) }
            evaluateMockCapabilities()
        }
    }

    private fun isValidCoord(value: String): Boolean {
        if (value.isEmpty() || value == "-") return true
        return value.toDoubleOrNull() != null
    }

    private fun evaluateMockCapabilities() {
        val state = _uiState.value
        val lat = state.latitudeInput.toDoubleOrNull()
        val lng = state.longitudeInput.toDoubleOrNull()

        if (lat == null || lng == null) {
            _uiState.update {
                it.copy(
                    canMockWifi = false,
                    canMockCell = false,
                    canMockBluetooth = false,
                    collectedWifiJson = "[]",
                    collectedCellJson = "[]",
                    collectedBluetoothJson = "[]",
                    wifiApCount = 0,
                    wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE
                )
            }
            return
        }

        viewModelScope.launch {
            evaluateMockCapabilitiesSuspend(lat, lng)
        }
    }

    private var pinnedLocationRecordId: Long? = null

    fun selectCollectedLocation(locationId: Long) {
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) {
                environmentDao.getCompleteLocationById(locationId)
            }
            if (record != null) {
                pinnedLocationRecordId = locationId
                val name = when {
                    record.location.remark.isNotBlank() -> record.location.remark
                    record.location.placeName.isNotBlank() -> record.location.placeName
                    else -> String.format(Locale.US, "(%.5f, %.5f)", record.location.lat, record.location.lng)
                }
                _uiState.update {
                    it.copy(
                        latitudeInput = record.location.lat.toString(),
                        longitudeInput = record.location.lng.toString(),
                        pinnedCollectedLocationId = locationId,
                        pinnedLocationName = name
                    )
                }
                evaluateMockCapabilitiesSuspend(record.location.lat, record.location.lng)
            }
        }
    }

    fun clearPinnedCollectedLocation() {
        pinnedLocationRecordId = null
        _uiState.update {
            it.copy(
                pinnedCollectedLocationId = null,
                pinnedLocationName = null
            )
        }
        evaluateMockCapabilities()
    }

    private fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) *
                kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLng / 2).let { it * it }
        return 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private suspend fun evaluateMockCapabilitiesSuspend(lat: Double, lng: Double) {
        val currentPinnedId = pinnedLocationRecordId
        var pinnedRecord: com.suseoaa.locationspoofer.data.db.CompleteLocation? = null

        if (currentPinnedId != null) {
            val pinned = withContext(Dispatchers.IO) {
                environmentDao.getCompleteLocationById(currentPinnedId)
            }
            if (pinned != null) {
                val distToPinned = calculateDistanceMeters(lat, lng, pinned.location.lat, pinned.location.lng)
                if (distToPinned <= 50.0) {
                    pinnedRecord = pinned
                } else {
                    // 超出 50 米有效范围，自动解除锁定
                    pinnedLocationRecordId = null
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                pinnedCollectedLocationId = null,
                                pinnedLocationName = null
                            )
                        }
                    }
                }
            } else {
                pinnedLocationRecordId = null
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            pinnedCollectedLocationId = null,
                            pinnedLocationName = null
                        )
                    }
                }
            }
        }

        val radLat = Math.toRadians(lat)
        val degLat = 65.0 / 111320.0
        val degLng = 65.0 / (111320.0 * maxOf(0.1, kotlin.math.cos(radLat)))

        val nearbyCandidates = withContext(Dispatchers.IO) {
            environmentDao.getCompleteLocationsInBounds(
                minLat = lat - degLat,
                maxLat = lat + degLat,
                minLng = lng - degLng,
                maxLng = lng + degLng,
                limit = 10
            )
        }

        val validRecords = mutableListOf<com.suseoaa.locationspoofer.data.db.CompleteLocation>()
        for (record in nearbyCandidates) {
            val dist = calculateDistanceMeters(lat, lng, record.location.lat, record.location.lng)
            if (dist <= 50.0) {
                validRecords.add(record)
            }
        }

        // 按与目标点距离从近到远排序
        validRecords.sortBy { calculateDistanceMeters(lat, lng, it.location.lat, it.location.lng) }

        // 若用户当前明确锁定了某个 50m 内的采集点，则将其置于首位最高优先级
        if (pinnedRecord != null) {
            validRecords.removeAll { it.location.id == pinnedRecord.location.id }
            validRecords.add(0, pinnedRecord)
        }

        withContext(Dispatchers.Main) {
            if (validRecords.isEmpty()) {
                _uiState.update {
                    it.copy(
                        canMockWifi = false,
                        canMockCell = false,
                        canMockBluetooth = false,
                        collectedWifiJson = "[]",
                        collectedCellJson = "[]",
                        collectedBluetoothJson = "[]",
                        wifiApCount = 0,
                        wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE
                    )
                }
            } else {
                val (wifiJson, cellJson, btJson) = locationToJson(validRecords, lat, lng)
                val hasW = try {
                    val obj = org.json.JSONObject(wifiJson)
                    val nearby = obj.optJSONArray("nearbyWifi")
                    val connected = obj.opt("connectedWifi")
                    (nearby != null && nearby.length() > 0) || (connected != null && !obj.isNull("connectedWifi"))
                } catch (e: Exception) {
                    false
                }
                val hasC = try {
                    val arr = org.json.JSONArray(cellJson)
                    arr.length() > 0
                } catch (e: Exception) {
                    false
                }
                val hasB = try {
                    val arr = org.json.JSONArray(btJson)
                    arr.length() > 0
                } catch (e: Exception) {
                    false
                }

                val wifiCount = parseWifiCount(wifiJson)

                _uiState.update {
                    it.copy(
                        canMockWifi = hasW,
                        canMockCell = hasC,
                        canMockBluetooth = hasB,
                        collectedWifiJson = wifiJson,
                        collectedCellJson = cellJson,
                        collectedBluetoothJson = btJson,
                        wifiApCount = wifiCount,
                        wifiLoadStatus = if (hasW) com.suseoaa.locationspoofer.data.model.WifiLoadStatus.DONE else com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE
                    )
                }
            }
        }
    }

    private suspend fun hasLocalWifiWithin50m(lat: Double, lng: Double): Boolean {
        val radLat = Math.toRadians(lat)
        val degLat = 65.0 / 111320.0
        val degLng = 65.0 / (111320.0 * maxOf(0.1, kotlin.math.cos(radLat)))

        val nearby = withContext(Dispatchers.IO) {
            environmentDao.getCompleteLocationsInBounds(
                minLat = lat - degLat,
                maxLat = lat + degLat,
                minLng = lng - degLng,
                maxLng = lng + degLng,
                limit = 5
            )
        }
        for (record in nearby) {
            if (record.wifis.isEmpty() && record.connectedWifi == null) continue
            val distance = calculateDistanceMeters(lat, lng, record.location.lat, record.location.lng)
            if (distance <= 50.0) {
                return true
            }
        }
        return false
    }

    private suspend fun hasLocalCellsWithin50m(lat: Double, lng: Double): Boolean {
        val radLat = Math.toRadians(lat)
        val degLat = 65.0 / 111320.0
        val degLng = 65.0 / (111320.0 * maxOf(0.1, kotlin.math.cos(radLat)))

        val nearby = withContext(Dispatchers.IO) {
            environmentDao.getCompleteLocationsInBounds(
                minLat = lat - degLat,
                maxLat = lat + degLat,
                minLng = lng - degLng,
                maxLng = lng + degLng,
                limit = 5
            )
        }
        for (record in nearby) {
            if (record.cells.isEmpty()) continue
            val distance = calculateDistanceMeters(lat, lng, record.location.lat, record.location.lng)
            if (distance <= 50.0) {
                return true
            }
        }
        return false
    }

    private suspend fun fetchWifiFromWigleSync(lat: Double, lng: Double) {
        val settingsToken = settingsRepository.getWigleApiToken()
        if (settingsToken.isBlank()) {
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE,
                        wifiApCount = 0,
                        canMockWifi = false
                    )
                }
            }
            return
        }

        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.LOADING) }
        }
        // 将坐标转换对齐至 WGS-84 标准以用于 WiGLE API
        val wgs84 = com.suseoaa.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
        val wgsLat = wgs84.lat
        val wgsLng = wgs84.lng

        try {
            val rawJsonArrayString = wifiRepository.fetchWifiData(wgsLat, wgsLng, settingsToken)
            val nearbyArr = org.json.JSONArray(rawJsonArrayString)
            if (nearbyArr.length() > 0) {
                val wifiObj = org.json.JSONObject()
                wifiObj.put("isConnected", true)

                val firstAp = nearbyArr.getJSONObject(0)
                val firstBssid = firstAp.optString("bssid")
                val firstSsid = firstAp.optString("ssid")

                val connObj = org.json.JSONObject().apply {
                    put("bssid", firstBssid)
                    put("ssid", firstSsid)
                    put(
                        "vendor",
                        com.suseoaa.locationspoofer.utils.MacVendorHelper.getVendor(firstBssid)
                    )
                    put("level", -45)
                    put("frequency", 2412)
                    put("channel", 1)
                    put("capabilities", "[WPA2-PSK-CCMP][ESS]")
                    put("macAddress", "02:00:00:00:00:00")
                    put("linkSpeed", 150)
                    put("networkId", 1)
                    put("wifiStandard", 4)
                }
                wifiObj.put("connectedWifi", connObj)

                val formattedNearby = org.json.JSONArray()
                for (i in 0 until nearbyArr.length()) {
                    val ap = nearbyArr.getJSONObject(i)
                    val bssid = ap.optString("bssid")
                    val ssid = ap.optString("ssid")
                    val level = -50 - (i * 2)
                    val freq = if (i % 2 == 0) 2412 else 5180

                    val itemObj = org.json.JSONObject().apply {
                        put("bssid", bssid)
                        put("ssid", ssid)
                        put(
                            "vendor",
                            com.suseoaa.locationspoofer.utils.MacVendorHelper.getVendor(bssid)
                        )
                        put("level", level)
                        put("capabilities", "[WPA2-PSK-CCMP][ESS]")
                        put("frequency", freq)
                        put(
                            "channel",
                            com.suseoaa.locationspoofer.utils.MacVendorHelper.frequencyToChannel(
                                freq
                            )
                        )
                    }
                    formattedNearby.put(itemObj)
                }
                wifiObj.put("nearbyWifi", formattedNearby)

                val formattedWifiJson = wifiObj.toString()

                withContext(Dispatchers.IO) {
                    saveEnvironmentData(lat, lng, formattedWifiJson, "[]", "[]")
                    // 更新 metadata 以指示 WiGLE 来源
                    val newestLocation = environmentDao.getAllLocations()
                        .firstOrNull { it.lat == lat && it.lng == lng }
                    if (newestLocation != null) {
                        val latLngPrefix = context.getString(R.string.lat_lng_format_prefix)
                        environmentDao.updateMetadata(
                            newestLocation.id,
                            lat,
                            lng,
                            context.getString(R.string.wigle_import),
                            "$latLngPrefix (${String.format("%.6f", lat)}, ${
                                String.format(
                                    "%.6f",
                                    lng
                                )
                            })",
                            null, null, null
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    evaluateMockCapabilities()
                    // 刷新记录统计总数
                    viewModelScope.launch(Dispatchers.IO) {
                        val count = environmentDao.getRecordCount()
                        _uiState.update { it.copy(environmentRecordCount = count) }
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE,
                            wifiApCount = 0,
                            canMockWifi = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE,
                        wifiApCount = 0,
                        canMockWifi = false
                    )
                }
            }
        }
    }

    private suspend fun fetchCellFromOpenCellIdSync(lat: Double, lng: Double) {
        val tokenToUse = settingsRepository.getOpencellidApiToken()
        if (tokenToUse.isBlank()) {
            return
        }
        val wgs84 = com.suseoaa.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
        val wgsLat = wgs84.lat
        val wgsLng = wgs84.lng

        try {
            val rawJsonArrayString = opencellidClient.fetchCellData(wgsLat, wgsLng, tokenToUse)
            val cellsArray = org.json.JSONArray(rawJsonArrayString)
            if (cellsArray.length() > 0) {
                val formattedCells = normalizeCellArrayForStorage(cellsArray)
                if (formattedCells.length() == 0) {
                    return
                }

                withContext(Dispatchers.IO) {
                    saveEnvironmentData(lat, lng, "{}", formattedCells.toString(), "[]")
                    val newestLocation = environmentDao.getAllLocations()
                        .firstOrNull { it.lat == lat && it.lng == lng }
                    if (newestLocation != null) {
                        val latLngPrefix = context.getString(R.string.lat_lng_format_prefix)
                        environmentDao.updateMetadata(
                            newestLocation.id,
                            lat,
                            lng,
                            context.getString(R.string.opencellid_import),
                            "$latLngPrefix (${String.format("%.6f", lat)}, ${
                                String.format(
                                    "%.6f",
                                    lng
                                )
                            })",
                            null, null, null
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    evaluateMockCapabilities()
                    // 刷新记录统计总数
                    viewModelScope.launch(Dispatchers.IO) {
                        val count = environmentDao.getRecordCount()
                        _uiState.update { it.copy(environmentRecordCount = count) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun normalizeCellArrayForStorage(cellsArray: org.json.JSONArray): org.json.JSONArray {
        val formattedCells = org.json.JSONArray()
        for (i in 0 until cellsArray.length()) {
            val cell = cellsArray.optJSONObject(i) ?: continue
            val area = cellArea(cell)
            val identity = cellIdentity(cell)
            if (area <= 0 || identity <= 0) {
                continue
            }

            val type = normalizeCellType(cell.optString("type", cell.optString("radio", "LTE")))
            val cellObj = org.json.JSONObject().apply {
                put("type", type)
                put("radio", cell.optString("radio", type))
                put("mcc", positiveCellInt(cell, "mcc", default = 460))
                put("mnc", positiveCellInt(cell, "mnc", "net", default = 0))
                put("tac", area)
                put("lac", area)
                put("ci", identity)
                put("cid", identity)
                put("cellid", identity)
                put(
                    "pci",
                    positiveCellInt(cell, "pci", default = (identity % 504).coerceIn(0, 503))
                )
                put("dbm", cellSignalDbm(cell, i))
                put("isRegistered", cell.optBoolean("isRegistered", i == 0))
            }
            formattedCells.put(cellObj)
        }
        return formattedCells
    }

    private fun normalizeCellType(rawType: String): String {
        return when (rawType.uppercase(java.util.Locale.US)) {
            "GSM" -> "GSM"
            "UMTS", "WCDMA" -> "WCDMA"
            "NR", "NR5G", "5G" -> "NR"
            else -> "LTE"
        }
    }

    private fun cellArea(cell: org.json.JSONObject): Int =
        positiveCellInt(cell, "tac", "lac", "area", default = 0)

    private fun cellIdentity(cell: org.json.JSONObject): Int =
        positiveCellInt(cell, "ci", "cid", "cellid", "cell", default = 0)

    private fun positiveCellInt(cell: org.json.JSONObject, vararg keys: String, default: Int): Int {
        for (key in keys) {
            if (!cell.has(key) || cell.isNull(key)) continue
            val value = cell.optInt(key, Int.MIN_VALUE)
            if (value > 0) return value
            val parsed = cell.optString(key).toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return default
    }

    private fun cellSignalDbm(cell: org.json.JSONObject, index: Int): Int {
        val direct = cell.optInt("dbm", Int.MIN_VALUE)
        if (direct in -140..-40) return direct
        val average = cell.optInt("averageSignalStrength", Int.MIN_VALUE)
        if (average in -140..-40) return average
        val signal = cell.optInt("signal", Int.MIN_VALUE)
        if (signal in -140..-40) return signal
        return (-70 - index * 3).coerceAtLeast(-110)
    }

    // 定点模拟

    @android.annotation.SuppressLint("MissingPermission")
    fun startSpoofing() {
        val state = _uiState.value

        if (state.isContinuousScanning) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.suseoaa.locationspoofer.R.string.disable_continuous_scan_first),
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

            // 稍作等待，确保 root shell 完全同步到磁盘
            kotlinx.coroutines.delay(200)

            _uiState.update {
                it.copy(isSpoofingActive = true, isSavingConfig = false)
            }
        }
    }

    fun stopSpoofing() {
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

    fun moveByJoystick(bearing: Double, intensity: Float, maxSpeedMs: Float) {
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
        SpoofingState.latitude = newLat
        SpoofingState.longitude = newLng
        SpoofingState.simBearing = bearing.toFloat()
        SpoofingState.startTimestamp = System.currentTimeMillis()
    }

    // 路线规划状态机

    /** 进入全屏地图，进入选点阶段 */
    fun enterRoutePlanning() {
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.SELECTING,
                routePoints = emptyList()
            )
        }
    }

    /** 地图中心确认添加路点 */
    fun addRoutePoint(lat: Double, lng: Double) {
        _uiState.update { it.copy(routePoints = it.routePoints + RoutePoint(lat, lng)) }
    }

    /** 撤销最后一个路点 */
    fun undoLastRoutePoint() {
        _uiState.update { state ->
            if (state.routePoints.isEmpty()) state
            else state.copy(routePoints = state.routePoints.dropLast(1))
        }
    }

    /** 结束选点 → READY */
    fun finishSelectingPoints() {
        if (_uiState.value.routePoints.size < 2) return
        _uiState.update { it.copy(routePlanStage = RoutePlanStage.READY) }
    }

    /** 重新选点：清空路点，回到 SELECTING */
    fun restartSelectingPoints() {
        _uiState.update {
            it.copy(
                routePoints = emptyList(),
                routePlanStage = RoutePlanStage.SELECTING
            )
        }
    }

    /** 设置路线运行模式 */
    fun setRouteRunMode(mode: RouteRunMode) {
        _uiState.update { it.copy(routeRunMode = mode) }
    }

    fun saveRoute(name: String, points: List<RoutePoint>) {
        viewModelScope.launch(Dispatchers.IO) {
            locationRepository.insertSavedRoute(name, points)
        }
    }

    fun deleteSavedRoute(route: com.suseoaa.locationspoofer.data.model.SavedRoute) {
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
    fun setRouteSimMode(mode: SimMode) {
        _uiState.update { it.copy(routeSimMode = mode) }
    }

    /** 设置自定义速度 (m/s) */
    fun setCustomSpeedMs(speed: Double) {
        _uiState.update { it.copy(customSpeedMs = speed.coerceIn(0.1, 100.0)) }
    }

    /** 获取实际生效的速度 (m/s) */
    private fun getEffectiveSpeedMs(): Double {
        val state = _uiState.value
        return if (state.routeSimMode == SimMode.CUSTOM) state.customSpeedMs
        else state.routeSimMode.speedMs
    }

    /** 首页地图确认选点 */
    fun confirmMapPoint(lat: Double, lng: Double, isDragging: Boolean = false) {
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
                    mockWifi = updatedState.mockWifi && updatedState.canMockWifi,
                    mockCell = updatedState.mockCell,
                    mockBluetooth = updatedState.mockBluetooth && updatedState.canMockBluetooth,
                    enableJitter = updatedState.enableJitter
                )
            }
        }
    }

    /** 清除地图选点状态 */

    fun setUseRealRoute(use: Boolean) {
        _uiState.update { it.copy(useRealRoute = use) }
    }

    fun setStopAtDestination(stop: Boolean) {
        _uiState.update { it.copy(stopAtDestination = stop) }
    }

    fun setEnableStepSimulation(enable: Boolean) {
        _uiState.update { it.copy(enableStepSimulation = enable) }
    }

    fun setStepCadenceSpm(spm: Int) {
        _uiState.update { it.copy(stepCadenceSpm = spm) }
    }

    fun setIsAutoCadence(auto: Boolean) {
        _uiState.update { it.copy(isAutoCadence = auto) }
    }

    /**
     * 开始路线模拟。
     * - 手动模式：启动 spoofing（STILL），由摇杆驱动 moveByJoystick 实时更新坐标。
     * - 循环模式：启动 spoofing，自动沿路线点按速度移动，到终点后反向循环。
     */
    fun startRoutePlanning() {
        val state = _uiState.value
        if (state.isContinuousScanning) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.suseoaa.locationspoofer.R.string.disable_continuous_scan_route_first),
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

    private fun fetchRealRouteAndStart(points: List<RoutePoint>, state: AppState) {
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

    private fun startSimulationWithPoints(pointsToRun: List<RoutePoint>, state: AppState) {
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
    fun cancelRoutePlanning() {
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.IDLE,
                routePoints = emptyList(),
                routeRunMode = RouteRunMode.LOOP
            )
        }
    }

    fun stopRoutePlanning() {
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

    fun saveCurrentLocation(name: String) {
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

    /**
     * 把一个采集点直接收藏起来（供"本地采集数据源"与"管理采集数据"两个页面调用）。
     *
     * 刻意不复用 evaluateMockCapabilitiesSuspend：那条路会顺带改写 uiState 里
     * 当前待模拟的环境数据，而"收藏某个点"不应该悄悄改变你接下来要模拟的内容。
     * 这里只用纯函数 locationToJson 做转换，无副作用。
     * addSavedLocation 已按 name+lat+lng 去重，重复收藏是覆盖而不是叠加。
     */
    fun saveCollectedLocationToFavorites(locationId: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) {
                environmentDao.getCompleteLocationById(locationId)
            }
            if (record == null) {
                onResult(null)
                return@launch
            }

            val lat = record.location.lat
            val lng = record.location.lng
            // 与 selectCollectedLocation 保持一致的命名回退
            val name = when {
                record.location.remark.isNotBlank() -> record.location.remark
                record.location.placeName.isNotBlank() -> record.location.placeName
                else -> String.format(Locale.US, "(%.5f, %.5f)", lat, lng)
            }

            val (wifiJson, cellJson, btJson) = locationToJson(listOf(record), lat, lng)
            settingsRepository.addSavedLocation(
                SavedLocation(name, lat, lng, wifiJson, cellJson, btJson)
            )
            _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
            onResult(name)
        }
    }

    private fun parseWifiCount(wifiJson: String?): Int {
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

    fun loadSavedLocation(loc: SavedLocation) {
        val wifiCount = parseWifiCount(loc.wifiJson)
        _uiState.update {
            it.copy(
                latitudeInput = String.format(java.util.Locale.US, "%.6f", loc.lat),
                longitudeInput = String.format(java.util.Locale.US, "%.6f", loc.lng),
                collectedWifiJson = loc.wifiJson,
                collectedCellJson = loc.cellJson,
                wifiApCount = wifiCount,
                wifiLoadStatus = if (wifiCount > 0) com.suseoaa.locationspoofer.data.model.WifiLoadStatus.DONE else com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE
            )
        }
    }

    fun removeSavedLocation(location: SavedLocation) {
        settingsRepository.removeSavedLocation(location)
        _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
    }

    fun addSavedRoute(name: String) {
        val points = _uiState.value.routePoints
        if (points.size >= 2) {
            settingsRepository.addSavedRoute(
                com.suseoaa.locationspoofer.data.model.SavedRoute(
                    name,
                    points
                )
            )
            _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
        }
    }

    fun removeSavedRoute(route: com.suseoaa.locationspoofer.data.model.SavedRoute) {
        settingsRepository.removeSavedRoute(route)
        _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
    }

    fun loadSavedRoute(route: com.suseoaa.locationspoofer.data.model.SavedRoute) {
        _uiState.update {
            it.copy(
                routePoints = route.points,
                routePlanStage = com.suseoaa.locationspoofer.data.model.RoutePlanStage.READY
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
    private fun startAutoRouteLoop() {
        autoRouteJob?.cancel()
        autoRouteJob = viewModelScope.launch(Dispatchers.Default) {
            val points = _uiState.value.routePoints
            if (points.size < 2) return@launch

            val isClosedLoop = haversineMeters(points.first(), points.last()) <= 5.0

            val speedMs = getEffectiveSpeedMs()
            if (speedMs <= 0.0) return@launch

            val tickMs = 100L
            val tickSec = tickMs / 1000.0
            var forward = true
            var segmentIndex = 0
            var progress = 0.0 // 当前段上已走过的距离（米）

            while (isActive) {
                val fromIdx = if (forward) segmentIndex else segmentIndex + 1
                val toIdx = if (forward) segmentIndex + 1 else segmentIndex
                val from = points[fromIdx]
                val to = points[toIdx]
                val segLen = haversineMeters(from, to)

                val stepDist = speedMs * tickSec
                progress += stepDist

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

    private var lastDbQueryLat: Double = 0.0
    private var lastDbQueryLng: Double = 0.0

    /** 更新当前模拟位置到 UI 和 SpoofingState */
    private fun updatePosition(lat: Double, lng: Double, bearing: Float) {
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
                _uiState.value.routePlanStage == com.suseoaa.locationspoofer.data.model.RoutePlanStage.RUNNING
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

    private fun haversineMeters(a: RoutePoint, b: RoutePoint): Double {
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

    private fun bearingBetween(from: RoutePoint, to: RoutePoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val x = kotlin.math.sin(dLng) * kotlin.math.cos(lat2)
        val y = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
                kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLng)
        return (Math.toDegrees(kotlin.math.atan2(x, y)) + 360) % 360
    }

    fun toggleMockWifi() {
        val newVal = !_uiState.value.mockWifi
        settingsRepository.mockWifi = newVal
        _uiState.update { it.copy(mockWifi = newVal) }
        syncMockSettings()
    }

    fun toggleMockCell() {
        val newVal = !_uiState.value.mockCell
        settingsRepository.mockCell = newVal
        _uiState.update { it.copy(mockCell = newVal) }
        syncMockSettings()
    }

    fun toggleMockBluetooth() {
        val newVal = !_uiState.value.mockBluetooth
        settingsRepository.mockBluetooth = newVal
        _uiState.update { it.copy(mockBluetooth = newVal) }
        syncMockSettings()
    }

    fun toggleEnableJitter() {
        val newVal = !_uiState.value.enableJitter
        settingsRepository.enableJitter = newVal
        _uiState.update { it.copy(enableJitter = newVal) }
        syncMockSettings()
    }

    private fun syncMockSettings() {
        if (_uiState.value.isSpoofingActive) {
            val state = _uiState.value
            val lat = state.latitudeInput.toDoubleOrNull() ?: return
            val lng = state.longitudeInput.toDoubleOrNull() ?: return
            viewModelScope.launch {
                locationRepository.updateConfig(
                    lat = lat,
                    lng = lng,
                    simMode = if (state.routePlanStage == com.suseoaa.locationspoofer.data.model.RoutePlanStage.RUNNING) state.routeSimMode.name else "STILL",
                    simBearing = state.simBearing,
                    startTime = SpoofingState.startTimestamp,
                    routePoints = state.routePoints,
                    isRouteMode = state.routePlanStage == com.suseoaa.locationspoofer.data.model.RoutePlanStage.RUNNING,
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


    fun toggleContinuousScanning() {
        if (_uiState.value.isSpoofingActive) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.suseoaa.locationspoofer.R.string.disable_continuous_scan_route_first),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (_uiState.value.isSpoofingActive) {
            android.widget.Toast.makeText(
                context,
                context.getString(com.suseoaa.locationspoofer.R.string.stop_spoofing_before_scan),
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

                        saveEnvironmentData(lat, lng, wifiJson, cellJson, bluetoothJson)

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

    fun refreshRecordCount() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = environmentDao.getRecordCount()
            _uiState.update { it.copy(environmentRecordCount = count) }
        }
    }

    suspend fun getAllLocations(): List<com.suseoaa.locationspoofer.data.db.LocationRecord> {
        return environmentDao.getAllLocations()
    }

    fun onManageDataChanged() {
        refreshRecordCount()
        evaluateMockCapabilities()
    }

    fun setAmapApiKey(key: String) {
        settingsRepository.setAmapApiKey(key)
        _uiState.update { it.copy(amapApiKey = key) }
    }

    fun setBaiduApiKey(key: String) {
        settingsRepository.setBaiduApiKey(key)
        _uiState.update { it.copy(baiduApiKey = key) }
    }

    fun setGoogleApiKey(key: String) {
        settingsRepository.setGoogleApiKey(key)
        _uiState.update { it.copy(googleApiKey = key) }
    }

    fun setWigleApiToken(token: String) {
        settingsRepository.setWigleApiToken(token)
        _uiState.update { it.copy(wigleToken = token) }
    }

    fun setOpencellidApiToken(token: String) {
        settingsRepository.setOpencellidApiToken(token)
        _uiState.update { it.copy(opencellidToken = token) }
    }

    @Suppress("DEPRECATION")
    private fun getAppSignatureSHA1(): String {
        try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNATURES
            )
            val signatures = info.signatures ?: return "Unknown"
            if (signatures.isEmpty()) return "Unknown"
            val cert = signatures[0].toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA1")
            val publicKey = md.digest(cert)
            val hexString = StringBuilder()
            for (b in publicKey) {
                val appendString = Integer.toHexString(0xFF and b.toInt())
                if (appendString.length == 1) hexString.append("0")
                hexString.append(appendString)
                hexString.append(":")
            }
            return hexString.toString().dropLast(1).uppercase()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown"
    }

    fun setAppCoordinateSystem(pkg: String, sys: String) {
        val currentMap = _uiState.value.appCoordinateSystems.toMutableMap()
        currentMap[pkg] = sys
        settingsRepository.setAppCoordinateSystems(currentMap)
        _uiState.update { it.copy(appCoordinateSystems = currentMap) }

        // 如果模拟处于开启状态，则更新配置
        if (_uiState.value.isSpoofingActive) {
            viewModelScope.launch {
                locationRepository.updateConfig(
                    SpoofingState.latitude,
                    SpoofingState.longitude,
                    SpoofingState.simMode,
                    SpoofingState.simBearing,
                    SpoofingState.startTimestamp,
                    if (SpoofingState.isRouteMode) parseRoutePoints(SpoofingState.routeJson) else emptyList(),
                    SpoofingState.isRouteMode,
                    currentMap
                )
            }
        }
    }

    fun removeAppCoordinateSystem(pkg: String) {
        val currentMap = _uiState.value.appCoordinateSystems.toMutableMap()
        currentMap.remove(pkg)
        settingsRepository.setAppCoordinateSystems(currentMap)
        _uiState.update { it.copy(appCoordinateSystems = currentMap) }

        if (_uiState.value.isSpoofingActive) {
            viewModelScope.launch {
                locationRepository.updateConfig(
                    SpoofingState.latitude,
                    SpoofingState.longitude,
                    SpoofingState.simMode,
                    SpoofingState.simBearing,
                    SpoofingState.startTimestamp,
                    if (SpoofingState.isRouteMode) parseRoutePoints(SpoofingState.routeJson) else emptyList(),
                    SpoofingState.isRouteMode,
                    currentMap
                )
            }
        }
    }

    private fun parseRoutePoints(json: String): List<RoutePoint> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                RoutePoint(obj.getDouble("lat"), obj.getDouble("lng"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun saveEnvironmentData(
        lat: Double,
        lng: Double,
        wifiJson: String,
        cellJson: String,
        bluetoothJson: String
    ) {
        val existingLocation = environmentDao.findLocationByCoordinates(lat, lng)
        val locId = if (existingLocation != null) {
            val updated = existingLocation.copy(timestamp = System.currentTimeMillis())
            environmentDao.insertLocation(updated)
            updated.id
        } else {
            environmentDao.insertLocation(
                com.suseoaa.locationspoofer.data.db.LocationRecord(
                    lat = lat,
                    lng = lng
                )
            )
        }

        try {
            val wifiObj = org.json.JSONObject(wifiJson)
            val isConnected = wifiObj.optBoolean("isConnected", false)
            if (isConnected && wifiObj.has("connectedWifi")) {
                val conn = wifiObj.getJSONObject("connectedWifi")
                val connWifi = com.suseoaa.locationspoofer.data.db.LocationConnectedWifi(
                    locationId = locId,
                    bssid = conn.optString("bssid"),
                    ssid = conn.optString("ssid"),
                    vendor = conn.optString("vendor"),
                    macAddress = conn.optString("macAddress"),
                    frequency = conn.optInt("frequency"),
                    linkSpeed = conn.optInt("linkSpeed"),
                    level = conn.optInt("level"),
                    capabilities = conn.optString("capabilities"),
                    networkId = conn.optInt("networkId"),
                    wifiStandard = conn.optInt("wifiStandard")
                )
                environmentDao.insertConnectedWifi(connWifi)
            }

            val nearbyArr = wifiObj.optJSONArray("nearbyWifi")
            if (nearbyArr != null) {
                for (i in 0 until nearbyArr.length()) {
                    val obj = nearbyArr.getJSONObject(i)
                    val bssid = obj.optString("bssid")
                    if (bssid.isEmpty()) continue
                    environmentDao.insertWifiDevice(
                        com.suseoaa.locationspoofer.data.db.WifiDevice(
                            bssid = bssid,
                            ssid = obj.optString("ssid", ""),
                            frequency = obj.optInt("frequency", 0),
                            capabilities = obj.optString("capabilities", ""),
                            vendor = obj.optString("vendor", "")
                        )
                    )
                    environmentDao.insertLocationWifi(
                        com.suseoaa.locationspoofer.data.db.LocationWifi(
                            locationId = locId,
                            bssid = bssid,
                            level = obj.optInt("level", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val cellArr = org.json.JSONArray(cellJson)
            for (i in 0 until cellArr.length()) {
                val obj = cellArr.getJSONObject(i)
                val type =
                    normalizeCellType(obj.optString("type", obj.optString("radio", "UNKNOWN")))
                val area = cellArea(obj)
                val identity = cellIdentity(obj)
                val tac = when (type) {
                    "LTE", "NR" -> area
                    else -> positiveCellInt(obj, "tac", default = 0)
                }
                val lac = when (type) {
                    "GSM", "WCDMA" -> area
                    else -> positiveCellInt(obj, "lac", default = 0)
                }
                val ci = when (type) {
                    "LTE" -> identity
                    else -> positiveCellInt(obj, "ci", default = 0)
                }
                val cid = when (type) {
                    "GSM", "WCDMA" -> identity
                    else -> positiveCellInt(obj, "cid", default = 0)
                }
                val nci = if (type == "NR") {
                    obj.optLong("nci", identity.toLong()).takeIf { it > 0L } ?: identity.toLong()
                } else {
                    obj.optLong("nci", 0L)
                }
                val basestationId = if (type == "CDMA") {
                    positiveCellInt(obj, "basestationId", "cellid", "cell", default = 0)
                } else {
                    positiveCellInt(obj, "basestationId", default = 0)
                }
                if (area <= 0 && identity <= 0 && basestationId <= 0) continue
                val cellKey = "${type}_${positiveCellInt(obj, "mcc", default = 460)}_${
                    positiveCellInt(
                        obj,
                        "mnc",
                        "net",
                        default = 0
                    )
                }_${tac}_${ci}_${cid}_${basestationId}_${nci}"
                val device = com.suseoaa.locationspoofer.data.db.CellDevice(
                    cellKey = cellKey, type = type,
                    mcc = positiveCellInt(obj, "mcc", default = 460),
                    mnc = positiveCellInt(obj, "mnc", "net", default = 0),
                    tac = tac, ci = ci,
                    pci = positiveCellInt(
                        obj,
                        "pci",
                        default = if (identity > 0) (identity % 504).coerceIn(0, 503) else 0
                    ),
                    lac = lac, cid = cid,
                    psc = positiveCellInt(obj, "psc", default = 0),
                    nci = nci,
                    networkId = positiveCellInt(obj, "networkId", default = 0),
                    systemId = positiveCellInt(obj, "systemId", default = 0),
                    basestationId = basestationId
                )
                environmentDao.insertCellDevice(device)
                environmentDao.insertLocationCell(
                    com.suseoaa.locationspoofer.data.db.LocationCell(
                        locId,
                        cellKey,
                        cellSignalDbm(obj, i),
                        obj.optBoolean("isRegistered", i == 0)
                    )
                )
            }
        } catch (e: Exception) {
        }

        try {
            val btArr = org.json.JSONArray(bluetoothJson)
            for (i in 0 until btArr.length()) {
                val obj = btArr.getJSONObject(i)
                val address = obj.optString("address")
                if (address.isEmpty()) continue
                environmentDao.insertBluetoothDevice(
                    com.suseoaa.locationspoofer.data.db.BluetoothDevice(
                        address,
                        obj.optString("name", ""),
                        obj.optString("scanRecordHex", "")
                    )
                )
                environmentDao.insertLocationBluetooth(
                    com.suseoaa.locationspoofer.data.db.LocationBluetooth(
                        locId,
                        address,
                        obj.optInt("rssi", -60)
                    )
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun locationToJson(
        records: List<com.suseoaa.locationspoofer.data.db.CompleteLocation>,
        targetLat: Double,
        targetLng: Double
    ): Triple<String, String, String> {
        if (records.isEmpty()) return Triple("{}", "[]", "[]")

        val weights = records.mapIndexed { i, it ->
            if (i == 0 && (it.location.id == pinnedLocationRecordId || it.location.selectedWifiBssid != null)) {
                1000.0
            } else {
                val rLat = Math.toRadians(it.location.lat - targetLat)
                val rLng = Math.toRadians(it.location.lng - targetLng)
                val rA = kotlin.math.sin(rLat / 2).let { v -> v * v } + kotlin.math.cos(
                    Math.toRadians(targetLat)
                ) * kotlin.math.cos(Math.toRadians(it.location.lat)) * kotlin.math.sin(rLng / 2)
                    .let { v -> v * v }
                val dist =
                    2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(rA), kotlin.math.sqrt(1 - rA))
                val safeDist = kotlin.math.max(dist, 1.0)
                1.0 / (safeDist * safeDist)
            }
        }

        val closestRecord = records.firstOrNull()
        val explicitWifiBssid = closestRecord?.location?.selectedWifiBssid
        val explicitWifi =
            if (explicitWifiBssid != null && explicitWifiBssid != "__NONE__") {
                closestRecord.wifis.find { it.device.bssid.equals(explicitWifiBssid, ignoreCase = true) }
            } else null

        val connectedObj = if (explicitWifiBssid == "__NONE__") {
            null
        } else if (explicitWifi != null) {
            org.json.JSONObject().apply {
                put("ssid", explicitWifi.device.ssid)
                put("bssid", explicitWifi.device.bssid)
                put("vendor", explicitWifi.device.vendor)
                put("macAddress", explicitWifi.device.bssid)
                put("frequency", explicitWifi.device.frequency)
                put(
                    "channel",
                    com.suseoaa.locationspoofer.utils.MacVendorHelper.frequencyToChannel(
                        explicitWifi.device.frequency
                    )
                )
                put("linkSpeed", 65)
                put("level", explicitWifi.locationWifi.level)
                put("capabilities", explicitWifi.device.capabilities)
                put("networkId", 1)
                put("wifiStandard", 6)
            }
        } else if (closestRecord?.connectedWifi != null && (explicitWifiBssid == null || explicitWifiBssid.equals(closestRecord.connectedWifi.bssid, ignoreCase = true))) {
            val cw = closestRecord.connectedWifi
            org.json.JSONObject().apply {
                put("ssid", cw.ssid)
                put("bssid", cw.bssid)
                put("vendor", cw.vendor)
                put("macAddress", cw.macAddress)
                put("frequency", cw.frequency)
                put(
                    "channel",
                    com.suseoaa.locationspoofer.utils.MacVendorHelper.frequencyToChannel(cw.frequency)
                )
                put("linkSpeed", cw.linkSpeed)
                put("level", cw.level)
                put("capabilities", cw.capabilities)
                put("networkId", cw.networkId)
                put("wifiStandard", cw.wifiStandard)
            }
        } else {
            null
        }

        // 2. 插值附近的 Wi-Fi
        val wifiMap = mutableMapOf<String, com.suseoaa.locationspoofer.data.db.LocationWithWifi>()
        val wifiLevels = mutableMapOf<String, Double>()
        val wifiWeights = mutableMapOf<String, Double>()

        records.forEachIndexed { i, rec ->
            rec.wifis.forEach { rw ->
                val bssid = rw.device.bssid
                if (!wifiMap.containsKey(bssid)) wifiMap[bssid] = rw
                wifiLevels[bssid] = (wifiLevels[bssid] ?: 0.0) + rw.locationWifi.level * weights[i]
                wifiWeights[bssid] = (wifiWeights[bssid] ?: 0.0) + weights[i]
            }
        }

        val nearbyArr = org.json.JSONArray()
        wifiMap.forEach { (bssid, rw) ->
            val w = wifiWeights[bssid]!!
            val interpolatedLevel = (wifiLevels[bssid]!! / w).toInt()
            val obj = org.json.JSONObject().apply {
                put("bssid", bssid)
                put("ssid", rw.device.ssid)
                put("vendor", rw.device.vendor)
                put("frequency", rw.device.frequency)
                put(
                    "channel",
                    com.suseoaa.locationspoofer.utils.MacVendorHelper.frequencyToChannel(rw.device.frequency)
                )
                put("capabilities", rw.device.capabilities)
                put("level", interpolatedLevel)
            }
            nearbyArr.put(obj)
        }

        val hasConnected = connectedObj != null
        val wifiResultObj = org.json.JSONObject().apply {
            put("isConnected", hasConnected)
            put("connectedWifi", connectedObj ?: org.json.JSONObject.NULL)
            put("nearbyWifi", nearbyArr)
        }
        val wifiArr = wifiResultObj // 根据需要赋值以匹配其余的方法变量，或者直接返回 wifiResultObj.toString()


        val cellMap = mutableMapOf<String, com.suseoaa.locationspoofer.data.db.LocationWithCell>()
        val cellDbms = mutableMapOf<String, Double>()
        val cellWeights = mutableMapOf<String, Double>()

        records.forEachIndexed { i, rec ->
            rec.cells.forEach { rc ->
                val cellKey = rc.device.cellKey
                if (!cellMap.containsKey(cellKey)) cellMap[cellKey] = rc
                cellDbms[cellKey] = (cellDbms[cellKey] ?: 0.0) + rc.locationCell.dbm * weights[i]
                cellWeights[cellKey] = (cellWeights[cellKey] ?: 0.0) + weights[i]
            }
        }

        val explicitCellKey = closestRecord?.location?.selectedCellKey
        val cellArr = org.json.JSONArray()
        val cellList = mutableListOf<org.json.JSONObject>()
        cellMap.forEach { (cellKey, rc) ->
            val w = cellWeights[cellKey]!!
            val interpolatedDbm = (cellDbms[cellKey]!! / w).toInt()
            val obj = org.json.JSONObject()
            obj.put("type", rc.device.type)
            obj.put("mcc", rc.device.mcc)
            obj.put("mnc", rc.device.mnc)
            obj.put("tac", rc.device.tac)
            obj.put("ci", rc.device.ci)
            obj.put("pci", rc.device.pci)
            obj.put("lac", rc.device.lac)
            obj.put("cid", rc.device.cid)
            obj.put("psc", rc.device.psc)
            obj.put("nci", rc.device.nci)
            obj.put("networkId", rc.device.networkId)
            obj.put("systemId", rc.device.systemId)
            obj.put("basestationId", rc.device.basestationId)
            obj.put("dbm", interpolatedDbm)
            val isReg =
                if (explicitCellKey != null) cellKey.equals(explicitCellKey, ignoreCase = true) else rc.locationCell.isRegistered
            obj.put("isRegistered", isReg)
            if (isReg) {
                cellList.add(0, obj)
            } else {
                cellList.add(obj)
            }
        }
        cellList.forEach { cellArr.put(it) }

        val btMap =
            mutableMapOf<String, com.suseoaa.locationspoofer.data.db.LocationWithBluetooth>()
        val btRssis = mutableMapOf<String, Double>()
        val btWeights = mutableMapOf<String, Double>()

        records.forEachIndexed { i, rec ->
            rec.bluetooths.forEach { rb ->
                val address = rb.device.address
                if (!btMap.containsKey(address)) btMap[address] = rb
                btRssis[address] =
                    (btRssis[address] ?: 0.0) + rb.locationBluetooth.rssi * weights[i]
                btWeights[address] = (btWeights[address] ?: 0.0) + weights[i]
            }
        }

        val explicitBtAddress = closestRecord?.location?.selectedBluetoothAddress
        val btArr = org.json.JSONArray()
        val btList = mutableListOf<org.json.JSONObject>()
        btMap.forEach { (address, rb) ->
            val w = btWeights[address]!!
            val interpolatedRssi = (btRssis[address]!! / w).toInt()
            val obj = org.json.JSONObject()
            obj.put("address", address)
            obj.put("name", rb.device.name)
            obj.put("scanRecordHex", rb.device.scanRecordHex)
            obj.put("rssi", interpolatedRssi)
            val isSelectedBt = explicitBtAddress != null && explicitBtAddress.equals(address, ignoreCase = true)
            if (isSelectedBt) {
                obj.put("isConnected", true)
                btList.add(0, obj)
            } else {
                btList.add(obj)
            }
        }
        btList.forEach { btArr.put(it) }

        return Triple(wifiArr.toString(), cellArr.toString(), btArr.toString())
    }

    /** 供导出对话框展示"当前设备上每个分类各有多少条" */
    fun collectExportCounts(onResult: (ImportExportCounts) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val counts = try {
                ImportExportCounts(
                    locations = environmentDao.getAllCompleteLocations().size,
                    savedLocations = settingsRepository.getSavedLocations().size,
                    savedRoutes = locationRepository.getAllSavedRoutesList().size,
                    appCoordinateSystems = settingsRepository.getAppCoordinateSystems().size,
                    settings = 1,
                    apiKeys = listOf(
                        settingsRepository.getAmapApiKey(),
                        settingsRepository.getBaiduApiKey(),
                        settingsRepository.getGoogleApiKey(),
                        settingsRepository.getWigleApiToken(),
                        settingsRepository.getOpencellidApiToken()
                    ).count { it.isNotBlank() }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                ImportExportCounts()
            }
            launch(Dispatchers.Main) { onResult(counts) }
        }
    }

    fun exportEnvironmentData(
        uri: android.net.Uri,
        selection: ImportExportSelection = ImportExportSelection(),
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 未勾选的分类直接留空/留 null，不写进文件
                val locations = if (selection.locations) environmentDao.getAllCompleteLocations() else emptyList()
                val savedLocations = if (selection.savedLocations) settingsRepository.getSavedLocations() else emptyList()
                val savedRoutes = if (selection.savedRoutes) locationRepository.getAllSavedRoutesList() else emptyList()
                val appCoordinateSystems =
                    if (selection.appCoordinateSystems) settingsRepository.getAppCoordinateSystems() else emptyMap()
                val settings = if (selection.settings) {
                    com.suseoaa.locationspoofer.data.db.ExportedSettings(
                        mockWifi = settingsRepository.mockWifi,
                        mockCell = settingsRepository.mockCell,
                        mockBluetooth = settingsRepository.mockBluetooth,
                        enableJitter = settingsRepository.enableJitter,
                        altitude = settingsRepository.altitude,
                        satelliteCount = settingsRepository.satelliteCount,
                        mapType = settingsRepository.getMapType(),
                        mapEngine = settingsRepository.getMapEngine()
                    )
                } else null
                val apiKeys = if (selection.apiKeys) {
                    com.suseoaa.locationspoofer.data.db.ExportedApiKeys(
                        amapApiKey = settingsRepository.getAmapApiKey(),
                        baiduApiKey = settingsRepository.getBaiduApiKey(),
                        googleApiKey = settingsRepository.getGoogleApiKey(),
                        wigleApiToken = settingsRepository.getWigleApiToken(),
                        opencellidApiToken = settingsRepository.getOpencellidApiToken()
                    )
                } else null

                val dataPackage = com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage(
                    version = 3,
                    exportTimestamp = System.currentTimeMillis(),
                    appVersion = "2.0.0",
                    locations = locations,
                    savedLocations = savedLocations,
                    savedRoutes = savedRoutes,
                    appCoordinateSystems = appCoordinateSystems,
                    settings = settings,
                    apiKeys = apiKeys
                )

                val json = kotlinx.serialization.json.Json {
                    prettyPrint = true
                    encodeDefaults = true
                    ignoreUnknownKeys = true
                }
                val jsonStr = json.encodeToString(dataPackage)

                // 优先用 "wt"（write + truncate）而不是默认的 "w"：
                // SAF 的 "w" 对多数 DocumentsProvider 不会截断原文件，当新内容比旧内容短时，
                // 旧文件的尾巴会残留在后面，导出的 JSON 直接损坏。
                // 但少数 OEM 的 DocumentsProvider 不认 "t" 标志会直接抛异常，
                // 所以失败时降级回 "w"，保证导出至少能成功而不是彻底不可用。
                val bytes = jsonStr.toByteArray(Charsets.UTF_8)
                val written = try {
                    context.contentResolver.openOutputStream(uri, "wt")
                } catch (e: Exception) {
                    e.printStackTrace()
                    context.contentResolver.openOutputStream(uri)
                }?.use { outputStream ->
                    outputStream.write(bytes)
                    outputStream.flush()
                    true
                } ?: false

                launch(Dispatchers.Main) { onResult(written) }
            } catch (e: Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    /**
     * 兼容入口：解析并全量导入（API 密钥除外，避免静默覆盖掉用户自己的密钥）。
     * 需要按分类选择时走 parseImportPackage + applyImportPackage。
     */
    fun importEnvironmentData(uri: android.net.Uri, onComplete: () -> Unit) {
        parseImportPackage(uri) { pkg ->
            if (pkg == null) onComplete()
            else applyImportPackage(pkg, ImportExportSelection(), onComplete)
        }
    }

    /** 只解析不落库：先让用户看清文件里有什么，再决定导入哪些分类 */
    fun parseImportPackage(
        uri: android.net.Uri,
        onParsed: (com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val parsed = try {
                parseImportPackageInternal(uri)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            launch(Dispatchers.Main) { onParsed(parsed) }
        }
    }

    private fun parseImportPackageInternal(
        uri: android.net.Uri
    ): com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage? {
        val jsonStr = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        } ?: return null

        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }

        // 1. 优先按标准数据包格式解析（version 2 的文件没有 settings/apiKeys 字段，会落到默认 null）
        val pkg = try {
            json.decodeFromString<com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage>(jsonStr)
        } catch (e: Exception) {
            null
        }
        val pkgHasContent = pkg != null && (
                pkg.locations.isNotEmpty() || pkg.savedLocations.isNotEmpty() ||
                        pkg.savedRoutes.isNotEmpty() || pkg.appCoordinateSystems.isNotEmpty() ||
                        pkg.settings != null || pkg.apiKeys != null
                )
        if (pkgHasContent) return pkg

        // 2. 兼容旧版本历史 JSON 导出格式 (List<CompleteLocation> 或 List<SavedLocation>)
        try {
            val legacyLocations =
                json.decodeFromString<List<com.suseoaa.locationspoofer.data.db.CompleteLocation>>(jsonStr)
            if (legacyLocations.isNotEmpty()) {
                return com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage(locations = legacyLocations)
            }
        } catch (e: Exception) {
            try {
                val legacySaved = json.decodeFromString<List<SavedLocation>>(jsonStr)
                if (legacySaved.isNotEmpty()) {
                    return com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage(savedLocations = legacySaved)
                }
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }

        return pkg
    }

    /** 按用户勾选的分类落库，各分类的合并语义与此前保持一致 */
    fun applyImportPackage(
        pkg: com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage,
        selection: ImportExportSelection,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 安全导入环境定位点 (通过 copy(id = 0) 消除主键冲突，并正确绑定关联设备外键)
                if (selection.locations) {
                    pkg.locations.forEach { cl ->
                        val locId = environmentDao.insertLocation(cl.location.copy(id = 0))
                        cl.connectedWifi?.let { cw ->
                            environmentDao.insertConnectedWifi(cw.copy(locationId = locId))
                        }
                        cl.wifis.forEach { w ->
                            environmentDao.insertWifiDevice(w.device)
                            environmentDao.insertLocationWifi(w.locationWifi.copy(locationId = locId))
                        }
                        cl.cells.forEach { c ->
                            environmentDao.insertCellDevice(c.device)
                            environmentDao.insertLocationCell(c.locationCell.copy(locationId = locId))
                        }
                        cl.bluetooths.forEach { b ->
                            environmentDao.insertBluetoothDevice(b.device)
                            environmentDao.insertLocationBluetooth(b.locationBluetooth.copy(locationId = locId))
                        }
                    }
                }

                // 合并收藏点位 (避免重复点位)
                if (selection.savedLocations && pkg.savedLocations.isNotEmpty()) {
                    val currentSaved = settingsRepository.getSavedLocations().toMutableList()
                    pkg.savedLocations.forEach { loc ->
                        if (currentSaved.none { it.name == loc.name && it.lat == loc.lat && it.lng == loc.lng }) {
                            currentSaved.add(loc)
                        }
                    }
                    settingsRepository.setSavedLocations(currentSaved)
                }

                // 合并保存的路线
                if (selection.savedRoutes && pkg.savedRoutes.isNotEmpty()) {
                    pkg.savedRoutes.forEach { route ->
                        locationRepository.insertSavedRouteEntity(route.copy(id = 0))
                    }
                }

                // 合并应用坐标系配置
                if (selection.appCoordinateSystems && pkg.appCoordinateSystems.isNotEmpty()) {
                    val currentCoords = settingsRepository.getAppCoordinateSystems().toMutableMap()
                    currentCoords.putAll(pkg.appCoordinateSystems)
                    settingsRepository.setAppCoordinateSystems(currentCoords)
                }

                // 软件设置与 API 密钥都是标量配置，没有合并语义，导入即覆盖
                if (selection.settings) {
                    pkg.settings?.let { s ->
                        settingsRepository.mockWifi = s.mockWifi
                        settingsRepository.mockCell = s.mockCell
                        settingsRepository.mockBluetooth = s.mockBluetooth
                        settingsRepository.enableJitter = s.enableJitter
                        if (s.altitude.isNotBlank()) settingsRepository.altitude = s.altitude
                        if (s.satelliteCount.isNotBlank()) settingsRepository.satelliteCount = s.satelliteCount
                        if (s.mapType.isNotBlank()) settingsRepository.setMapType(s.mapType)
                        if (s.mapEngine.isNotBlank()) settingsRepository.setMapEngine(s.mapEngine)
                    }
                }
                if (selection.apiKeys) {
                    pkg.apiKeys?.let { k ->
                        if (k.amapApiKey.isNotBlank()) settingsRepository.setAmapApiKey(k.amapApiKey)
                        if (k.baiduApiKey.isNotBlank()) settingsRepository.setBaiduApiKey(k.baiduApiKey)
                        if (k.googleApiKey.isNotBlank()) settingsRepository.setGoogleApiKey(k.googleApiKey)
                        if (k.wigleApiToken.isNotBlank()) settingsRepository.setWigleApiToken(k.wigleApiToken)
                        if (k.opencellidApiToken.isNotBlank()) {
                            settingsRepository.setOpencellidApiToken(k.opencellidApiToken)
                        }
                    }
                }

                val count = environmentDao.getRecordCount()
                val updatedSaved = settingsRepository.getSavedLocations()
                val updatedCoords = settingsRepository.getAppCoordinateSystems()

                _uiState.update {
                    it.copy(
                        environmentRecordCount = count,
                        savedLocations = updatedSaved,
                        appCoordinateSystems = updatedCoords,
                        mockWifi = settingsRepository.mockWifi,
                        mockCell = settingsRepository.mockCell,
                        mockBluetooth = settingsRepository.mockBluetooth,
                        enableJitter = settingsRepository.enableJitter,
                        altitudeInput = settingsRepository.altitude.ifBlank { it.altitudeInput },
                        satelliteCountInput = settingsRepository.satelliteCount.ifBlank { it.satelliteCountInput },
                        amapApiKey = settingsRepository.getAmapApiKey(),
                        baiduApiKey = settingsRepository.getBaiduApiKey(),
                        googleApiKey = settingsRepository.getGoogleApiKey(),
                        mapType = try {
                            AppMapType.valueOf(settingsRepository.getMapType())
                        } catch (e: Exception) {
                            it.mapType
                        },
                        mapEngine = try {
                            MapEngine.valueOf(settingsRepository.getMapEngine())
                        } catch (e: Exception) {
                            it.mapEngine
                        }
                    )
                }

                launch(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) { onComplete() }
            }
        }
    }

    fun getIgnoredVersion(): String = settingsRepository.getIgnoredVersion()

    fun setIgnoredVersion(version: String) {
        settingsRepository.setIgnoredVersion(version)
    }

    fun setCheckBetaUpdates(enabled: Boolean) {
        settingsRepository.checkBetaUpdates = enabled
        _uiState.update { it.copy(checkBetaUpdates = enabled) }
    }

    fun handleSpoofingIntent(intent: SpoofingIntent) {
        when (intent) {
            is SpoofingIntent.SetSaveDialogVisible -> _spoofingUiState.update {
                it.copy(
                    showSaveDialog = intent.visible
                )
            }

            is SpoofingIntent.SetSavedLocationsVisible -> _spoofingUiState.update {
                it.copy(
                    showSavedLocationsDialog = intent.visible
                )
            }

            is SpoofingIntent.SetMapTypeDialogVisible -> _spoofingUiState.update {
                it.copy(
                    showMapTypeDialog = intent.visible
                )
            }

            is SpoofingIntent.SetCustomCoordDialogVisible -> _spoofingUiState.update {
                it.copy(
                    showCustomCoordDialog = intent.visible
                )
            }

            is SpoofingIntent.SetStartSpoofingDialogVisible -> _spoofingUiState.update {
                it.copy(
                    showStartSpoofingDialog = intent.visible
                )
            }

            is SpoofingIntent.SetAppCoordinateScreenVisible -> _spoofingUiState.update {
                it.copy(
                    showAppCoordinateScreen = intent.visible
                )
            }

            is SpoofingIntent.SetSheetExpanded -> _spoofingUiState.update {
                it.copy(
                    isSheetExpanded = intent.expanded
                )
            }

            is SpoofingIntent.SetSearchActive -> _spoofingUiState.update {
                it.copy(
                    isSearchActive = intent.active
                )
            }

            SpoofingIntent.HideSearchResults -> _spoofingUiState.update {
                it.copy(showSearchResults = false)
            }

            is SpoofingIntent.UpdateSearchQuery -> _spoofingUiState.update {
                it.copy(
                    searchQuery = intent.query
                )
            }

            is SpoofingIntent.PerformSearch -> {
                // Implement search logic later via another intent or directly here if preferred
            }

            is SpoofingIntent.ClearSearchResults -> _spoofingUiState.update {
                it.copy(
                    searchResults = emptyList(),
                    showSearchResults = false
                )
            }

            is SpoofingIntent.SetSearchResults -> _spoofingUiState.update {
                val query = intent.query.ifBlank { it.searchQuery }
                it.copy(
                    searchResults = intent.results,
                    showSearchResults = intent.show,
                    cachedSearchQuery = if (intent.results.isNotEmpty()) query else it.cachedSearchQuery,
                    cachedSearchAt = if (intent.results.isNotEmpty()) System.currentTimeMillis() else it.cachedSearchAt
                )
            }

            is SpoofingIntent.ConfirmMapPoint -> confirmMapPoint(
                intent.lat,
                intent.lng
            )

            is SpoofingIntent.MapPointMoved -> {
                val now = System.currentTimeMillis()
                if (now - lastMapMoveTime > 500) {
                    lastMapMoveTime = now
                    confirmMapPoint(intent.lat, intent.lng, isDragging = true)
                } else {
                    mapMoveJob?.cancel()
                    mapMoveJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(500 - (now - lastMapMoveTime))
                        lastMapMoveTime = System.currentTimeMillis()
                        confirmMapPoint(intent.lat, intent.lng, isDragging = true)
                    }
                }
            }

            is SpoofingIntent.RequestCurrentLocation -> {} // Typically requires Context, will pass to a callback instead
        }
    }
}
