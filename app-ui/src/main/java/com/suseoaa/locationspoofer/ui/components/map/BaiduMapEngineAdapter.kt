package com.suseoaa.locationspoofer.ui.components.map

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.Stroke
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.model.LatLng
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.AppMapMarker
import com.suseoaa.locationspoofer.ui.components.MarkerType

class BaiduMapControllerImpl(
    private val map: BaiduMap,
    private val mapView: TextureMapView,
    private val context: Context
) : AppMapController {
    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL

    override fun setDarkMode(isDark: Boolean, context: Context) {
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
            LatLng(it.first, it.second)
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
        val bitmap = GaodeMarkerHelper.getMarkerBitmap(context, title, type)
        val descriptor = com.baidu.mapapi.map.BitmapDescriptorFactory.fromBitmap(bitmap)
        val marker = map.addOverlay(
            com.baidu.mapapi.map.MarkerOptions()
                .position(LatLng(lat, lng))
                .title(title)
                .icon(descriptor)
                .anchor(0.5f, 0.9f)
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
        fitBounds(points, padding, padding, padding, padding)
    }

    override fun fitBounds(
        points: List<Pair<Double, Double>>,
        paddingLeft: Int,
        paddingTop: Int,
        paddingRight: Int,
        paddingBottom: Int
    ) {
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
            val verticalDiff = maxLat - minLat
            val centerLat =
                (minLat + maxLat) / 2 - (if (paddingBottom > paddingTop) verticalDiff * 0.15 else 0.0)
            val centerLng = (minLng + maxLng) / 2

            val results = FloatArray(1)
            android.location.Location.distanceBetween(minLat, minLng, maxLat, maxLng, results)
            val distance = results[0]

            val zoom = when {
                distance < 80 -> 18.5f
                distance < 250 -> 17.5f
                distance < 600 -> 16.5f
                distance < 1500 -> 15.5f
                distance < 3500 -> 14.5f
                distance < 8000 -> 13.5f
                distance < 18000 -> 12.5f
                distance < 45000 -> 11.5f
                distance < 100000 -> 10.5f
                distance < 250000 -> 9.5f
                else -> 8.5f
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

    override val cameraTargetLat: Double?
        get() = map.mapStatus?.target?.latitude
    override val cameraTargetLng: Double?
        get() = map.mapStatus?.target?.longitude

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
fun BaiduMapViewContainer(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    isDark: Boolean,
    modifier: Modifier,
    onMapReady: (AppMapController) -> Unit,
    onControllerCreated: (AppMapController) -> Unit
) {
    val baiduMapView = remember {
        TextureMapView(context)
    }

    DisposableEffect(lifecycleOwner.lifecycle, baiduMapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> baiduMapView.onResume()
                Lifecycle.Event.ON_PAUSE -> baiduMapView.onPause()
                Lifecycle.Event.ON_DESTROY -> baiduMapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            baiduMapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            baiduMapView.onPause()
            baiduMapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            baiduMapView.apply {
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                map.setOnMapLoadedCallback {
                    val controller = BaiduMapControllerImpl(map, this, context)
                    onControllerCreated(controller)
                    controller.setDarkMode(isDark, context)
                    onMapReady(controller)
                }
            }
        },
        modifier = modifier
    )
}
