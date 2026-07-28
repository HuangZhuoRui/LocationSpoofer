package com.suseoaa.locationspoofer.ui.screen.spoofing

/**
 * Defines all user actions on the SpoofingScreen.
 * This establishes a strict Unidirectional Data Flow (UDF) via MVI architecture.
 */
sealed class SpoofingIntent {
    // Navigation / Dialogs
    data class SetSaveDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetSavedLocationsVisible(val visible: Boolean) : SpoofingIntent()
    data class SetUpdateDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetMapTypeDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetCustomCoordDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetStartSpoofingDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetAppCoordinateScreenVisible(val visible: Boolean) : SpoofingIntent()

    // Bottom Sheet & Search UI
    data class SetSheetExpanded(val expanded: Boolean) : SpoofingIntent()
    data class SetSearchActive(val active: Boolean) : SpoofingIntent()
    data class UpdateSearchQuery(val query: String) : SpoofingIntent()
    data object PerformSearch : SpoofingIntent()
    data class ClearSearchResults(val clearAll: Boolean = false) : SpoofingIntent()
    data class SetSearchResults(
        val results: List<com.suseoaa.locationspoofer.ui.screen.AppPoiItem>,
        val show: Boolean
    ) : SpoofingIntent()

    // Map Actions
    data class ConfirmMapPoint(val lat: Double, val lng: Double) : SpoofingIntent()
    data class MapPointMoved(val lat: Double, val lng: Double) : SpoofingIntent()
    data object RequestCurrentLocation : SpoofingIntent()
}
