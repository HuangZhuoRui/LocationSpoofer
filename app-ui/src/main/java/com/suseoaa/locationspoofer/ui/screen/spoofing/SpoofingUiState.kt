package com.suseoaa.locationspoofer.ui.screen.spoofing

import com.suseoaa.locationspoofer.ui.screen.AppPoiItem

/**
 * Holds all transient UI state for the SpoofingScreen.
 * This separates view-specific toggles from the core business state (AppState).
 */
data class SpoofingUiState(
    // Dialog Toggles
    val showSaveDialog: Boolean = false,
    val showSavedLocationsDialog: Boolean = false,
    val showMapTypeDialog: Boolean = false,
    val showCustomCoordDialog: Boolean = false,
    val showStartSpoofingDialog: Boolean = false,
    val showAppCoordinateScreen: Boolean = false,

    // 底部抽屉与搜索状态
    val isSheetExpanded: Boolean = true,
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<AppPoiItem> = emptyList(),
    val showSearchResults: Boolean = false,
    val cachedSearchQuery: String = "",
    val cachedSearchAt: Long = 0L,

    // 通知 / 错误
    val toastMessage: String? = null
)
