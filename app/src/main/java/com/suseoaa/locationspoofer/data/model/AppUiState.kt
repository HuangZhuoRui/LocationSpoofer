package com.suseoaa.locationspoofer.data.model

import androidx.annotation.StringRes
import com.suseoaa.locationspoofer.R

enum class WifiLoadStatus { IDLE, LOADING, DONE }

enum class SimMode(@StringRes val labelResId: Int, val speedMs: Double) {
    STILL(R.string.still, 0.0),
    WALKING(R.string.walking, 1.4),
    RUNNING(R.string.running, 3.0),
    CYCLING(R.string.cycling, 5.5),
    DRIVING(R.string.driving, 15.0),
    CUSTOM(R.string.custom, 0.0)
}

enum class SearchMode {
    NETWORK,
    LOCAL
}

/** 路线规划阶段 */
enum class RoutePlanStage {
    /** 未开始，默认状态 */
    IDLE,

    /** 正在点击地图添加路点 */
    SELECTING,

    /** 已结束选点，等待配置并启动 */
    READY,

    /** 路线模拟运行中 */
    RUNNING
}

/** 路线运行模式 */
enum class RouteRunMode {
    /** 手动模式：摇杆控制移动方向和速度 */
    MANUAL,

    /** 循环模式：按路线自动来回移动 */
    LOOP
}

enum class AppMapType {
    NORMAL,
    SATELLITE,
    MAP_3D
}

data class AppState(
    val mapType: AppMapType = AppMapType.NORMAL,
    val mapEngine: MapEngine = MapEngine.AUTO,
    val isInitializing: Boolean = true,
    val isLanguageSet: Boolean = true, // 默认为 true，以避免在不需要时发生闪烁
    val currentLanguage: String = "",
    val hasRootAccess: Boolean = false,
    val isLSPosedActive: Boolean = false,
    val longitudeInput: String = "",
    val latitudeInput: String = "",
    val showCoordinateError: Boolean = false,
    val isSavingConfig: Boolean = false,
    val isSpoofingActive: Boolean = false,
    val wifiLoadStatus: WifiLoadStatus = WifiLoadStatus.IDLE,
    val wifiApCount: Int = 0,
    val savedLocations: List<SavedLocation> = emptyList(),
    val searchKeyword: String = "",
    val searchMode: SearchMode = SearchMode.NETWORK,
    val searchResults: List<SavedLocation> = emptyList(),
    val simBearing: Float = 0f,
    val savedRoutes: List<SavedRoute> = emptyList(),
    // 路线规划
    val routePoints: List<RoutePoint> = emptyList(),
    val routePlanStage: RoutePlanStage = RoutePlanStage.IDLE,
    /** 路线运行模式（手动 / 循环） */
    val routeRunMode: RouteRunMode = RouteRunMode.MANUAL,
    /** 循环模式使用的速度 */
    val routeSimMode: SimMode = SimMode.WALKING,
    /** 自定义速度 (m/s)，仅当 routeSimMode == CUSTOM 时使用 */
    val customSpeedMs: Double = 1.5,
    /** 是否使用真实路线规划 */
    val useRealRoute: Boolean = false,
    /** 是否正在向地图API请求真实路线 */
    val isFetchingRoute: Boolean = false,
    /** 首页地图已确认的选点（点击地图后出现确认按钮，确认后填充坐标） */
    val mapConfirmedPoint: Pair<Double, Double>? = null,
    val amapApiKey: String = "",
    val baiduApiKey: String = "",
    val googleApiKey: String = "",
    val wigleToken: String = "",
    val opencellidToken: String = "",
    val appSha1: String = "",
    val appCoordinateSystems: Map<String, String> = emptyMap(),
    val isContinuousScanning: Boolean = false,
    val environmentRecordCount: Int = 0,
    val scannedWifiCount: Int = 0,
    val scannedCellCount: Int = 0,
    val scannedBluetoothCount: Int = 0,
    val hookedApps: List<AppInfoItem> = emptyList(),
    // 采集到的本地环境数据
    val collectedWifiJson: String = "[]",
    val collectedCellJson: String = "[]",
    val collectedBluetoothJson: String = "[]",
    // 模拟开关
    val mockWifi: Boolean = true,
    val mockCell: Boolean = true,
    val mockBluetooth: Boolean = true,
    val enableJitter: Boolean = true,
    val altitudeInput: String = "0.0",
    val satelliteCountInput: String = "20",
    val canMockWifi: Boolean = false,
    val canMockCell: Boolean = false,
    val canMockBluetooth: Boolean = false,

    // Data Management
    val isManageDataScreen: Boolean = false,
    val manageDataList: List<com.suseoaa.locationspoofer.data.db.CompleteLocation> = emptyList(),
    val manageDataIsLoading: Boolean = false
)

data class AppInfoItem(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean
)
