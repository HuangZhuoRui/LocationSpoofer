package com.vincenthzr.locationspoofer.viewmodel

import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.data.db.CollectionRouteRecord
import com.vincenthzr.locationspoofer.data.db.CompleteLocation
import com.vincenthzr.locationspoofer.data.model.CollectionRouteMatcher
import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.data.model.geometry
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun MainViewModel.setRouteCollection(enabled: Boolean) {
    if (_uiState.value.isContinuousScanning || _uiState.value.isStoppingCollection) return
    _uiState.update { it.copy(isRouteCollection = enabled, isDrawingCollectionRoute = enabled && it.collectionRoutePoints.size < 2) }
}

internal fun MainViewModel.addCollectionRoutePoint(lat: Double, lng: Double) {
    if (_uiState.value.isContinuousScanning || _uiState.value.isStoppingCollection) return
    val previous = _uiState.value.collectionRoutePoints.lastOrNull()
    if (previous != null && GeoMath.distance(LatLng(previous.lat, previous.lng), LatLng(lat, lng)) < 2.0) return
    _uiState.update { it.copy(collectionRoutePoints = it.collectionRoutePoints + RoutePoint(lat, lng)) }
}

internal fun MainViewModel.undoCollectionRoutePoint() {
    if (!_uiState.value.isContinuousScanning && !_uiState.value.isStoppingCollection) {
        _uiState.update { it.copy(collectionRoutePoints = it.collectionRoutePoints.dropLast(1)) }
    }
}

internal fun MainViewModel.drawCollectionRoute() {
    if (_uiState.value.isContinuousScanning || _uiState.value.isStoppingCollection) return
    _uiState.update { it.copy(isRouteCollection = true, isDrawingCollectionRoute = true) }
}

internal fun MainViewModel.finishCollectionRouteDrawing() {
    if (_uiState.value.collectionRoutePoints.size >= 2) {
        _uiState.update { it.copy(isDrawingCollectionRoute = false) }
    }
}

internal fun MainViewModel.saveCollectionRouteInfo(id: Long, name: String, remark: String, closePending: Boolean = true, onSaved: () -> Unit = {}) {
    if (_uiState.value.isSavingCollectionInfo || name.isBlank()) return
    _uiState.update { it.copy(isSavingCollectionInfo = true) }
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { environmentDao.updateCollectionRouteInfo(id, name.trim(), remark.trim()) }
            _uiState.update {
                it.copy(isSavingCollectionInfo = false, pendingCollectionRoute = if (closePending) null else it.pendingCollectionRoute)
            }
            onSaved()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _uiState.update { it.copy(isSavingCollectionInfo = false) }
            android.widget.Toast.makeText(context, R.string.collection_info_save_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

internal fun MainViewModel.skipCollectionRouteInfo() {
    if (!_uiState.value.isSavingCollectionInfo) _uiState.update { it.copy(pendingCollectionRoute = null) }
}

internal fun MainViewModel.selectCollectedRoute(id: Long) {
    val route = collectionRoutes.value.firstOrNull { it.route.id == id } ?: return
    val point = route.route.geometry().firstOrNull() ?: return
    pinnedLocationRecordId = null
    _uiState.update {
        it.copy(selectedCollectionRouteId = id, pinnedCollectedLocationId = null, pinnedLocationName = null,
            latitudeInput = point.lat.toString(), longitudeInput = point.lng.toString(),
            routePoints = route.route.geometry().map { p -> RoutePoint(p.lat, p.lng) },
            routePlanStage = com.vincenthzr.locationspoofer.data.model.RoutePlanStage.READY,
            useRealRoute = false)
    }
    evaluateMockCapabilities()
}

internal fun MainViewModel.deleteCollectedRoute(id: Long) {
    viewModelScope.launch(Dispatchers.IO) { environmentDao.deleteCollectionRoute(id) }
    _uiState.update { if (it.selectedCollectionRouteId == id) it.copy(selectedCollectionRouteId = null) else it }
}

/** 界面能力判断与模拟移动使用同一匹配规则，避免路线中部采样稀疏时丢失环境数据。 */
internal suspend fun MainViewModel.environmentRecordsAt(lat: Double, lng: Double): List<CompleteLocation> {
    val position = LatLng(lat, lng)
    val routes = collectionRoutes.value
    val selected = _uiState.value.selectedCollectionRouteId
    val route = CollectionRouteMatcher.routeAt(routes, position, selected)
    if (route != null) return CollectionRouteMatcher.samplesAt(route, position)
    return environmentLocations.value
        .filter { it.location.collectionRouteId == null && GeoMath.distance(position, LatLng(it.location.lat, it.location.lng)) <= 50.0 }
        .sortedBy { GeoMath.distance(position, LatLng(it.location.lat, it.location.lng)) }

}

internal fun MainViewModel.prepareRouteSimulationOptions(onReady: () -> Unit) {
    val point = _uiState.value.routePoints.firstOrNull() ?: return
    viewModelScope.launch {
        _uiState.update { it.copy(latitudeInput = point.lat.toString(), longitudeInput = point.lng.toString()) }
        evaluateMockCapabilitiesSuspend(point.lat, point.lng)
        _uiState.update { state -> state.copy(
            mockWifi = state.mockWifi || state.canMockWifi,
            mockCell = state.mockCell || state.canMockCell,
            mockBluetooth = state.mockBluetooth || state.canMockBluetooth
        ) }
        settingsRepository.mockWifi = _uiState.value.mockWifi
        settingsRepository.mockCell = _uiState.value.mockCell
        settingsRepository.mockBluetooth = _uiState.value.mockBluetooth
        onReady()
    }
}
