package com.suseoaa.locationspoofer.ui.components

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.Stroke
import com.baidu.mapapi.model.LatLng
import com.amap.api.maps.model.LatLng as AMapLatLng
import com.amap.api.maps.model.MarkerOptions as AMapMarkerOptions
import com.amap.api.maps.model.PolylineOptions as AMapPolylineOptions
import com.suseoaa.locationspoofer.data.model.AppMapType

import com.google.android.gms.maps.CameraUpdateFactory as GCameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle
import com.suseoaa.locationspoofer.data.model.MapEngine
import com.google.android.gms.maps.MapView as GMapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory as GBitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng as GLatLng
import com.google.android.gms.maps.model.MarkerOptions as GMarkerOptions
import com.google.android.gms.maps.model.PolylineOptions as GPolylineOptions

interface AppMapMarker {
    fun setPosition(lat: Double, lng: Double)
}

enum class MarkerType { GREEN, RED, ORANGE, DEFAULT }

interface AppMapController {
    fun clear()
    fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float)
    fun addCircle(
        lat: Double,
        lng: Double,
        radius: Double,
        fillColorInt: Int,
        strokeColorInt: Int,
        strokeWidth: Float
    )

    fun addMarker(lat: Double, lng: Double, title: String, type: MarkerType): AppMapMarker
    fun animateCamera(lat: Double, lng: Double, zoom: Float? = null)
    fun fitBounds(points: List<Pair<Double, Double>>, padding: Int)
    fun moveCamera(lat: Double, lng: Double, zoom: Float? = null)
    val cameraTargetLat: Double?
    val cameraTargetLng: Double?
    fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit)
    fun setOnCameraMoveListener(onMove: (lat: Double, lng: Double) -> Unit)
    fun disableUiControls()
    fun setMapType(type: AppMapType)
    fun setDarkMode(isDark: Boolean, context: android.content.Context)
}

// ── 坐标系转换工具 ──
// config 中统一存储 GCJ-02；各地图 SDK 的原生坐标在此处统一转换

private fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
    val a = 6378245.0; val ee = 0.00669342162296594323
    if (wgsLng < 72.004 || wgsLng > 137.8347 || wgsLat < 0.8293 || wgsLat > 55.8271)
        return Pair(wgsLat, wgsLng)
    var dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
    var dLon  = transformLon(wgsLng - 105.0, wgsLat - 35.0)
    val radLat = wgsLat / 180.0 * Math.PI
    var magic = Math.sin(radLat); magic = 1 - ee * magic * magic
    val sqrtM = Math.sqrt(magic)
    dLat = dLat * 180.0 / (a * (1 - ee) / (magic * sqrtM) * Math.PI)
    dLon = dLon * 180.0 / (a / sqrtM * Math.cos(radLat) * Math.PI)
    return Pair(wgsLat + dLat, wgsLng + dLon)
}

private val X_PI = Math.PI * 3000.0 / 180.0

private fun bd09ToGcj02(bdLat: Double, bdLng: Double): Pair<Double, Double> {
    val x = bdLng - 0.0065; val y = bdLat - 0.006
    val z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI)
    val theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI)
    return Pair(z * Math.sin(theta), z * Math.cos(theta))
}

private fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
    val z = Math.sqrt(gcjLng * gcjLng + gcjLat * gcjLat) + 0.00002 * Math.sin(gcjLat * X_PI)
    val theta = Math.atan2(gcjLat, gcjLng) + 0.000003 * Math.cos(gcjLng * X_PI)
    return Pair(z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065)
}

private fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
    val a = 6378245.0; val ee = 0.00669342162296594323
    if (gcjLng < 72.004 || gcjLng > 137.8347 || gcjLat < 0.8293 || gcjLat > 55.8271)
        return Pair(gcjLat, gcjLng)
    var dLat = transformLat(gcjLng - 105.0, gcjLat - 35.0)
    var dLon = transformLon(gcjLng - 105.0, gcjLat - 35.0)
    val radLat = gcjLat / 180.0 * Math.PI
    var magic = Math.sin(radLat); magic = 1 - ee * magic * magic
    val sqrtM = Math.sqrt(magic)
    dLat = dLat * 180.0 / (a * (1 - ee) / (magic * sqrtM) * Math.PI)
    dLon = dLon * 180.0 / (a / sqrtM * Math.cos(radLat) * Math.PI)
    return Pair(gcjLat - dLat, gcjLng - dLon)
}


private fun transformLat(x: Double, y: Double): Double {
    var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
    r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    r += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return r
}

private fun transformLon(x: Double, y: Double): Double {
    var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
    r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    r += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    r += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return r
}

class AMapControllerImpl(private val map: AMap) : AppMapController {
    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL

    override fun setDarkMode(isDark: Boolean, context: android.content.Context) {
        isDarkMode = isDark
        setMapType(currentMapType)
    }

    override fun clear() {
        map.clear()
    }

    override fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float) {
        map.addPolyline(
            AMapPolylineOptions().color(colorInt).width(width).apply {
                points.forEach { add(AMapLatLng(it.first, it.second)) }
            }
        )
    }

    override fun addCircle(
        lat: Double,
        lng: Double,
        radius: Double,
        fillColorInt: Int,
        strokeColorInt: Int,
        strokeWidth: Float
    ) {
        map.addCircle(
            com.amap.api.maps.model.CircleOptions()
                .center(AMapLatLng(lat, lng))
                .radius(radius)
                .fillColor(fillColorInt)
                .strokeColor(strokeColorInt)
                .strokeWidth(strokeWidth)
        )
    }

    override fun addMarker(
        lat: Double,
        lng: Double,
        title: String,
        type: MarkerType
    ): AppMapMarker {
        val hue = when (type) {
            MarkerType.GREEN -> BitmapDescriptorFactory.HUE_GREEN
            MarkerType.RED -> BitmapDescriptorFactory.HUE_RED
            MarkerType.ORANGE -> BitmapDescriptorFactory.HUE_ORANGE
            else -> BitmapDescriptorFactory.HUE_RED
        }
        val marker = map.addMarker(
            AMapMarkerOptions()
                .position(AMapLatLng(lat, lng))
                .title(title)
                .icon(BitmapDescriptorFactory.defaultMarker(hue))
        )
        return object : AppMapMarker {
            override fun setPosition(lat: Double, lng: Double) {
                marker?.position = AMapLatLng(lat, lng)
            }
        }
    }

    override fun animateCamera(lat: Double, lng: Double, zoom: Float?) {
        if (zoom != null) map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                AMapLatLng(lat, lng),
                zoom
            )
        )
        else map.moveCamera(CameraUpdateFactory.newLatLng(AMapLatLng(lat, lng)))
    }

    override fun fitBounds(points: List<Pair<Double, Double>>, padding: Int) {
        if (points.isEmpty()) return
        val builder = com.amap.api.maps.model.LatLngBounds.Builder()
        points.forEach { builder.include(AMapLatLng(it.first, it.second)) }
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), padding))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun moveCamera(lat: Double, lng: Double, zoom: Float?) {
        if (zoom != null) map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                AMapLatLng(lat, lng),
                zoom
            )
        )
        else map.moveCamera(CameraUpdateFactory.newLatLng(AMapLatLng(lat, lng)))
    }

    override val cameraTargetLat: Double? get() = map.cameraPosition?.target?.latitude
    override val cameraTargetLng: Double? get() = map.cameraPosition?.target?.longitude

    private var cameraFinishListener: ((Double, Double) -> Unit)? = null
    private var cameraMoveListener: ((Double, Double) -> Unit)? = null

    init {
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(p0: CameraPosition?) {
                p0?.target?.let { cameraMoveListener?.invoke(it.latitude, it.longitude) }
            }
            override fun onCameraChangeFinish(p0: CameraPosition?) {
                p0?.target?.let { cameraFinishListener?.invoke(it.latitude, it.longitude) }
            }
        })
    }

    override fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit) {
        cameraFinishListener = onFinish
    }

    override fun setOnCameraMoveListener(onMove: (lat: Double, lng: Double) -> Unit) {
        cameraMoveListener = onMove
    }

    override fun disableUiControls() {
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.setAllGesturesEnabled(true)
    }

    override fun setMapType(type: AppMapType) {
        currentMapType = type
        when (type) {
            AppMapType.NORMAL -> {
                map.mapType = if (isDarkMode) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
                val cameraPosition = map.cameraPosition ?: return
                val newCam = CameraPosition(
                    cameraPosition.target,
                    cameraPosition.zoom,
                    0f,
                    cameraPosition.bearing
                )
                map.moveCamera(CameraUpdateFactory.newCameraPosition(newCam))
            }

            AppMapType.SATELLITE -> {
                map.mapType = AMap.MAP_TYPE_SATELLITE
                val cameraPosition = map.cameraPosition ?: return
                val newCam = CameraPosition(
                    cameraPosition.target,
                    cameraPosition.zoom,
                    0f,
                    cameraPosition.bearing
                )
                map.moveCamera(CameraUpdateFactory.newCameraPosition(newCam))
            }

            AppMapType.MAP_3D -> {
                map.mapType = if (isDarkMode) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL
                val cameraPosition = map.cameraPosition ?: return
                val newCam = CameraPosition(
                    cameraPosition.target,
                    cameraPosition.zoom,
                    45f,
                    cameraPosition.bearing
                )
                map.moveCamera(CameraUpdateFactory.newCameraPosition(newCam))
            }
        }
    }
}

class GMapControllerImpl(private val map: GoogleMap) : AppMapController {
    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL

    override fun setDarkMode(isDark: Boolean, context: Context) {
        isDarkMode = isDark
        try {
            if (isDark) {
                map.setMapStyle(
                    loadRawResourceStyle(
                        context,
                        com.suseoaa.locationspoofer.R.raw.map_style_dark
                    )
                )
            } else {
                map.setMapStyle(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        setMapType(currentMapType)
    }

    override fun clear() {
        map.clear()
    }

    override fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float) {
        map.addPolyline(
            GPolylineOptions().color(colorInt).width(width).apply {
                points.forEach {
                    val wgs = gcj02ToWgs84(it.first, it.second)
                    add(GLatLng(wgs.first, wgs.second))
                }
            }
        )
    }

    override fun addCircle(
        lat: Double,
        lng: Double,
        radius: Double,
        fillColorInt: Int,
        strokeColorInt: Int,
        strokeWidth: Float
    ) {
        val wgs = gcj02ToWgs84(lat, lng)
        map.addCircle(
            CircleOptions()
                .center(GLatLng(wgs.first, wgs.second))
                .radius(radius)
                .fillColor(fillColorInt)
                .strokeColor(strokeColorInt)
                .strokeWidth(strokeWidth)
        )
    }

    override fun addMarker(
        lat: Double,
        lng: Double,
        title: String,
        type: MarkerType
    ): AppMapMarker {
        val hue = when (type) {
            MarkerType.GREEN -> GBitmapDescriptorFactory.HUE_GREEN
            MarkerType.RED -> GBitmapDescriptorFactory.HUE_RED
            MarkerType.ORANGE -> GBitmapDescriptorFactory.HUE_ORANGE
            else -> GBitmapDescriptorFactory.HUE_RED
        }
        val wgs = gcj02ToWgs84(lat, lng)
        val marker = map.addMarker(
            GMarkerOptions()
                .position(GLatLng(wgs.first, wgs.second))
                .title(title)
                .icon(GBitmapDescriptorFactory.defaultMarker(hue))
        )
        return object : AppMapMarker {
            override fun setPosition(lat: Double, lng: Double) {
                val w = gcj02ToWgs84(lat, lng)
                marker?.position = GLatLng(w.first, w.second)
            }
        }
    }

    override fun animateCamera(lat: Double, lng: Double, zoom: Float?) {
        val wgs = gcj02ToWgs84(lat, lng)
        if (zoom != null) map.animateCamera(
            GCameraUpdateFactory.newLatLngZoom(
                GLatLng(wgs.first, wgs.second),
                zoom
            )
        )
        else map.animateCamera(GCameraUpdateFactory.newLatLng(GLatLng(wgs.first, wgs.second)))
    }

    override fun fitBounds(points: List<Pair<Double, Double>>, padding: Int) {
        if (points.isEmpty()) return
        val builder = LatLngBounds.Builder()
        points.forEach {
            val wgs = gcj02ToWgs84(it.first, it.second)
            builder.include(GLatLng(wgs.first, wgs.second))
        }
        try {
            map.animateCamera(GCameraUpdateFactory.newLatLngBounds(builder.build(), padding))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun moveCamera(lat: Double, lng: Double, zoom: Float?) {
        val wgs = gcj02ToWgs84(lat, lng)
        if (zoom != null) map.moveCamera(
            GCameraUpdateFactory.newLatLngZoom(
                GLatLng(wgs.first, wgs.second),
                zoom
            )
        )
        else map.moveCamera(GCameraUpdateFactory.newLatLng(GLatLng(wgs.first, wgs.second)))
    }

    override val cameraTargetLat: Double? get() {
        val target = map.cameraPosition?.target ?: return null
        return wgs84ToGcj02(target.latitude, target.longitude).first
    }
    override val cameraTargetLng: Double? get() {
        val target = map.cameraPosition?.target ?: return null
        return wgs84ToGcj02(target.latitude, target.longitude).second
    }

    private var cameraFinishListener: ((Double, Double) -> Unit)? = null
    private var cameraMoveListener: ((Double, Double) -> Unit)? = null

    init {
        map.setOnCameraMoveListener {
            val target = map.cameraPosition?.target
            if (target != null) {
                val gcj = wgs84ToGcj02(target.latitude, target.longitude)
                cameraMoveListener?.invoke(gcj.first, gcj.second)
            }
        }
        map.setOnCameraIdleListener {
            val target = map.cameraPosition?.target
            if (target != null) {
                val gcj = wgs84ToGcj02(target.latitude, target.longitude)
                cameraFinishListener?.invoke(gcj.first, gcj.second)
            }
        }
    }

    override fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit) {
        cameraFinishListener = onFinish
    }

    override fun setOnCameraMoveListener(onMove: (lat: Double, lng: Double) -> Unit) {
        cameraMoveListener = onMove
    }

    override fun disableUiControls() {
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.setAllGesturesEnabled(true)
    }

    override fun setMapType(type: AppMapType) {
        currentMapType = type
        when (type) {
            AppMapType.NORMAL -> {
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
                val cameraPosition = map.cameraPosition
                val newCam = com.google.android.gms.maps.model.CameraPosition.builder()
                    .target(cameraPosition.target)
                    .zoom(cameraPosition.zoom)
                    .tilt(0f)
                    .bearing(cameraPosition.bearing)
                    .build()
                map.moveCamera(GCameraUpdateFactory.newCameraPosition(newCam))
            }

            AppMapType.SATELLITE -> {
                map.mapType = GoogleMap.MAP_TYPE_HYBRID
                val cameraPosition = map.cameraPosition
                val newCam = com.google.android.gms.maps.model.CameraPosition.builder()
                    .target(cameraPosition.target)
                    .zoom(cameraPosition.zoom)
                    .tilt(0f)
                    .bearing(cameraPosition.bearing)
                    .build()
                map.moveCamera(GCameraUpdateFactory.newCameraPosition(newCam))
            }

            AppMapType.MAP_3D -> {
                map.mapType = GoogleMap.MAP_TYPE_NORMAL
                map.isBuildingsEnabled = true
                val cameraPosition = map.cameraPosition
                val newCam = com.google.android.gms.maps.model.CameraPosition.builder()
                    .target(cameraPosition.target)
                    .zoom(cameraPosition.zoom)
                    .tilt(45f)
                    .bearing(cameraPosition.bearing)
                    .build()
                map.moveCamera(GCameraUpdateFactory.newCameraPosition(newCam))
            }
        }
    }
}

class BaiduMapControllerImpl(
    private val map: com.baidu.mapapi.map.BaiduMap,
    private val mapView: com.baidu.mapapi.map.TextureMapView
) : AppMapController {
    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL

    override fun setDarkMode(isDark: Boolean, context: android.content.Context) {
        isDarkMode = isDark
        val customStyleOptions = com.baidu.mapapi.map.MapCustomStyleOptions()
        if (isDark) {
            customStyleOptions.customStyleId("ed7541b0077ffda0205ff36f2a5633b1")
            mapView.setMapCustomStyle(
                customStyleOptions,
                object : com.baidu.mapapi.map.CustomMapStyleCallBack {
                    override fun onPreLoadLastCustomMapStyle(p0: String?): Boolean = false
                    override fun onCustomMapStyleLoadSuccess(p0: Boolean, p1: String?): Boolean =
                        true

                    override fun onCustomMapStyleLoadFailed(
                        p0: Int,
                        p1: String?,
                        p2: String?
                    ): Boolean = false
                })
            mapView.setMapCustomStyleEnable(true)
        } else {
            mapView.setMapCustomStyleEnable(false)
        }
        setMapType(currentMapType)
    }

    override fun clear() {
        map.clear()
    }

    override fun addPolyline(points: List<Pair<Double, Double>>, colorInt: Int, width: Float) {
        if (points.size < 2) return
        val latLngList = points.map {
            com.baidu.mapapi.model.LatLng(it.first, it.second)
        }
        map.addOverlay(
            com.baidu.mapapi.map.PolylineOptions()
                .color(colorInt)
                .width(width.toInt())
                .points(latLngList)
        )
    }

    override fun addCircle(
        lat: Double,
        lng: Double,
        radius: Double,
        fillColorInt: Int,
        strokeColorInt: Int,
        strokeWidth: Float
    ) {
        map.addOverlay(
            com.baidu.mapapi.map.CircleOptions()
                .center(LatLng(lat, lng))
                .radius(radius.toInt())
                .fillColor(fillColorInt)
                .stroke(Stroke(strokeWidth.toInt(), strokeColorInt))
        )
    }

    override fun addMarker(
        lat: Double,
        lng: Double,
        title: String,
        type: MarkerType
    ): AppMapMarker {
        val colorResource = when (type) {
            MarkerType.GREEN -> com.baidu.mapapi.map.BitmapDescriptorFactory.fromResource(android.R.drawable.ic_menu_mylocation)
            MarkerType.RED -> com.baidu.mapapi.map.BitmapDescriptorFactory.fromResource(android.R.drawable.ic_menu_mylocation)
            MarkerType.ORANGE -> com.baidu.mapapi.map.BitmapDescriptorFactory.fromResource(android.R.drawable.ic_menu_mylocation)
            else -> com.baidu.mapapi.map.BitmapDescriptorFactory.fromResource(android.R.drawable.ic_menu_mylocation)
        }
        val marker = map.addOverlay(
            com.baidu.mapapi.map.MarkerOptions()
                .position(LatLng(lat, lng))
                .title(title)
                .icon(colorResource)
        ) as? com.baidu.mapapi.map.Marker
        return object : AppMapMarker {
            override fun setPosition(lat: Double, lng: Double) {
                marker?.position = LatLng(lat, lng)
            }
        }
    }

    override fun animateCamera(lat: Double, lng: Double, zoom: Float?) {
        val update = if (zoom != null) com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLngZoom(
            LatLng(lat, lng), zoom
        )
        else com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLng(
            LatLng(lat, lng)
        )
        map.animateMapStatus(update)
    }

    override fun fitBounds(points: List<Pair<Double, Double>>, padding: Int) {
        if (points.isEmpty()) return
        try {
            var minLat = 90.0
            var maxLat = -90.0
            var minLng = 180.0
            var maxLng = -180.0
            points.forEach {
                if (it.first < minLat) minLat = it.first
                if (it.first > maxLat) maxLat = it.first
                if (it.second < minLng) minLng = it.second
                if (it.second > maxLng) maxLng = it.second
            }
            val centerLat = (minLat + maxLat) / 2
            val centerLng = (minLng + maxLng) / 2

            val results = FloatArray(1)
            android.location.Location.distanceBetween(minLat, minLng, maxLat, maxLng, results)
            val distance = results[0]

            // 百度地图距离与缩放级别的经验映射
            // 如果处于小窗模式 (padding 较小)，适当缩小缩放级别以显示更多内容
            val paddingFactor = if (padding < 50) 1.5f else 1.0f
            val adjustedDistance = distance * paddingFactor

            val zoom = when {
                adjustedDistance < 50 -> 20f
                adjustedDistance < 200 -> 18f
                adjustedDistance < 500 -> 17f
                adjustedDistance < 1000 -> 16f
                adjustedDistance < 2000 -> 15f
                adjustedDistance < 5000 -> 14f
                adjustedDistance < 10000 -> 13f
                adjustedDistance < 20000 -> 12f
                adjustedDistance < 50000 -> 11f
                adjustedDistance < 100000 -> 10f
                adjustedDistance < 200000 -> 9f
                adjustedDistance < 500000 -> 8f
                adjustedDistance < 1000000 -> 7f
                adjustedDistance < 2000000 -> 6f
                else -> 5f
            }

            val update = com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLngZoom(
                LatLng(centerLat, centerLng), zoom
            )
            map.animateMapStatus(update)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun moveCamera(lat: Double, lng: Double, zoom: Float?) {
        val update = if (zoom != null) com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLngZoom(
            LatLng(lat, lng), zoom
        )
        else com.baidu.mapapi.map.MapStatusUpdateFactory.newLatLng(
            LatLng(lat, lng)
        )
        map.setMapStatus(update)
    }

    override val cameraTargetLat: Double? get() {
        val target = map.mapStatus?.target ?: return null
        return target.latitude
    }
    override val cameraTargetLng: Double? get() {
        val target = map.mapStatus?.target ?: return null
        return target.longitude
    }

    private var cameraFinishListener: ((Double, Double) -> Unit)? = null
    private var cameraMoveListener: ((Double, Double) -> Unit)? = null

    init {
        map.setOnMapStatusChangeListener(object :
            BaiduMap.OnMapStatusChangeListener {
            private var lastReason: Int = 0
            override fun onMapStatusChangeStart(p0: com.baidu.mapapi.map.MapStatus?) {}
            override fun onMapStatusChangeStart(p0: com.baidu.mapapi.map.MapStatus?, p1: Int) {
                lastReason = p1
            }

            override fun onMapStatusChange(p0: com.baidu.mapapi.map.MapStatus?) {
                if (lastReason == BaiduMap.OnMapStatusChangeListener.REASON_GESTURE) {
                    p0?.target?.let {
                        cameraMoveListener?.invoke(it.latitude, it.longitude)
                    }
                }
            }
            override fun onMapStatusChangeFinish(p0: com.baidu.mapapi.map.MapStatus?) {
                if (lastReason == BaiduMap.OnMapStatusChangeListener.REASON_GESTURE) {
                    p0?.target?.let {
                        cameraFinishListener?.invoke(it.latitude, it.longitude)
                    }
                }
            }
        })
    }

    override fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit) {
        cameraFinishListener = onFinish
    }

    override fun setOnCameraMoveListener(onMove: (lat: Double, lng: Double) -> Unit) {
        cameraMoveListener = onMove
    }

    override fun disableUiControls() {
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isOverlookingGesturesEnabled = true
        map.uiSettings.isRotateGesturesEnabled = true
    }

    override fun setMapType(type: AppMapType) {
        currentMapType = type
        when (type) {
            AppMapType.NORMAL -> {
                map.mapType = BaiduMap.MAP_TYPE_NORMAL
            }

            AppMapType.SATELLITE -> {
                map.mapType = BaiduMap.MAP_TYPE_SATELLITE
            }

            AppMapType.MAP_3D -> {
                map.mapType = BaiduMap.MAP_TYPE_NORMAL
            }
        }
    }
}

@Composable
fun AppMapView(
    mapEngine: MapEngine,
    isDomestic: Boolean,
    modifier: Modifier = Modifier,
    onMapReady: (AppMapController) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    var mapController by remember { mutableStateOf<AppMapController?>(null) }

    LaunchedEffect(isDark, mapController) {
        mapController?.setDarkMode(isDark, context)
    }

    val activeEngine = if (mapEngine == MapEngine.AUTO) {
        if (isDomestic) MapEngine.AMAP else MapEngine.GOOGLE
    } else {
        mapEngine
    }

    if (activeEngine == MapEngine.AMAP) {
        val amapView = remember {
            val view = TextureMapView(context)
            view.onCreate(Bundle())
            view
        }
        DisposableEffect(lifecycle, amapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> amapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> amapView.onPause()
                    Lifecycle.Event.ON_DESTROY -> amapView.onDestroy()
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) amapView.onResume()
            onDispose {
                lifecycle.removeObserver(observer)
                amapView.onPause()
                amapView.onDestroy()
            }
        }
        AndroidView(
            factory = {
                amapView.apply {
                    setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                    map.setOnMapLoadedListener {
                        val controller = AMapControllerImpl(map)
                        mapController = controller
                        controller.setDarkMode(isDark, context)
                        onMapReady(controller)
                    }
                }
            },
            modifier = modifier
        )
    } else if (activeEngine == MapEngine.BAIDU) {
        val baiduMapView = remember {
            val view = com.baidu.mapapi.map.TextureMapView(context)
            view
        }
        DisposableEffect(lifecycle, baiduMapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> baiduMapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> baiduMapView.onPause()
                    Lifecycle.Event.ON_DESTROY -> baiduMapView.onDestroy()
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) baiduMapView.onResume()
            onDispose {
                lifecycle.removeObserver(observer)
                baiduMapView.onPause()
                baiduMapView.onDestroy()
            }
        }
        AndroidView(
            factory = {
                baiduMapView.apply {
                    setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                    map.setOnMapLoadedCallback {
                        val controller = BaiduMapControllerImpl(map, this)
                        mapController = controller
                        controller.setDarkMode(isDark, context)
                        onMapReady(controller)
                    }
                }
            },
            modifier = modifier
        )
    } else {
        val gmapView = remember {
            val view = GMapView(context)
            view.onCreate(Bundle())
            view
        }
        DisposableEffect(lifecycle, gmapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> gmapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> gmapView.onPause()
                    Lifecycle.Event.ON_DESTROY -> gmapView.onDestroy()
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) gmapView.onResume()
            onDispose {
                lifecycle.removeObserver(observer)
                gmapView.onPause()
                gmapView.onDestroy()
            }
        }
        AndroidView(
            factory = {
                gmapView.apply {
                    setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                    getMapAsync { map ->
                        val controller = GMapControllerImpl(map)
                        mapController = controller
                        controller.setDarkMode(isDark, context)
                        onMapReady(controller)
                    }
                }
            },
            modifier = modifier
        )
    }
}
