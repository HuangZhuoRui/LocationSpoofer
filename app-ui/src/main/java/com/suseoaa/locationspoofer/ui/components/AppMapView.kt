package com.suseoaa.locationspoofer.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.data.model.MapEngine
import com.suseoaa.locationspoofer.ui.components.map.AMapViewContainer
import com.suseoaa.locationspoofer.ui.components.map.BaiduMapViewContainer
import com.suseoaa.locationspoofer.ui.components.map.GoogleMapViewContainer

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
    fun fitBounds(
        points: List<Pair<Double, Double>>,
        paddingLeft: Int,
        paddingTop: Int,
        paddingRight: Int,
        paddingBottom: Int
    ) {
        fitBounds(points, maxOf(paddingLeft, paddingRight, paddingTop, paddingBottom))
    }

    fun moveCamera(lat: Double, lng: Double, zoom: Float? = null)
    val cameraTargetLat: Double?
    val cameraTargetLng: Double?
    fun setOnCameraChangeListener(onFinish: (lat: Double, lng: Double) -> Unit)
    fun setOnCameraMoveListener(onMove: (lat: Double, lng: Double) -> Unit)
    fun disableUiControls()
    fun setMapType(type: AppMapType)
    fun setDarkMode(isDark: Boolean, context: android.content.Context)
}

// Re-export implementations for backwards compatibility if needed
typealias AMapControllerImpl = com.suseoaa.locationspoofer.ui.components.map.AMapControllerImpl
typealias BaiduMapControllerImpl = com.suseoaa.locationspoofer.ui.components.map.BaiduMapControllerImpl
typealias GMapControllerImpl = com.suseoaa.locationspoofer.ui.components.map.GMapControllerImpl
typealias GaodeMarkerHelper = com.suseoaa.locationspoofer.ui.components.map.GaodeMarkerHelper

@Composable
fun AppMapView(
    mapEngine: MapEngine,
    isDomestic: Boolean,
    modifier: Modifier = Modifier,
    onMapReady: (AppMapController) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDark = isSystemInDarkTheme()
    var mapController by remember { mutableStateOf<AppMapController?>(null) }

    LaunchedEffect(isDark, mapController) {
        mapController?.setDarkMode(isDark, context)
    }

    val activeEngine = if (mapEngine == MapEngine.AUTO) {
        MapEngine.AMAP
    } else {
        mapEngine
    }

    when (activeEngine) {
        MapEngine.BAIDU -> {
            BaiduMapViewContainer(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isDark = isDark,
                modifier = modifier,
                onMapReady = onMapReady,
                onControllerCreated = { mapController = it }
            )
        }

        MapEngine.GOOGLE -> {
            GoogleMapViewContainer(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isDark = isDark,
                modifier = modifier,
                onMapReady = onMapReady,
                onControllerCreated = { mapController = it }
            )
        }

        else -> {
            AMapViewContainer(
                context = context,
                lifecycleOwner = lifecycleOwner,
                isDark = isDark,
                modifier = modifier,
                onMapReady = onMapReady,
                onControllerCreated = { mapController = it }
            )
        }
    }
}
