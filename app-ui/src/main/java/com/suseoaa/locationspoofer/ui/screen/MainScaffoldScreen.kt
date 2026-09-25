package com.suseoaa.locationspoofer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suseoaa.locationspoofer.ui.BuildConfig
import com.suseoaa.locationspoofer.ui.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.AppMapView
import com.suseoaa.locationspoofer.ui.liquid.AndroidFloatingBottomBar
import com.suseoaa.locationspoofer.ui.liquid.FloatingBottomBarItem
import com.suseoaa.locationspoofer.ui.screen.spoofing.SpoofingIntent
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import com.suseoaa.locationspoofer.viewmodel.UpdateViewModel
import com.suseoaa.locationspoofer.viewmodel.getIgnoredVersion
import com.suseoaa.locationspoofer.viewmodel.handleSpoofingIntent
import com.suseoaa.locationspoofer.viewmodel.isDomesticEnvironment
import com.suseoaa.locationspoofer.viewmodel.setIgnoredVersion
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

enum class BottomTab(val icon: ImageVector) {
    Location(Icons.Rounded.MyLocation),
    Route(Icons.Rounded.Route),
    Features(Icons.Rounded.Extension),
    Info(Icons.Rounded.Settings);

    @Composable
    fun getLabel(): String {
        return when (this) {
            Location -> stringResource(R.string.tab_location)
            Route -> stringResource(R.string.tab_route)
            Features -> stringResource(R.string.tab_features)
            Info -> stringResource(R.string.tab_info)
        }
    }
}

enum class MainSubScreen {
    None,
    CoordinateConfig,
    ScannerMap,
    ManageData,
    Update,
    LanguageSettings,
    MapEngineSettings,
    SignatureAuth,
    RootDiagnostics,
    VendorScheme,
    BackgroundKeepAlive,
    EnvTokens,
    SystemHookApps
}

@Composable
fun MainScaffoldScreen(
    viewModel: MainViewModel,
    uiState: AppState
) {
    val pageCount = BottomTab.values().size
    var selectedTab by remember { mutableIntStateOf(BottomTab.Location.ordinal) }
    var currentSubScreen by remember { mutableStateOf(MainSubScreen.None) }
    var activeSubScreen by remember { mutableStateOf(MainSubScreen.None) }
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    val currentSelectedTab by rememberUpdatedState(selectedTab)
    val isDark = isSystemInDarkTheme()
    val updateViewModel: UpdateViewModel = koinViewModel()

    val backdrop = rememberLayerBackdrop()

    val updateUiState by updateViewModel.uiState.collectAsState()
    var hasAutoCheckedUpdates by remember { mutableStateOf(false) }
    var showStartupUpdateDialog by remember { mutableStateOf(false) }
    val targetReleases = remember(updateUiState.releases, uiState.checkBetaUpdates) {
        if (uiState.checkBetaUpdates) updateUiState.releases else updateUiState.releases.filter { !it.isPrerelease }
    }
    val latestRelease = targetReleases.firstOrNull()

    // 软件启动进入主界面时，立即在后台检索是否有新版本发布
    LaunchedEffect(Unit) {
        updateViewModel.fetchReleases()
    }

    // 检测到未被忽略的新版本时弹出启动更新提示框
    LaunchedEffect(targetReleases, updateUiState.isLoading) {
        if (!hasAutoCheckedUpdates && !updateUiState.isLoading && targetReleases.isNotEmpty()) {
            if (latestRelease != null) {
                val latestVersion = latestRelease.versionName
                val currentVersion = BuildConfig.VERSION_NAME
                val ignoredVersion = viewModel.getIgnoredVersion()
                if (isNewerVersion(
                        latestVersion,
                        currentVersion
                    ) && latestVersion != ignoredVersion
                ) {
                    showStartupUpdateDialog = true
                }
            }
            hasAutoCheckedUpdates = true
        }
    }

    LaunchedEffect(currentSubScreen) {
        if (currentSubScreen != MainSubScreen.None) {
            activeSubScreen = currentSubScreen
        }
    }

    // 针对非首页 Tab（路线/功能/信息）的系统返回拦截：返回到定位首页
    BackHandler(enabled = currentSubScreen == MainSubScreen.None && selectedTab != BottomTab.Location.ordinal) {
        selectedTab = BottomTab.Location.ordinal
    }

    LaunchedEffect(selectedTab, mapController, uiState.mapType) {
        if (selectedTab == 0) {
            mapController?.clear()
            mapController?.setMapType(uiState.mapType)
        }
    }

    val selectPage: (Int) -> Unit = { index ->
        selectedTab = index.coerceIn(0, pageCount - 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                AnimatedVisibility(
                    visible = currentSubScreen == MainSubScreen.None,
                    enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 14.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        AndroidFloatingBottomBar(
                            selectedIndex = { selectedTab },
                            onSelected = selectPage,
                            backdrop = backdrop,
                            tabsCount = pageCount
                        ) {
                            BottomTab.values().forEachIndexed { index, tab ->
                                val isSelected = selectedTab == index
                                FloatingBottomBarItem(
                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                                    onClick = { selectPage(index) }
                                ) {
                                    val label = tab.getLabel()
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = label,
                                        tint = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (isDark) 0.65f else 0.70f
                                        ),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = if (isDark) 0.65f else 0.70f
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                // 底层地图层（定位与路线 Tab 共享）
                Box(modifier = Modifier.fillMaxSize()) {
                    AppMapView(
                        modifier = Modifier.fillMaxSize(),
                        mapEngine = uiState.mapEngine,
                        isDomestic = viewModel.isDomesticEnvironment(),
                        onMapReady = { controller ->
                            mapController = controller
                            controller.disableUiControls()
                            controller.setMapType(uiState.mapType)
                            val initLat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
                            val initLng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
                            controller.moveCamera(initLat, initLng, 15f)

                            controller.setOnCameraChangeListener { lat, lng ->
                                if (currentSelectedTab == BottomTab.Location.ordinal) {
                                    viewModel.handleSpoofingIntent(
                                        SpoofingIntent.ConfirmMapPoint(
                                            lat,
                                            lng
                                        )
                                    )
                                }
                            }
                            controller.setOnCameraMoveListener { lat, lng ->
                                if (currentSelectedTab == BottomTab.Location.ordinal) {
                                    viewModel.handleSpoofingIntent(
                                        SpoofingIntent.MapPointMoved(
                                            lat,
                                            lng
                                        )
                                    )
                                }
                            }
                        }
                    )

                    // 定位选点十字准心（仅在定位 Tab 显示）
                    AnimatedVisibility(
                        visible = selectedTab == 0,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(
                            Icons.Rounded.AddLocationAlt,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier
                                .size(36.dp)
                                .padding(bottom = 16.dp)
                        )
                    }
                }

                // 底部 Tab 内容切换（纯左右完整切换，无渐变）
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val isForward = targetState > initialState
                        if (isForward) {
                            slideInHorizontally(
                                animationSpec = tween(
                                    300,
                                    easing = FastOutSlowInEasing
                                )
                            ) { width -> width }
                                .togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(
                                            300,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) { width -> -width })
                        } else {
                            slideInHorizontally(
                                animationSpec = tween(
                                    300,
                                    easing = FastOutSlowInEasing
                                )
                            ) { width -> -width }
                                .togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = tween(
                                            300,
                                            easing = FastOutSlowInEasing
                                        )
                                    ) { width -> width })
                        }
                    },
                    label = "main_tabs_transition"
                ) { targetTab ->
                    when (targetTab) {
                        BottomTab.Location.ordinal -> com.suseoaa.locationspoofer.ui.screen.tabs.LocationTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            mapController = mapController,
                            tabBarHeight = paddingValues.calculateBottomPadding()
                        )

                        BottomTab.Route.ordinal -> com.suseoaa.locationspoofer.ui.screen.tabs.RouteTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            mapController = mapController,
                            isActive = true,
                            bottomBarHeight = paddingValues.calculateBottomPadding()
                        )

                        BottomTab.Features.ordinal -> com.suseoaa.locationspoofer.ui.screen.tabs.FeaturesTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            tabBarHeight = paddingValues.calculateBottomPadding(),
                            onNavigateToCoordinate = {
                                currentSubScreen = MainSubScreen.CoordinateConfig
                            },
                            onNavigateToScanner = { currentSubScreen = MainSubScreen.ScannerMap },
                            onNavigateToManageData = { currentSubScreen = MainSubScreen.ManageData },
                            onNavigateToSystemHookApps = { currentSubScreen = MainSubScreen.SystemHookApps }
                        )

                        BottomTab.Info.ordinal -> com.suseoaa.locationspoofer.ui.screen.tabs.InfoTab(
                            viewModel = viewModel,
                            uiState = uiState,
                            updateUiState = updateUiState,
                            tabBarHeight = paddingValues.calculateBottomPadding(),
                            onNavigateToUpdate = { currentSubScreen = MainSubScreen.Update },
                            onNavigateToLanguage = { currentSubScreen = MainSubScreen.LanguageSettings },
                            onNavigateToMapEngine = { currentSubScreen = MainSubScreen.MapEngineSettings },
                            onNavigateToSignatureAuth = { currentSubScreen = MainSubScreen.SignatureAuth },
                            onNavigateToRootDiagnostics = { currentSubScreen = MainSubScreen.RootDiagnostics },
                            onNavigateToVendorScheme = { currentSubScreen = MainSubScreen.VendorScheme },
                            onNavigateToBackgroundKeepAlive = { currentSubScreen = MainSubScreen.BackgroundKeepAlive },
                            onNavigateToEnvTokens = { currentSubScreen = MainSubScreen.EnvTokens }
                        )
                    }
                }
            }
        }

        // 全屏子页面覆盖层（纯左右完整滑入与退出，无内部冲突动画）
        AnimatedVisibility(
            visible = currentSubScreen != MainSubScreen.None,
            enter = slideInHorizontally(
                tween(
                    300,
                    easing = FastOutSlowInEasing
                )
            ) { width -> width },
            exit = slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { width -> width }
        ) {
            val subScreenToRender =
                if (currentSubScreen != MainSubScreen.None) currentSubScreen else activeSubScreen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (subScreenToRender) {
                    MainSubScreen.CoordinateConfig -> AppCoordinateScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        onBack = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.ScannerMap -> ScannerMapScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.ManageData -> ManageDataScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.Update -> UpdateScreen(
                        updateViewModel = updateViewModel,
                        viewModel = viewModel,
                        isDark = isDark,
                        onBack = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.LanguageSettings -> com.suseoaa.locationspoofer.ui.screen.settings.LanguageSettingsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.MapEngineSettings -> com.suseoaa.locationspoofer.ui.screen.settings.MapEngineSettingsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.SignatureAuth -> com.suseoaa.locationspoofer.ui.screen.settings.SignatureAuthScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.RootDiagnostics -> com.suseoaa.locationspoofer.ui.screen.settings.RootDiagnosticsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.VendorScheme -> com.suseoaa.locationspoofer.ui.screen.settings.VendorSchemeScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.BackgroundKeepAlive -> com.suseoaa.locationspoofer.ui.screen.settings.BackgroundKeepAliveScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.EnvTokens -> com.suseoaa.locationspoofer.ui.screen.settings.EnvTokensScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onClose = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.SystemHookApps -> SystemHookAppsScreen(
                        viewModel = viewModel,
                        uiState = uiState,
                        onBack = { currentSubScreen = MainSubScreen.None }
                    )

                    MainSubScreen.None -> Unit
                }
            }
        }

        // 启动时若检测到新版本，弹出更新弹窗（支持忽略此版本、前往更新）
        if (showStartupUpdateDialog && latestRelease != null) {
            StartupUpdateDialog(
                latestRelease = latestRelease,
                onDismiss = { showStartupUpdateDialog = false },
                onNavigateToUpdate = {
                    showStartupUpdateDialog = false
                    currentSubScreen = MainSubScreen.Update
                },
                onIgnore = { version ->
                    viewModel.setIgnoredVersion(version)
                    showStartupUpdateDialog = false
                }
            )
        }
    }
}
