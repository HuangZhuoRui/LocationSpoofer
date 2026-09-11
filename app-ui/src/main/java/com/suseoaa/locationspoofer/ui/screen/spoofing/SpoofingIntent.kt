package com.suseoaa.locationspoofer.ui.screen.spoofing

/**
 * 定义 SpoofingScreen 上的所有用户操作。
 * 通过 MVI 架构建立严格的单向数据流 (UDF)。
 */
sealed class SpoofingIntent {
    // 导航 / 弹窗
    data class SetSaveDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetSavedLocationsVisible(val visible: Boolean) : SpoofingIntent()
    data class SetMapTypeDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetCustomCoordDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetStartSpoofingDialogVisible(val visible: Boolean) : SpoofingIntent()
    data class SetAppCoordinateScreenVisible(val visible: Boolean) : SpoofingIntent()

    // 底部抽屉与搜索界面
    data class SetSheetExpanded(val expanded: Boolean) : SpoofingIntent()
    data class SetSearchActive(val active: Boolean) : SpoofingIntent()
    data class UpdateSearchQuery(val query: String) : SpoofingIntent()
    data object PerformSearch : SpoofingIntent()
    data object HideSearchResults : SpoofingIntent()
    data class ClearSearchResults(val clearAll: Boolean = false) : SpoofingIntent()
    data class SetSearchResults(
        val results: List<com.suseoaa.locationspoofer.ui.screen.AppPoiItem>,
        val show: Boolean,
        val query: String = ""
    ) : SpoofingIntent()

    // 地图操作
    data class ConfirmMapPoint(val lat: Double, val lng: Double) : SpoofingIntent()
    data class MapPointMoved(val lat: Double, val lng: Double) : SpoofingIntent()
    data object RequestCurrentLocation : SpoofingIntent()
}
