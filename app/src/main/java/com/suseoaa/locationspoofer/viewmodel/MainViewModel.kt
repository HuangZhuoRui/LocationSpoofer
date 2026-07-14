package com.suseoaa.locationspoofer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.google.android.gms.location.LocationServices
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.RoutePlanStage
import com.suseoaa.locationspoofer.data.model.RouteRunMode
import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.SimMode
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.data.repository.LocationRepository
import com.suseoaa.locationspoofer.data.repository.SettingsRepository
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.service.SpoofingService
import com.suseoaa.locationspoofer.utils.ConfigWriteGate
import com.suseoaa.locationspoofer.utils.EnvironmentPayloads
import kotlinx.coroutines.CancellationException
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainViewModel(
    private val locationRepository: LocationRepository,
    private val settingsRepository: SettingsRepository,
    private val lsposedManager: com.suseoaa.locationspoofer.utils.LSPosedManager,
    private val environmentScanner: com.suseoaa.locationspoofer.utils.EnvironmentScanner,
    private val environmentDao: com.suseoaa.locationspoofer.data.db.EnvironmentDao,
    private val wifiRepository: com.suseoaa.locationspoofer.data.repository.WifiRepository,
    private val opencellidClient: com.suseoaa.locationspoofer.utils.OpenCellIdClient,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppState(
            mapType = try {
                AppMapType.valueOf(settingsRepository.getMapType())
            } catch (e: Exception) {
                AppMapType.NORMAL
            },
            mapEngine = try {
                com.suseoaa.locationspoofer.data.model.MapEngine.valueOf(settingsRepository.getMapEngine())
            } catch (e: Exception) {
                com.suseoaa.locationspoofer.data.model.MapEngine.AUTO
            },
            savedLocations = settingsRepository.getSavedLocations(),
            savedRoutes = emptyList(), // Will be populated by Room Flow
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

    private var locationSyncJob: Job? = null
    private var autoRouteJob: Job? = null
    private var continuousScanJob: Job? = null
    private var liveFixedLocationJob: Job? = null
    private val liveFixedLocationRequestId = AtomicLong(0)
    private val capabilityRequestId = AtomicLong(0)
    private val configWriteGate = ConfigWriteGate()
    private var selectedEnvironmentOverrides = EnvironmentOverrides()

    private data class EnvironmentPayload(
        val wifiJson: String = "[]",
        val cellJson: String = "[]",
        val bluetoothJson: String = "[]",
        val canMockWifi: Boolean = false,
        val canMockCell: Boolean = false,
        val canMockBluetooth: Boolean = false,
        val wifiApCount: Int = 0
    )

    private data class EnvironmentOverrides(
        val wifiJson: String? = null,
        val cellJson: String? = null,
        val bluetoothJson: String? = null
    )

    init {
        initialize()
    }

    // 初始化

    private fun initialize() {
        val initializationGeneration = configWriteGate.currentGeneration()
        viewModelScope.launch(Dispatchers.IO) {
            val root = locationRepository.checkRootAccess()

            if (settingsRepository.isSpoofingActive && settingsRepository.isRouteSpoofingActive) {
                // Route progress is not persisted, so restoring it as the last fixed point is wrong.
                // Explicitly end the stale route session instead.
                settingsRepository.isSpoofingActive = false
                settingsRepository.isRouteSpoofingActive = false
                configWriteGate.runIfCurrent(initializationGeneration) {
                    locationRepository.stopSpoofing(context)
                }
            } else if (settingsRepository.isSpoofingActive) {
                val lastLat = settingsRepository.lastSpoofedLat.toDoubleOrNull()
                val lastLng = settingsRepository.lastSpoofedLng.toDoubleOrNull()
                if (lastLat != null && lastLng != null &&
                    lastLat in -90.0..90.0 && lastLng in -180.0..180.0
                ) {
                    val payload = prepareEnvironmentPayload(
                        lat = lastLat,
                        lng = lastLng,
                        overrides = EnvironmentOverrides(),
                        fetchRemote = true
                    )
                    if (initializationGeneration == configWriteGate.currentGeneration()) {
                        applyEnvironmentPayload(payload)
                    }
                    var repositoryStarted = false
                    val generationCurrent = configWriteGate.runIfCurrent(initializationGeneration) {
                        repositoryStarted = locationRepository.startSpoofing(
                            context, lastLat, lastLng,
                            "STILL", 0f, System.currentTimeMillis(),
                            emptyList(), false,
                            settingsRepository.getAppCoordinateSystems(),
                            wifiJson = payload.wifiJson,
                            cellJson = payload.cellJson,
                            bluetoothJson = payload.bluetoothJson,
                            mockWifi = settingsRepository.mockWifi && payload.canMockWifi,
                            mockCell = settingsRepository.mockCell,
                            mockBluetooth = settingsRepository.mockBluetooth && payload.canMockBluetooth,
                            enableJitter = settingsRepository.enableJitter
                        )
                    }
                    if (!generationCurrent || !repositoryStarted) {
                        settingsRepository.isSpoofingActive = false
                        settingsRepository.isRouteSpoofingActive = false
                    }
                } else {
                    settingsRepository.isSpoofingActive = false
                    settingsRepository.isRouteSpoofingActive = false
                }
            } else {
                settingsRepository.isRouteSpoofingActive = false
                if (SpoofingService.isRunning) {
                    configWriteGate.runIfCurrent(initializationGeneration) {
                        locationRepository.stopSpoofing(context)
                    }
                }
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
                    appSha1 = getAppSignatureSHA1()
                )
            }
            if (!settingsRepository.isSpoofingActive) {
                fetchCurrentLocation(context)
            }
            refreshRecordCount()
            loadManageData()
        }

        viewModelScope.launch {
            com.suseoaa.locationspoofer.LocationApp.isModuleActive.collect { active ->
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
                    } catch (e: Exception) { e.printStackTrace() }
                    com.suseoaa.locationspoofer.data.model.SavedRoute(entity.name, points).apply {
                        // attach ID dynamically if needed or just use name for deletion
                        // Currently MapScreen's showSavedRoutesDialog uses route.name
                    }
                }
                _uiState.update { it.copy(savedRoutes = routes) }
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

    fun setMapEngine(engine: com.suseoaa.locationspoofer.data.model.MapEngine) {
        settingsRepository.setMapEngine(engine.name)
        _uiState.update { it.copy(mapEngine = engine) }
    }

    fun setSearchMode(mode: com.suseoaa.locationspoofer.data.model.SearchMode) {
        _uiState.update { it.copy(searchMode = mode) }
    }

    data class ClusterData(
        val center: com.suseoaa.locationspoofer.data.db.LocationRecord,
        var count: Int,
        var hasWifi: Boolean,
        var hasBluetooth: Boolean,
        var hasCell: Boolean
    )

    suspend fun performLocalSearch(): List<com.suseoaa.locationspoofer.ui.screen.AppPoiItem> {
        val allRecords = environmentDao.getAllCompleteLocations()
        if (allRecords.isEmpty()) {
            return emptyList()
        }

        // Simple clustering logic: group by ~150m distance
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
                val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

                if (distance <= 150.0) { // 150 meters radius
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
            if (cluster.hasBluetooth) tags.add("蓝牙")
            if (cluster.hasCell) tags.add("基站")
            
            val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""

            com.suseoaa.locationspoofer.ui.screen.AppPoiItem(
                title = "本地采集热点$tagStr",
                snippet = "包含 ${cluster.count} 条记录 (${String.format("%.4f", cluster.center.lat)}, ${String.format("%.4f", cluster.center.lng)})",
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

    fun isDomesticEnvironment(): Boolean {
        val lang = getSavedLanguage()
        return lang == "zh" || (lang.isEmpty() && java.util.Locale.getDefault().language == "zh")
    }

    fun fetchCurrentLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.Main) {
            val isDomestic = isDomesticEnvironment()
            if (isDomestic) {
                val client = try {
                    AMapLocationClient(ctx.applicationContext)
                } catch (e: Exception) {
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
                            updateSelectedLocation(loc.latitude, loc.longitude)
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
            } else {
                // 海外直接使用原生定位(WGS84)
                fallbackToNativeLocation(ctx, forceCallback, false)
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun fallbackToNativeLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)?, convertToGcj: Boolean) {
        try {
            if (forceCallback != null && convertToGcj) {
                android.widget.Toast.makeText(ctx, ctx.getString(com.suseoaa.locationspoofer.R.string.amap_restricted_fallback), android.widget.Toast.LENGTH_SHORT).show()
            }
            val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val provider = if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                android.location.LocationManager.NETWORK_PROVIDER
            } else {
                android.location.LocationManager.GPS_PROVIDER
            }

            val lastLoc = locationManager.getLastKnownLocation(provider)
            if (lastLoc != null) {
                applyNativeLocation(ctx, lastLoc, forceCallback, convertToGcj)
            } else if (forceCallback != null) {
                android.widget.Toast.makeText(ctx, ctx.getString(com.suseoaa.locationspoofer.R.string.waiting_gps_signal), android.widget.Toast.LENGTH_LONG).show()
            }

            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    if (forceCallback != null) {
                        android.widget.Toast.makeText(ctx, ctx.getString(com.suseoaa.locationspoofer.R.string.native_location_success), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    applyNativeLocation(ctx, location, forceCallback, convertToGcj)
                    locationManager.removeUpdates(this)
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
        } catch (e: SecurityException) {
            if (forceCallback != null) {
                android.widget.Toast.makeText(ctx, ctx.getString(com.suseoaa.locationspoofer.R.string.location_permission_denied), android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyNativeLocation(ctx: Context, location: android.location.Location, forceCallback: ((Double, Double) -> Unit)?, convertToGcj: Boolean) {
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
            updateSelectedLocation(finalLat, finalLng)
            forceCallback?.invoke(finalLat, finalLng)
        }
    }

    private suspend fun fetchRealLocationSilent(ctx: Context): Pair<Double, Double>? = suspendCoroutine { cont ->
        val isDomestic = isDomesticEnvironment()
        if (isDomestic) {
            val client = try {
                com.amap.api.location.AMapLocationClient(ctx.applicationContext)
            } catch (e: Exception) {
                cont.resume(null)
                return@suspendCoroutine
            }
            client.setLocationOption(com.amap.api.location.AMapLocationClientOption().apply {
                locationMode = com.amap.api.location.AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
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
        } else {
            fallbackToNativeLocationSilent(ctx, false, cont)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun fallbackToNativeLocationSilent(ctx: Context, convertToGcj: Boolean, cont: kotlin.coroutines.Continuation<Pair<Double, Double>?>) {
        try {
            val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val provider = if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                android.location.LocationManager.NETWORK_PROVIDER
            } else if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                android.location.LocationManager.GPS_PROVIDER
            } else {
                cont.resume(null)
                return
            }

            // try last known first
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
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            // Use main looper for listener
            locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
            
            // Timeout after 5 seconds to avoid suspending forever
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                delay(5000)
                locationManager.removeUpdates(listener)
                if (cont.context.isActive) {
                    try { cont.resume(null) } catch(e: Exception) {}
                }
            }
        } catch (e: Exception) {
            try { cont.resume(null) } catch(e: Exception) {}
        }
    }
    
    private fun getNativeConverted(ctx: Context, location: android.location.Location, convertToGcj: Boolean): Pair<Double, Double> {
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
            selectedEnvironmentOverrides = EnvironmentOverrides()
            _uiState.update { it.copy(longitudeInput = value, showCoordinateError = false) }
            evaluateMockCapabilities()
        }
    }

    fun updateLatitude(value: String) {
        if (isValidCoord(value)) {
            selectedEnvironmentOverrides = EnvironmentOverrides()
            _uiState.update { it.copy(latitudeInput = value, showCoordinateError = false) }
            evaluateMockCapabilities()
        }
    }

    fun selectFixedLocation(lat: Double, lng: Double) {
        updateSelectedLocation(lat, lng)
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
            capabilityRequestId.incrementAndGet()
            _uiState.update { 
                it.copy(canMockWifi = false, canMockCell = false, canMockBluetooth = false, 
                        collectedWifiJson = "[]", collectedCellJson = "[]", collectedBluetoothJson = "[]",
                        wifiApCount = 0, wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE) 
            }
            return
        }
        
        val requestId = capabilityRequestId.incrementAndGet()
        val overrides = selectedEnvironmentOverrides
        viewModelScope.launch {
            val payload = loadEnvironmentPayload(lat, lng, overrides)
            if (requestId == capabilityRequestId.get()) {
                withContext(Dispatchers.Main) { applyEnvironmentPayload(payload) }
            }
        }
    }

    private suspend fun loadEnvironmentPayload(
        lat: Double,
        lng: Double,
        overrides: EnvironmentOverrides = EnvironmentOverrides()
    ): EnvironmentPayload {
        val allRecords = withContext(Dispatchers.IO) { environmentDao.getAllCompleteLocations() }
        val validRecords = mutableListOf<com.suseoaa.locationspoofer.data.db.CompleteLocation>()

        for (record in allRecords) {
            val loc = record.location
            val dLat = Math.toRadians(lat - loc.lat)
            val dLng = Math.toRadians(lng - loc.lng)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                    kotlin.math.cos(Math.toRadians(loc.lat)) *
                    kotlin.math.cos(Math.toRadians(lat)) *
                    kotlin.math.sin(dLng / 2).let { it * it }
            val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))

            if (distance <= 50.0) { // Increased radius to 50m to match visually forgiving areas
                validRecords.add(record)
            }
        }

        val localPayload = if (validRecords.isEmpty()) {
            EnvironmentPayload()
        } else {
            val (wifiJson, cellJson, bluetoothJson) = locationToJson(validRecords, lat, lng)
            environmentPayload(wifiJson, cellJson, bluetoothJson)
        }
        return mergeEnvironmentOverrides(localPayload, overrides)
    }

    private fun environmentPayload(
        wifiJson: String,
        cellJson: String,
        bluetoothJson: String
    ): EnvironmentPayload {
        val wifiInfo = EnvironmentPayloads.inspectWifi(wifiJson)
        return EnvironmentPayload(
            wifiJson = wifiJson,
            cellJson = cellJson,
            bluetoothJson = bluetoothJson,
            canMockWifi = wifiInfo.hasData,
            canMockCell = EnvironmentPayloads.hasArrayItems(cellJson),
            canMockBluetooth = EnvironmentPayloads.hasArrayItems(bluetoothJson),
            wifiApCount = wifiInfo.nearbyCount
        )
    }

    private fun mergeEnvironmentOverrides(
        local: EnvironmentPayload,
        overrides: EnvironmentOverrides
    ): EnvironmentPayload {
        val merged = EnvironmentPayloads.merge(
            localWifiJson = local.wifiJson,
            localCellJson = local.cellJson,
            localBluetoothJson = local.bluetoothJson,
            wifiJsonOverride = overrides.wifiJson,
            cellJsonOverride = overrides.cellJson,
            bluetoothJsonOverride = overrides.bluetoothJson
        )
        return environmentPayload(merged.wifiJson, merged.cellJson, merged.bluetoothJson)
    }

    private fun applyEnvironmentPayload(payload: EnvironmentPayload) {
        _uiState.update {
            it.copy(
                canMockWifi = payload.canMockWifi,
                canMockCell = payload.canMockCell,
                canMockBluetooth = payload.canMockBluetooth,
                collectedWifiJson = payload.wifiJson,
                collectedCellJson = payload.cellJson,
                collectedBluetoothJson = payload.bluetoothJson,
                wifiApCount = payload.wifiApCount,
                wifiLoadStatus = if (payload.canMockWifi) {
                    com.suseoaa.locationspoofer.data.model.WifiLoadStatus.DONE
                } else {
                    com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE
                }
            )
        }
    }

    private suspend fun hasLocalWifiWithin50m(lat: Double, lng: Double): Boolean {
        val allRecords = withContext(Dispatchers.IO) { environmentDao.getAllCompleteLocations() }
        for (record in allRecords) {
            if (record.wifis.isEmpty()) continue
            val loc = record.location
            val dLat = Math.toRadians(lat - loc.lat)
            val dLng = Math.toRadians(lng - loc.lng)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                    kotlin.math.cos(Math.toRadians(loc.lat)) *
                    kotlin.math.cos(Math.toRadians(lat)) *
                    kotlin.math.sin(dLng / 2).let { it * it }
            val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            if (distance <= 50.0) {
                return true
            }
        }
        return false
    }

    private suspend fun hasLocalCellsWithin50m(lat: Double, lng: Double): Boolean {
        val allRecords = withContext(Dispatchers.IO) { environmentDao.getAllCompleteLocations() }
        for (record in allRecords) {
            if (record.cells.isEmpty()) continue
            val loc = record.location
            val dLat = Math.toRadians(lat - loc.lat)
            val dLng = Math.toRadians(lng - loc.lng)
            val a = kotlin.math.sin(dLat / 2).let { it * it } + 
                    kotlin.math.cos(Math.toRadians(loc.lat)) * 
                    kotlin.math.cos(Math.toRadians(lat)) * 
                    kotlin.math.sin(dLng / 2).let { it * it }
            val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            if (distance <= 50.0) {
                val cellCount = record.cells.size
                val cellInfoStr = record.cells.map { cell ->
                    "Type: ${cell.device.type}, MCC: ${cell.device.mcc}, MNC: ${cell.device.mnc}, LAC: ${cell.device.lac}, CID: ${cell.device.cid}, DBM: ${cell.locationCell.dbm}"
                }.joinToString("; ")
                android.util.Log.d("OpenCellID", "hasLocalCellsWithin50m: Found cached cells within 50m of ($lat, $lng) at location ID ${record.location.id}. Distance: ${String.format(Locale.US, "%.2f", distance)}m, Cell Count: $cellCount. Bypassing network request. Cached cells: $cellInfoStr")
                return true
            }
        }
        android.util.Log.d("OpenCellID", "hasLocalCellsWithin50m: No cached cells within 50m of ($lat, $lng). Will perform network request.")
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
        // Align coordinate conversion to WGS-84 standard for WiGLE API
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
                    put("vendor", com.suseoaa.locationspoofer.utils.MacVendorHelper.getVendor(firstBssid))
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
                        put("vendor", com.suseoaa.locationspoofer.utils.MacVendorHelper.getVendor(bssid))
                        put("level", level)
                        put("capabilities", "[WPA2-PSK-CCMP][ESS]")
                        put("frequency", freq)
                        put("channel", com.suseoaa.locationspoofer.utils.MacVendorHelper.frequencyToChannel(freq))
                    }
                    formattedNearby.put(itemObj)
                }
                wifiObj.put("nearbyWifi", formattedNearby)
                
                val formattedWifiJson = wifiObj.toString()
                
                withContext(Dispatchers.IO) {
                    saveEnvironmentData(lat, lng, formattedWifiJson, "[]", "[]")
                    // update metadata to indicate WiGLE source
                    val newestLocation = environmentDao.getAllLocations().firstOrNull { it.lat == lat && it.lng == lng }
                    if (newestLocation != null) {
                        environmentDao.updateMetadata(newestLocation.id, "WiGLE 导入", "经纬度: (${String.format(Locale.US, "%.6f", lat)}, ${String.format(Locale.US, "%.6f", lng)})")
                    }
                }
                
                val count = withContext(Dispatchers.IO) { environmentDao.getRecordCount() }
                _uiState.update { it.copy(environmentRecordCount = count) }
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
        } catch (e: CancellationException) {
            throw e
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
            android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Token is blank. Skipping request and database insertion.")
            return
        }
        android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Starting request for coordinates ($lat, $lng)")
        val wgs84 = com.suseoaa.locationspoofer.utils.CoordinateUtils.gcj02ToWgs84(lat, lng)
        val wgsLat = wgs84.lat
        val wgsLng = wgs84.lng
        android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Converted GCJ-02 ($lat, $lng) -> WGS-84 ($wgsLat, $wgsLng) for OpenCellID query")

        try {
            val rawJsonArrayString = opencellidClient.fetchCellData(wgsLat, wgsLng, tokenToUse)
            val cellsArray = org.json.JSONArray(rawJsonArrayString)
            android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Received cell list size: ${cellsArray.length()}")
            if (cellsArray.length() > 0) {
                val formattedCells = normalizeCellArrayForStorage(cellsArray)
                if (formattedCells.length() == 0) {
                    android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: No usable cells after normalization.")
                    return
                }

                withContext(Dispatchers.IO) {
                    saveEnvironmentData(lat, lng, "{}", formattedCells.toString(), "[]")
                    val newestLocation = environmentDao.getAllLocations().firstOrNull { it.lat == lat && it.lng == lng }
                    if (newestLocation != null) {
                        environmentDao.updateMetadata(newestLocation.id, "OpenCellID 导入", "经纬度: (${String.format(Locale.US, "%.6f", lat)}, ${String.format(Locale.US, "%.6f", lng)})")
                    }
                    android.util.Log.d("OpenCellID", "fetchCellFromOpenCellIdSync: Successfully inserted ${formattedCells.length()} cells into database.")
                }

                val count = withContext(Dispatchers.IO) { environmentDao.getRecordCount() }
                _uiState.update { it.copy(environmentRecordCount = count) }
            }
        } catch (e: CancellationException) {
            throw e
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
                android.util.Log.d("OpenCellID", "normalizeCellArrayForStorage: Skipping invalid cell: $cell")
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
                put("pci", positiveCellInt(cell, "pci", default = (identity % 504).coerceIn(0, 503)))
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

    private suspend fun prepareEnvironmentPayload(
        lat: Double,
        lng: Double,
        overrides: EnvironmentOverrides,
        fetchRemote: Boolean
    ): EnvironmentPayload {
        val state = _uiState.value
        if (fetchRemote && overrides.wifiJson == null && state.mockWifi && !hasLocalWifiWithin50m(lat, lng)) {
            fetchWifiFromWigleSync(lat, lng)
        }
        if (fetchRemote && overrides.cellJson == null && state.mockCell && !hasLocalCellsWithin50m(lat, lng)) {
            fetchCellFromOpenCellIdSync(lat, lng)
        }
        return loadEnvironmentPayload(lat, lng, overrides)
    }

    // 定点模拟

    @android.annotation.SuppressLint("MissingPermission")
    fun startSpoofing() {
        val state = _uiState.value
        
        if (state.isContinuousScanning) {
            android.widget.Toast.makeText(context, context.getString(com.suseoaa.locationspoofer.R.string.disable_continuous_scan_first), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val lng = state.longitudeInput.toDoubleOrNull()
        val lat = state.latitudeInput.toDoubleOrNull()
        if (lng == null || lat == null || lng !in -180.0..180.0 || lat !in -90.0..90.0) {
            _uiState.update { it.copy(showCoordinateError = true) }
            return
        }
        
        settingsRepository.isSpoofingActive = true
        settingsRepository.isRouteSpoofingActive = false
        settingsRepository.lastSpoofedLat = lat.toString()
        settingsRepository.lastSpoofedLng = lng.toString()
        cancelLiveFixedLocationSync()
        val configGeneration = configWriteGate.beginNewGeneration()
        
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingConfig = true) }
            
            val payload = prepareEnvironmentPayload(
                lat = lat,
                lng = lng,
                overrides = selectedEnvironmentOverrides,
                fetchRemote = true
            )
            applyEnvironmentPayload(payload)
            if (configGeneration != configWriteGate.currentGeneration()) {
                return@launch
            }
            
            val updatedState = _uiState.value
            val now = System.currentTimeMillis()
            var repositoryStarted = false
            val wroteConfig = configWriteGate.runIfCurrent(configGeneration) {
                repositoryStarted = locationRepository.startSpoofing(
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
            }
            if (!wroteConfig || !repositoryStarted) {
                if (configGeneration == configWriteGate.currentGeneration()) {
                    settingsRepository.isSpoofingActive = false
                    settingsRepository.isRouteSpoofingActive = false
                    _uiState.update { it.copy(isSpoofingActive = false, isSavingConfig = false) }
                }
                return@launch
            }
            
            // Wait briefly to ensure root shell syncs to disk fully
            kotlinx.coroutines.delay(200)

            if (configGeneration == configWriteGate.currentGeneration()) {
                _uiState.update {
                    it.copy(isSpoofingActive = true, isSavingConfig = false)
                }
            }
        }
    }

    fun stopSpoofing() {
        val configGeneration = configWriteGate.beginNewGeneration()
        settingsRepository.isSpoofingActive = false
        settingsRepository.isRouteSpoofingActive = false
        cancelLiveFixedLocationSync()
        locationSyncJob?.cancel()
        locationSyncJob = null
        autoRouteJob?.cancel()
        autoRouteJob = null
        _uiState.update {
            it.copy(isSpoofingActive = false, isSavingConfig = false)
        }
        viewModelScope.launch {
            configWriteGate.runIfCurrent(configGeneration) {
                locationRepository.stopSpoofing(context)
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
            kotlin.math.cos(latRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(bearingRad)
        )
        val newLngRad = lngRad + kotlin.math.atan2(
            kotlin.math.sin(bearingRad) * kotlin.math.sin(distance / R) * kotlin.math.cos(latRad),
            kotlin.math.cos(distance / R) - kotlin.math.sin(latRad) * kotlin.math.sin(newLatRad)
        )
        val newLat = Math.toDegrees(newLatRad)
        val newLng = Math.toDegrees(newLngRad)
        _uiState.update {
            it.copy(
                latitudeInput = String.format(Locale.US, "%.6f", newLat),
                longitudeInput = String.format(Locale.US, "%.6f", newLng),
                simBearing = bearing.toFloat(),
                showCoordinateError = false
            )
        }
        // 实时同步给 SpooferProvider
        SpooferProvider.latitude = newLat
        SpooferProvider.longitude = newLng
        SpooferProvider.simBearing = bearing.toFloat()
        SpooferProvider.startTimestamp = System.currentTimeMillis()
    }

    // 路线规划状态机

    /** 进入全屏地图，进入选点阶段 */
    fun enterRoutePlanning() {
        cancelLiveFixedLocationSync()
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
            // we delete by finding the entity with matching name
            // (a bit hacky but works for now, or we can add delete by name in DAO)
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
    fun confirmMapPoint(lat: Double, lng: Double) {
        updateSelectedLocation(lat, lng, mapPoint = Pair(lat, lng))
    }

    /** 清除地图选点状态 */

    fun setUseRealRoute(use: Boolean) {
        _uiState.update { it.copy(useRealRoute = use) }
    }

    /**
     * 开始路线模拟。
     * - 手动模式：启动 spoofing（STILL），由摇杆驱动 moveByJoystick 实时更新坐标。
     * - 循环模式：启动 spoofing，自动沿路线点按速度移动，到终点后反向循环。
     */
    fun startRoutePlanning() {
        val state = _uiState.value
        if (state.isContinuousScanning) {
            android.widget.Toast.makeText(context, context.getString(com.suseoaa.locationspoofer.R.string.disable_continuous_scan_route_first), android.widget.Toast.LENGTH_SHORT).show()
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
                    val end = com.amap.api.services.core.LatLonPoint(points[i + 1].lat, points[i + 1].lng)
                    val fromAndTo = com.amap.api.services.route.RouteSearch.FromAndTo(start, end)

                    // 创建驾车路线查询 (0: 速度优先，不考虑路况)
                    val query = com.amap.api.services.route.RouteSearch.DriveRouteQuery(
                        fromAndTo, com.amap.api.services.route.RouteSearch.DrivingDefault, null, null, ""
                    )
                    
                    val result = routeSearch.calculateDriveRoute(query)
                    if (result != null && result.paths.isNotEmpty()) {
                        val path = result.paths[0]
                        val segmentPoints = mutableListOf<RoutePoint>()
                        val stepEndIndices = mutableListOf<Int>()
                        for (step in path.steps) {
                            for (polyline in step.polyline) {
                                segmentPoints.add(RoutePoint(polyline.latitude, polyline.longitude, 0.0))
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
                        android.widget.Toast.makeText(context, "路线规划失败或部分失败，使用直线模拟", android.widget.Toast.LENGTH_SHORT).show()
                        startSimulationWithPoints(points, state)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isFetchingRoute = false) }
                    var msg = "路线请求异常: ${e.message}"
                    if (e is com.amap.api.services.core.AMapException) {
                        val errCode = e.errorCode
                        val errMsg = e.errorMessage ?: ""
                        msg = "高德API异常(码:$errCode): $errMsg"
                        if (errCode == 10003 || errCode == 10012 || errCode == 10013 || errCode == 1800 || errCode == 18000 || 
                            errMsg.contains("额度") || errMsg.contains("limit", ignoreCase = true)) {
                            msg = "高德API调用失败(可能是额度耗尽)，将退回直线模拟！\n错误详情: $errMsg"
                        }
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    startSimulationWithPoints(points, state)
                }
            }
        }
    }

    private fun startSimulationWithPoints(pointsToRun: List<RoutePoint>, state: AppState) {
        cancelLiveFixedLocationSync()
        val configGeneration = configWriteGate.beginNewGeneration()
        settingsRepository.isSpoofingActive = true
        settingsRepository.isRouteSpoofingActive = true
        val startPoint = pointsToRun.first()

        _uiState.update {
            it.copy(
                latitudeInput = String.format(Locale.US, "%.6f", startPoint.lat),
                longitudeInput = String.format(Locale.US, "%.6f", startPoint.lng),
                routePlanStage = RoutePlanStage.RUNNING,
                routePoints = pointsToRun
            )
        }

        val isLoop = state.routeRunMode == RouteRunMode.LOOP

        viewModelScope.launch {
            val now = System.currentTimeMillis()

            var repositoryStarted = false
            val generationCurrent = configWriteGate.runIfCurrent(configGeneration) {
                repositoryStarted = locationRepository.startSpoofing(
                    context, startPoint.lat, startPoint.lng,
                    if (isLoop) state.routeSimMode.name else "STILL",
                    0f, now, pointsToRun, isLoop, state.appCoordinateSystems,
                    state.collectedWifiJson, state.collectedCellJson, state.collectedBluetoothJson,
                    state.mockWifi && state.canMockWifi,
                    state.mockCell,
                    state.mockBluetooth && state.canMockBluetooth,
                    state.enableJitter
                )
            }
            if (generationCurrent && repositoryStarted && configGeneration == configWriteGate.currentGeneration()) {
                _uiState.update {
                    it.copy(isSpoofingActive = true)
                }
                if (isLoop) {
                    startAutoRouteLoop()
                }
            } else if (configGeneration == configWriteGate.currentGeneration()) {
                settingsRepository.isSpoofingActive = false
                settingsRepository.isRouteSpoofingActive = false
                _uiState.update {
                    it.copy(isSpoofingActive = false, routePlanStage = RoutePlanStage.READY)
                }
            }
        }
    }

    /** 停止路线模拟，重置所有状态 */
    fun cancelRoutePlanning() {
        _uiState.update {
            it.copy(
                routePlanStage = RoutePlanStage.IDLE,
                routePoints = emptyList(),
                routeRunMode = RouteRunMode.MANUAL
            )
        }
    }

    fun stopRoutePlanning() {
        val configGeneration = configWriteGate.beginNewGeneration()
        settingsRepository.isSpoofingActive = false
        settingsRepository.isRouteSpoofingActive = false
        locationSyncJob?.cancel()
        locationSyncJob = null
        autoRouteJob?.cancel()
        autoRouteJob = null
        cancelLiveFixedLocationSync()
        _uiState.update {
            it.copy(
                isSpoofingActive = false,
                routePlanStage = RoutePlanStage.IDLE,
                routePoints = emptyList(),
                routeRunMode = RouteRunMode.MANUAL
            )
        }
        viewModelScope.launch {
            configWriteGate.runIfCurrent(configGeneration) {
                locationRepository.stopSpoofing(context)
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
                name = name,
                lat = lat,
                lng = lng,
                wifiJson = state.collectedWifiJson,
                cellJson = state.collectedCellJson,
                bluetoothJson = state.collectedBluetoothJson
            )
        )
        _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
    }

    fun loadSavedLocation(loc: SavedLocation) {
        val overrides = EnvironmentOverrides(
            wifiJson = EnvironmentPayloads.usableWifiOverride(loc.wifiJson),
            cellJson = EnvironmentPayloads.usableArrayOverride(loc.cellJson),
            bluetoothJson = EnvironmentPayloads.usableArrayOverride(loc.bluetoothJson)
        )
        updateSelectedLocation(loc.lat, loc.lng, overrides = overrides)
    }

    fun removeSavedLocation(location: SavedLocation) {
        settingsRepository.removeSavedLocation(location)
        _uiState.update { it.copy(savedLocations = settingsRepository.getSavedLocations()) }
    }

    fun addSavedRoute(name: String) {
        val points = _uiState.value.routePoints
        if (points.size >= 2) {
            settingsRepository.addSavedRoute(com.suseoaa.locationspoofer.data.model.SavedRoute(name, points))
            _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
        }
    }

    fun removeSavedRoute(route: com.suseoaa.locationspoofer.data.model.SavedRoute) {
        settingsRepository.removeSavedRoute(route)
        _uiState.update { it.copy(savedRoutes = settingsRepository.getSavedRoutes()) }
    }

    fun loadSavedRoute(route: com.suseoaa.locationspoofer.data.model.SavedRoute) {
        _uiState.update { it.copy(
            routePoints = route.points,
            routePlanStage = com.suseoaa.locationspoofer.data.model.RoutePlanStage.READY
        )}
    }

    // 搜索



    // 内部工具

    /**
     * 循环模式自动移动。
     * 按路点顺序移动，到终点后反向，不断循环。
     * 同时实时同步坐标到 SpooferProvider。
     */
    private fun startAutoRouteLoop() {
        autoRouteJob?.cancel()
        autoRouteJob = viewModelScope.launch(Dispatchers.Default) {
            val points = _uiState.value.routePoints
            if (points.size < 2) return@launch

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
                            // 到达终点，反向
                            forward = false
                            segmentIndex = points.lastIndex - 1
                            progress = 0.0
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
                    updatePosition(newFrom.lat, newFrom.lng, 0f)
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

    /** 更新当前模拟位置到 UI 和 SpooferProvider */
    private fun updatePosition(lat: Double, lng: Double, bearing: Float) {
        _uiState.update {
            it.copy(
                latitudeInput = String.format(Locale.US, "%.6f", lat),
                longitudeInput = String.format(Locale.US, "%.6f", lng),
                simBearing = bearing,
                showCoordinateError = false
            )
        }
        SpooferProvider.latitude = lat
        SpooferProvider.longitude = lng
        SpooferProvider.simBearing = bearing
        SpooferProvider.startTimestamp = System.currentTimeMillis()
        
        // Check if we need to query the database (e.g. moved more than 20 meters since last query)
        val dLat = Math.toRadians(lat - lastDbQueryLat)
        val dLng = Math.toRadians(lng - lastDbQueryLng)
        val a = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(lastDbQueryLat)) * kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.sin(dLng / 2).let { it * it }
        val distance = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        
        if (distance > 20.0) {
            lastDbQueryLat = lat
            lastDbQueryLng = lng
            val configGeneration = configWriteGate.currentGeneration()
            viewModelScope.launch(Dispatchers.IO) {
                val records = environmentDao.getNearestLocations(lat, lng, 3)
                val environmentJson = if (records.isNotEmpty()) {
                    val record = records[0]
                    // Check if the closest record is actually within ~50 meters
                    val rLat = Math.toRadians(record.location.lat - lat)
                    val rLng = Math.toRadians(record.location.lng - lng)
                    val rA = kotlin.math.sin(rLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(lat)) * kotlin.math.cos(Math.toRadians(record.location.lat)) * kotlin.math.sin(rLng / 2).let { it * it }
                    val rDist = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(rA), kotlin.math.sqrt(1 - rA))
                    
                    if (rDist <= 50.0) {
                        val jsons = locationToJson(records, lat, lng)
                        jsons
                    } else {
                        // Fallback to random cell generation
                        Triple("[]", "[]", "[]")
                    }
                } else {
                    Triple("[]", "[]", "[]")
                }

                configWriteGate.runIfCurrent(configGeneration) {
                    val state = _uiState.value
                    locationRepository.updateConfig(
                        lat = lat,
                        lng = lng,
                        simMode = "STILL",
                        simBearing = bearing,
                        startTime = SpooferProvider.startTimestamp,
                        routePoints = state.routePoints,
                        isRouteMode = state.routePlanStage == com.suseoaa.locationspoofer.data.model.RoutePlanStage.RUNNING,
                        appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
                        wifiJson = environmentJson.first,
                        cellJson = environmentJson.second,
                        bluetoothJson = environmentJson.third,
                        mockWifi = state.mockWifi && EnvironmentPayloads.inspectWifi(environmentJson.first).hasData,
                        mockCell = state.mockCell,
                        mockBluetooth = state.mockBluetooth && EnvironmentPayloads.hasArrayItems(environmentJson.third),
                        enableJitter = state.enableJitter
                    )
                }
            }
        }
    }

    private fun updateSelectedLocation(
        lat: Double,
        lng: Double,
        mapPoint: Pair<Double, Double>? = null,
        overrides: EnvironmentOverrides = EnvironmentOverrides()
    ) {
        if (lng !in -180.0..180.0 || lat !in -90.0..90.0) {
            _uiState.update { it.copy(showCoordinateError = true) }
            return
        }

        capabilityRequestId.incrementAndGet()
        selectedEnvironmentOverrides = overrides
        _uiState.update {
            it.copy(
                latitudeInput = String.format(Locale.US, "%.6f", lat),
                longitudeInput = String.format(Locale.US, "%.6f", lng),
                mapConfirmedPoint = mapPoint ?: it.mapConfirmedPoint,
                showCoordinateError = false
            )
        }

        if (overrides != EnvironmentOverrides()) {
            applyEnvironmentPayload(mergeEnvironmentOverrides(EnvironmentPayload(), overrides))
        }

        if (isFixedSpoofingActive()) {
            syncLiveFixedLocation(
                lat,
                lng,
                overrides
            )
        } else {
            evaluateMockCapabilities()
        }
    }

    private fun isFixedSpoofingActive(): Boolean {
        val state = _uiState.value
        return state.isSpoofingActive && state.routePlanStage == RoutePlanStage.IDLE
    }

    private fun cancelLiveFixedLocationSync() {
        liveFixedLocationRequestId.incrementAndGet()
        liveFixedLocationJob?.cancel()
        liveFixedLocationJob = null
        _uiState.update { state ->
            if (state.wifiLoadStatus == com.suseoaa.locationspoofer.data.model.WifiLoadStatus.LOADING) {
                state.copy(wifiLoadStatus = com.suseoaa.locationspoofer.data.model.WifiLoadStatus.IDLE)
            } else {
                state
            }
        }
    }

    private fun syncLiveFixedLocation(
        lat: Double,
        lng: Double,
        overrides: EnvironmentOverrides = EnvironmentOverrides()
    ) {
        if (!isFixedSpoofingActive()) {
            return
        }

        settingsRepository.lastSpoofedLat = lat.toString()
        settingsRepository.lastSpoofedLng = lng.toString()

        val requestId = liveFixedLocationRequestId.incrementAndGet()
        val configGeneration = configWriteGate.currentGeneration()
        liveFixedLocationJob?.cancel()
        liveFixedLocationJob = viewModelScope.launch {
            val payload = prepareEnvironmentPayload(
                lat = lat,
                lng = lng,
                overrides = overrides,
                fetchRemote = true
            )
            if (requestId != liveFixedLocationRequestId.get() || !isFixedSpoofingActive()) {
                return@launch
            }
            applyEnvironmentPayload(payload)

            configWriteGate.runIfCurrent(configGeneration) {
                if (requestId != liveFixedLocationRequestId.get() || !isFixedSpoofingActive()) {
                    return@runIfCurrent
                }

                val state = _uiState.value
                val now = System.currentTimeMillis()
                locationRepository.updateConfig(
                    lat = lat,
                    lng = lng,
                    simMode = "STILL",
                    simBearing = state.simBearing,
                    startTime = now,
                    routePoints = emptyList(),
                    isRouteMode = false,
                    appCoordinateSystems = state.appCoordinateSystems,
                    wifiJson = payload.wifiJson,
                    cellJson = payload.cellJson,
                    bluetoothJson = payload.bluetoothJson,
                    mockWifi = state.mockWifi && payload.canMockWifi,
                    mockCell = state.mockCell,
                    mockBluetooth = state.mockBluetooth && payload.canMockBluetooth,
                    enableJitter = state.enableJitter
                )
            }
        }
    }

    private fun haversineMeters(a: RoutePoint, b: RoutePoint): Double {
        val R = 6378137.0
        val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
        val dLat = Math.toRadians(b.lat - a.lat); val dLng = Math.toRadians(b.lng - a.lng)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) * kotlin.math.sin(dLng / 2).let { it * it }
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
            val configGeneration = configWriteGate.currentGeneration()
            viewModelScope.launch {
                configWriteGate.runIfCurrent(configGeneration) {
                    val state = _uiState.value
                    val lat = state.latitudeInput.toDoubleOrNull() ?: return@runIfCurrent
                    val lng = state.longitudeInput.toDoubleOrNull() ?: return@runIfCurrent
                    locationRepository.updateConfig(
                        lat = lat,
                        lng = lng,
                        simMode = if (state.routePlanStage == com.suseoaa.locationspoofer.data.model.RoutePlanStage.RUNNING) state.routeSimMode.name else "STILL",
                        simBearing = state.simBearing,
                        startTime = SpooferProvider.startTimestamp,
                        routePoints = state.routePoints,
                        isRouteMode = state.routePlanStage == com.suseoaa.locationspoofer.data.model.RoutePlanStage.RUNNING,
                        appCoordinateSystems = state.appCoordinateSystems,
                        wifiJson = state.collectedWifiJson,
                        cellJson = state.collectedCellJson,
                        bluetoothJson = state.collectedBluetoothJson,
                        mockWifi = state.mockWifi && state.canMockWifi,
                        mockCell = state.mockCell,
                        mockBluetooth = state.mockBluetooth && state.canMockBluetooth,
                        enableJitter = state.enableJitter
                    )
                }
            }
        }
    }



    fun toggleContinuousScanning() {
        if (_uiState.value.isSpoofingActive) {
            android.widget.Toast.makeText(context, context.getString(com.suseoaa.locationspoofer.R.string.disable_continuous_scan_route_first), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        if (_uiState.value.isSpoofingActive) {
            android.widget.Toast.makeText(context, context.getString(com.suseoaa.locationspoofer.R.string.stop_spoofing_before_scan), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentState = _uiState.value.isContinuousScanning
        _uiState.update { it.copy(isContinuousScanning = !currentState) }
        
        if (!currentState) {
            // Start scanning
            continuousScanJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val realLoc = fetchRealLocationSilent(context)
                    if (realLoc != null) {
                        val lat = realLoc.first
                        val lng = realLoc.second
                        
                        val wifiJson = environmentScanner.scanWifi()
                        val cellJson = environmentScanner.scanCell()
                        val bluetoothJson = environmentScanner.scanBluetooth()
                        
                        saveEnvironmentData(lat, lng, wifiJson, cellJson, bluetoothJson)
                        
                        val count = environmentDao.getRecordCount()
                        _uiState.update { it.copy(environmentRecordCount = count) }
                    }
                    
                    // Delay 10 seconds between scans
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

    fun loadManageData() {
        _uiState.update { it.copy(manageDataIsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val list = environmentDao.getAllCompleteLocations()
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(manageDataList = list, manageDataIsLoading = false) }
                evaluateMockCapabilities()
            }
        }
    }

    fun deleteManageData(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocations(ids)
            loadManageData()
            refreshRecordCount()
        }
    }

    fun deleteManageDataSingle(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.deleteLocation(id)
            loadManageData()
            refreshRecordCount()
        }
    }

    fun updateManageDataMetadata(id: Long, placeName: String, remark: String) {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.updateMetadata(id, placeName, remark)
            loadManageData()
        }
    }

    fun clearAllManageData() {
        viewModelScope.launch(Dispatchers.IO) {
            environmentDao.clearAll()
            loadManageData()
            refreshRecordCount()
        }
    }

    fun toggleManageDataScreen(show: Boolean) {
        _uiState.update { it.copy(isManageDataScreen = show) }
        if (show) {
            loadManageData()
        }
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
        
        // If spoofing is active, update config
        if (_uiState.value.isSpoofingActive) {
            val configGeneration = configWriteGate.currentGeneration()
            viewModelScope.launch {
                configWriteGate.runIfCurrent(configGeneration) {
                    val state = _uiState.value
                    locationRepository.updateConfig(
                        SpooferProvider.latitude,
                        SpooferProvider.longitude,
                        SpooferProvider.simMode,
                        SpooferProvider.simBearing,
                        SpooferProvider.startTimestamp,
                        if (SpooferProvider.isRouteMode) parseRoutePoints(SpooferProvider.routeJson) else emptyList(),
                        SpooferProvider.isRouteMode,
                        currentMap,
                        mockWifi = state.mockWifi && state.canMockWifi,
                        mockCell = state.mockCell,
                        mockBluetooth = state.mockBluetooth && state.canMockBluetooth,
                        enableJitter = state.enableJitter
                    )
                }
            }
        }
    }

    fun removeAppCoordinateSystem(pkg: String) {
        val currentMap = _uiState.value.appCoordinateSystems.toMutableMap()
        currentMap.remove(pkg)
        settingsRepository.setAppCoordinateSystems(currentMap)
        _uiState.update { it.copy(appCoordinateSystems = currentMap) }

        if (_uiState.value.isSpoofingActive) {
            val configGeneration = configWriteGate.currentGeneration()
            viewModelScope.launch {
                configWriteGate.runIfCurrent(configGeneration) {
                    val state = _uiState.value
                    locationRepository.updateConfig(
                        SpooferProvider.latitude,
                        SpooferProvider.longitude,
                        SpooferProvider.simMode,
                        SpooferProvider.simBearing,
                        SpooferProvider.startTimestamp,
                        if (SpooferProvider.isRouteMode) parseRoutePoints(SpooferProvider.routeJson) else emptyList(),
                        SpooferProvider.isRouteMode,
                        currentMap,
                        mockWifi = state.mockWifi && state.canMockWifi,
                        mockCell = state.mockCell,
                        mockBluetooth = state.mockBluetooth && state.canMockBluetooth,
                        enableJitter = state.enableJitter
                    )
                }
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

    private suspend fun saveEnvironmentData(lat: Double, lng: Double, wifiJson: String, cellJson: String, bluetoothJson: String) {
        val locId = environmentDao.insertLocation(com.suseoaa.locationspoofer.data.db.LocationRecord(lat = lat, lng = lng))
        
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
                val type = normalizeCellType(obj.optString("type", obj.optString("radio", "UNKNOWN")))
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
                val cellKey = "${type}_${positiveCellInt(obj, "mcc", default = 460)}_${positiveCellInt(obj, "mnc", "net", default = 0)}_${tac}_${ci}_${cid}_${basestationId}_${nci}"
                val device = com.suseoaa.locationspoofer.data.db.CellDevice(
                    cellKey = cellKey, type = type,
                    mcc = positiveCellInt(obj, "mcc", default = 460),
                    mnc = positiveCellInt(obj, "mnc", "net", default = 0),
                    tac = tac, ci = ci,
                    pci = positiveCellInt(obj, "pci", default = if (identity > 0) (identity % 504).coerceIn(0, 503) else 0),
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
        } catch (e: Exception) {}

        try {
            val btArr = org.json.JSONArray(bluetoothJson)
            for (i in 0 until btArr.length()) {
                val obj = btArr.getJSONObject(i)
                val address = obj.optString("address")
                if (address.isEmpty()) continue
                environmentDao.insertBluetoothDevice(com.suseoaa.locationspoofer.data.db.BluetoothDevice(address, obj.optString("name", ""), obj.optString("scanRecordHex", "")))
                environmentDao.insertLocationBluetooth(com.suseoaa.locationspoofer.data.db.LocationBluetooth(locId, address, obj.optInt("rssi", -60)))
            }
        } catch (e: Exception) {}
    }

    private fun locationToJson(records: List<com.suseoaa.locationspoofer.data.db.CompleteLocation>, targetLat: Double, targetLng: Double): Triple<String, String, String> {
        if (records.isEmpty()) return Triple("{}", "[]", "[]")

        val weights = records.map {
            val rLat = Math.toRadians(it.location.lat - targetLat)
            val rLng = Math.toRadians(it.location.lng - targetLng)
            val rA = kotlin.math.sin(rLat / 2).let { v -> v * v } + kotlin.math.cos(Math.toRadians(targetLat)) * kotlin.math.cos(Math.toRadians(it.location.lat)) * kotlin.math.sin(rLng / 2).let { v -> v * v }
            val dist = 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(rA), kotlin.math.sqrt(1 - rA))
            val safeDist = kotlin.math.max(dist, 1.0)
            1.0 / (safeDist * safeDist)
        }

        // 1. Reconstruct connected Wi-Fi using the closest record's connectedWi-Fi
        val closestRecord = records.firstOrNull()
        val hasConnected = closestRecord?.connectedWifi != null
        val connectedObj = if (hasConnected) {
            val cw = closestRecord!!.connectedWifi!!
            org.json.JSONObject().apply {
                put("ssid", cw.ssid)
                put("bssid", cw.bssid)
                put("vendor", cw.vendor)
                put("macAddress", cw.macAddress)
                put("frequency", cw.frequency)
                put("channel", com.suseoaa.locationspoofer.utils.MacVendorHelper.frequencyToChannel(cw.frequency))
                put("linkSpeed", cw.linkSpeed)
                put("level", cw.level)
                put("capabilities", cw.capabilities)
                put("networkId", cw.networkId)
                put("wifiStandard", cw.wifiStandard)
            }
        } else {
            null
        }

        // 2. Interpolate nearby Wi-Fis
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
                put("channel", com.suseoaa.locationspoofer.utils.MacVendorHelper.frequencyToChannel(rw.device.frequency))
                put("capabilities", rw.device.capabilities)
                put("level", interpolatedLevel)
            }
            nearbyArr.put(obj)
        }

        val wifiResultObj = org.json.JSONObject().apply {
            put("isConnected", hasConnected)
            put("connectedWifi", connectedObj ?: org.json.JSONObject.NULL)
            put("nearbyWifi", nearbyArr)
        }
        val wifiArr = wifiResultObj // Just assign it to match the rest of the method variables if needed, or we return wifiResultObj.toString()

        
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
        
        val cellArr = org.json.JSONArray()
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
            obj.put("isRegistered", rc.locationCell.isRegistered)
            cellArr.put(obj)
        }
        
        val btMap = mutableMapOf<String, com.suseoaa.locationspoofer.data.db.LocationWithBluetooth>()
        val btRssis = mutableMapOf<String, Double>()
        val btWeights = mutableMapOf<String, Double>()
        
        records.forEachIndexed { i, rec ->
            rec.bluetooths.forEach { rb ->
                val address = rb.device.address
                if (!btMap.containsKey(address)) btMap[address] = rb
                btRssis[address] = (btRssis[address] ?: 0.0) + rb.locationBluetooth.rssi * weights[i]
                btWeights[address] = (btWeights[address] ?: 0.0) + weights[i]
            }
        }
        
        val btArr = org.json.JSONArray()
        btMap.forEach { (address, rb) ->
            val w = btWeights[address]!!
            val interpolatedRssi = (btRssis[address]!! / w).toInt()
            val obj = org.json.JSONObject()
            obj.put("address", address)
            obj.put("name", rb.device.name)
            obj.put("scanRecordHex", rb.device.scanRecordHex)
            obj.put("rssi", interpolatedRssi)
            btArr.put(obj)
        }
        
        return Triple(wifiArr.toString(), cellArr.toString(), btArr.toString())
    }

    fun exportEnvironmentData(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val locations = environmentDao.getAllCompleteLocations()
                val jsonStr = kotlinx.serialization.json.Json.encodeToString(locations)
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonStr.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importEnvironmentData(uri: android.net.Uri, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonStr = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
                if (jsonStr != null) {
                    val locations: List<com.suseoaa.locationspoofer.data.db.CompleteLocation> = kotlinx.serialization.json.Json.decodeFromString(jsonStr)
                    locations.forEach { cl ->
                        val locId = environmentDao.insertLocation(cl.location)
                        cl.connectedWifi?.let { cw ->
                            val newCw = cw.copy(locationId = locId)
                            environmentDao.insertConnectedWifi(newCw)
                        }
                        cl.wifis.forEach { w ->
                            environmentDao.insertWifiDevice(w.device)
                            val lw = w.locationWifi.copy(locationId = locId)
                            environmentDao.insertLocationWifi(lw)
                        }
                        cl.cells.forEach { c ->
                            environmentDao.insertCellDevice(c.device)
                            val lc = c.locationCell.copy(locationId = locId)
                            environmentDao.insertLocationCell(lc)
                        }
                        cl.bluetooths.forEach { b ->
                            environmentDao.insertBluetoothDevice(b.device)
                            val lb = b.locationBluetooth.copy(locationId = locId)
                            environmentDao.insertLocationBluetooth(lb)
                        }
                    }
                    val count = environmentDao.getRecordCount()
                    _uiState.update { it.copy(environmentRecordCount = count) }
                    launch(Dispatchers.Main) { onComplete() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getIgnoredVersion(): String = settingsRepository.getIgnoredVersion()

    fun setIgnoredVersion(version: String) {
        settingsRepository.setIgnoredVersion(version)
    }
}
