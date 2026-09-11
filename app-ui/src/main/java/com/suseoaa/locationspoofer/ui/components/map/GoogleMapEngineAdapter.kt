package com.suseoaa.locationspoofer.ui.components.map

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory as GCameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView as GMapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory as GBitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng as GLatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle
import com.google.android.gms.maps.model.MarkerOptions as GMarkerOptions
import com.google.android.gms.maps.model.PolylineOptions as GPolylineOptions
import com.suseoaa.locationspoofer.ui.R
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.AppMapMarker
import com.suseoaa.locationspoofer.ui.components.MarkerType

class GMapControllerImpl(
    private val map: GoogleMap,
    private val context: Context
) : AppMapController {
    private var isDarkMode: Boolean = false
    private var currentMapType: AppMapType = AppMapType.NORMAL

    override fun setDarkMode(isDark: Boolean, context: Context) {
        isDarkMode = isDark
        try {
            if (isDark) {
                map.setMapStyle(
                    loadRawResourceStyle(
                        context,
                        R.raw.map_style_dark
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
        val bitmap = GaodeMarkerHelper.getMarkerBitmap(context, title, type)
        val wgs = gcj02ToWgs84(lat, lng)
        val marker = map.addMarker(
            GMarkerOptions()
                .position(GLatLng(wgs.first, wgs.second))
                .title(title)
                .icon(GBitmapDescriptorFactory.fromBitmap(bitmap))
                .anchor(0.5f, 0.9f)
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
        val builder = LatLngBounds.Builder()
        points.forEach {
            val wgs = gcj02ToWgs84(it.first, it.second)
            builder.include(GLatLng(wgs.first, wgs.second))
        }
        try {
            val bounds = builder.build()
            try {
                map.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
                map.animateCamera(GCameraUpdateFactory.newLatLngBounds(bounds, 0))
            } catch (t: Throwable) {
                map.animateCamera(
                    GCameraUpdateFactory.newLatLngBounds(
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
        val wgs = gcj02ToWgs84(lat, lng)
        if (zoom != null) map.moveCamera(
            GCameraUpdateFactory.newLatLngZoom(
                GLatLng(wgs.first, wgs.second),
                zoom
            )
        )
        else map.moveCamera(GCameraUpdateFactory.newLatLng(GLatLng(wgs.first, wgs.second)))
    }

    override val cameraTargetLat: Double?
        get() {
            val target = map.cameraPosition.target
            return wgs84ToGcj02(target.latitude, target.longitude).first
        }
    override val cameraTargetLng: Double?
        get() {
            val target = map.cameraPosition.target
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

@Composable
fun GoogleMapViewContainer(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    isDark: Boolean,
    modifier: Modifier,
    onMapReady: (AppMapController) -> Unit,
    onControllerCreated: (AppMapController) -> Unit
) {
    val gmapView = remember {
        GMapView(context).apply {
            onCreate(Bundle())
        }
    }

    DisposableEffect(lifecycleOwner.lifecycle, gmapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> gmapView.onResume()
                Lifecycle.Event.ON_PAUSE -> gmapView.onPause()
                Lifecycle.Event.ON_DESTROY -> gmapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            gmapView.onResume()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gmapView.onPause()
            gmapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            gmapView.apply {
                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                getMapAsync { map ->
                    val controller = GMapControllerImpl(map, context)
                    onControllerCreated(controller)
                    controller.setDarkMode(isDark, context)
                    onMapReady(controller)
                }
            }
        },
        modifier = modifier
    )
}
