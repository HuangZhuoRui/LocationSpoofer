package com.vincenthzr.locationspoofer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.vincenthzr.locationspoofer.data.db.EnvironmentDao
import com.vincenthzr.locationspoofer.data.db.LocationRecord
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.data.model.AppMapType
import com.vincenthzr.locationspoofer.data.model.MapEngine
import com.vincenthzr.locationspoofer.data.model.RootSolution
import com.vincenthzr.locationspoofer.data.model.VendorScheme
import com.vincenthzr.locationspoofer.data.repository.LocationRepository
import com.vincenthzr.locationspoofer.data.repository.SettingsRepository
import com.vincenthzr.locationspoofer.data.repository.WifiRepository
import com.vincenthzr.locationspoofer.data.state.SpoofingState
import com.vincenthzr.locationspoofer.ui.screen.spoofing.SpoofingUiState
import com.vincenthzr.locationspoofer.utils.EnvironmentScanner
import com.vincenthzr.locationspoofer.utils.LSPosedManager
import com.vincenthzr.locationspoofer.utils.OpenCellIdClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface FavoriteToggleResult {
    data class Added(val name: String) : FavoriteToggleResult
    data class Removed(val name: String) : FavoriteToggleResult
    data object Failed : FavoriteToggleResult
}

class MainViewModel(
    internal val locationRepository: LocationRepository,
    internal val settingsRepository: SettingsRepository,
    internal val lsposedManager: LSPosedManager,
    internal val environmentScanner: EnvironmentScanner,
    internal val environmentDao: EnvironmentDao,
    internal val wifiRepository: WifiRepository,
    internal val opencellidClient: OpenCellIdClient,
    internal val context: Context
) : ViewModel() {
    internal var lastMapMoveTime = 0L
    internal var mapMoveJob: Job? = null

    internal val _uiState = MutableStateFlow(
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
            rootSolution = try {
                RootSolution.valueOf(settingsRepository.getRootSolution())
            } catch (e: Exception) {
                RootSolution.AUTO
            },
            vendorScheme = VendorScheme.fromId(settingsRepository.getVendorOverride()),
            savedLocations = settingsRepository.getSavedLocations(),
            savedRoutes = emptyList(), // 将由 Room Flow 填充
            currentLanguage = settingsRepository.getLanguage(),
            isLanguageSet = settingsRepository.isLanguageSet(),
            appCoordinateSystems = settingsRepository.getAppCoordinateSystems(),
            systemHookPackages = settingsRepository.getSystemHookPackages(),
            isSystemHookGlobalMode = settingsRepository.isSystemHookGlobalMode,
            mockWifi = settingsRepository.mockWifi,
            mockCell = settingsRepository.mockCell,
            mockBluetooth = settingsRepository.mockBluetooth,
            enableJitter = settingsRepository.enableJitter,
            restartAppsOnSpoof = settingsRepository.restartAppsOnSpoof,
            altitudeInput = settingsRepository.altitude,
            satelliteCountInput = settingsRepository.satelliteCount,
            wigleToken = settingsRepository.getWigleApiToken(),
            opencellidToken = settingsRepository.getOpencellidApiToken()
        )
    )
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    internal val _spoofingUiState =
        MutableStateFlow(SpoofingUiState())
    val spoofingUiState: StateFlow<SpoofingUiState> =
        _spoofingUiState.asStateFlow()

    internal var locationSyncJob: Job? = null
    internal var autoRouteJob: Job? = null
    internal var continuousScanJob: Job? = null

    init {
        initialize()
    }

    data class ClusterData(
        val center: LocationRecord,
        var count: Int,
        var hasWifi: Boolean,
        var hasBluetooth: Boolean,
        var hasCell: Boolean
    )

    internal var pinnedLocationRecordId: Long? = null

    internal val favoriteToggleMutex = kotlinx.coroutines.sync.Mutex()

    internal var lastDbQueryLat: Double = 0.0
    internal var lastDbQueryLng: Double = 0.0
}
