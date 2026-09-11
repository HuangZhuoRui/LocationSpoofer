package com.suseoaa.locationspoofer.ui.components.map

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng as AMapLatLng
import com.amap.api.maps.model.MarkerOptions as AMapMarkerOptions
import com.amap.api.maps.model.PolylineOptions as AMapPolylineOptions
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.AppMapMarker
import com.suseoaa.locationspoofer.ui.components.MarkerType

class AMapControllerImpl(
    private val map: AMap,
    private val context: Context
) : AppMapController {
    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL

    override fun setDarkMode(isDark: Boolean, context: Context) {
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
        val bitmap = GaodeMarkerHelper.getMarkerBitmap(context, title, type)
        val marker = map.addMarker(
            AMapMarkerOptions()
                .position(AMapLatLng(lat, lng))
                .title(title)
                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                .anchor(0.5f, 0.9f)
        )
        return object : AppMapMarker {
            override fun setPosition(lat: Double, lng: Double) {
                marker?.position = AMapLatLng(lat, lng)
            }
        }
    }

    override fun animateCamera(lat: Double, lng: Double, zoom: Float?) {
        if (zoom != null) map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                AMapLatLng(lat, lng),
                zoom
            )
        )
        else map.animateCamera(CameraUpdateFactory.newLatLng(AMapLatLng(lat, lng)))
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
        val builder = com.amap.api.maps.model.LatLngBounds.Builder()
        points.forEach { builder.include(AMapLatLng(it.first, it.second)) }
        try {
            val bounds = builder.build()
            try {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBoundsRect(
                        bounds,
                        paddingLeft,
                        paddingRight,
                        paddingTop,
                        paddingBottom
                    )
                )
            } catch (t: Throwable) {
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        maxOf(paddingLeft, paddingRight, paddingTop, paddingBottom)
                    )
                )
            }
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

@Composable
fun AMapViewContainer(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    isDark: Boolean,
    modifier: Modifier,
    onMapReady: (AppMapController) -> Unit,
    onControllerCreated: (AppMapController) -> Unit
) {
    val amapView = remember {
        val view = TextureMapView(context)
        view.onCreate(Bundle())
        view
    }

    DisposableEffect(lifecycleOwner.lifecycle, amapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> amapView.onResume()
                Lifecycle.Event.ON_PAUSE -> amapView.onPause()
                Lifecycle.Event.ON_DESTROY -> amapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            amapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            amapView.onPause()
            amapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            amapView.apply {
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                map.setOnMapLoadedListener {
                    val controller = AMapControllerImpl(map, context)
                    onControllerCreated(controller)
                    controller.setDarkMode(isDark, context)
                    onMapReady(controller)
                }
            }
        },
        modifier = modifier
    )
}
