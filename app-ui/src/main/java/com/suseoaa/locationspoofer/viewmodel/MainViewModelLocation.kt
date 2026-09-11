package com.suseoaa.locationspoofer.viewmodel

import android.content.Context
import java.util.Locale
import androidx.lifecycle.viewModelScope
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.suseoaa.locationspoofer.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// MainViewModel 的当前位置获取、原生定位回退与模拟能力评估相关扩展函数

// 当前位置获取
internal fun MainViewModel.fetchCurrentLocation(ctx: Context, forceCallback: ((Double, Double) -> Unit)? = null) {
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

private fun MainViewModel.fallbackToNativeLocation(
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
                ctx.getString(com.suseoaa.locationspoofer.ui.R.string.waiting_gps_signal),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (forceCallback != null) {
                    android.widget.Toast.makeText(
                        ctx,
                        ctx.getString(com.suseoaa.locationspoofer.ui.R.string.native_location_success),
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
                ctx.getString(com.suseoaa.locationspoofer.ui.R.string.location_permission_denied),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun MainViewModel.applyNativeLocation(
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

internal suspend fun MainViewModel.fetchRealLocationSilent(ctx: Context): Pair<Double, Double>? =
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

private fun MainViewModel.fallbackToNativeLocationSilent(
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

private fun MainViewModel.getNativeConverted(
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
internal fun MainViewModel.updateLongitude(value: String) {
    if (isValidCoord(value)) {
        _uiState.update { it.copy(longitudeInput = value, showCoordinateError = false) }
        evaluateMockCapabilities()
    }
}

internal fun MainViewModel.updateLatitude(value: String) {
    if (isValidCoord(value)) {
        _uiState.update { it.copy(latitudeInput = value, showCoordinateError = false) }
        evaluateMockCapabilities()
    }
}

private fun MainViewModel.isValidCoord(value: String): Boolean {
    if (value.isEmpty() || value == "-") return true
    return value.toDoubleOrNull() != null
}

internal fun MainViewModel.evaluateMockCapabilities() {
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

internal fun MainViewModel.selectCollectedLocation(locationId: Long) {
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

internal fun MainViewModel.clearPinnedCollectedLocation() {
    pinnedLocationRecordId = null
    _uiState.update {
        it.copy(
            pinnedCollectedLocationId = null,
            pinnedLocationName = null
        )
    }
    evaluateMockCapabilities()
}

private fun MainViewModel.calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) *
            kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2).let { it * it }
    return 2 * 6378137.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

internal suspend fun MainViewModel.evaluateMockCapabilitiesSuspend(lat: Double, lng: Double) {
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

internal suspend fun MainViewModel.hasLocalWifiWithin50m(lat: Double, lng: Double): Boolean {
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

internal suspend fun MainViewModel.hasLocalCellsWithin50m(lat: Double, lng: Double): Boolean {
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

internal suspend fun MainViewModel.fetchWifiFromWigleSync(lat: Double, lng: Double) {
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

internal suspend fun MainViewModel.fetchCellFromOpenCellIdSync(lat: Double, lng: Double) {
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
