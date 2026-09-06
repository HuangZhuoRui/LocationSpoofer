package com.suseoaa.locationspoofer.ui.screen.tabs

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.SearchMode
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.LocalEnvironmentDataDialog
import com.suseoaa.locationspoofer.ui.screen.*
import com.suseoaa.locationspoofer.ui.screen.spoofing.SpoofingIntent
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.noRippleClickable
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import com.suseoaa.locationspoofer.viewmodel.ManageDataViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import com.suseoaa.locationspoofer.ui.screen.tabs.location.*
import java.util.Locale

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LocationTab(
    viewModel: MainViewModel,
    uiState: AppState,
    mapController: AppMapController?,
    tabBarHeight: Dp = 90.dp,
    manageDataViewModel: ManageDataViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val spoofingUiState by viewModel.spoofingUiState.collectAsState()
    val isDark = isSystemInDarkTheme()
    val coroutineScope = rememberCoroutineScope()
    val searchFocusRequester = remember { FocusRequester() }
    val isSearching = spoofingUiState.isSearchActive
    val onIntent = { intent: SpoofingIntent -> viewModel.handleSpoofingIntent(intent) }
    val searchCacheDurationMs = 30_000L
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    var searchResultBounds by remember { mutableStateOf(Rect.Zero) }
    var showLocalDataDialog by remember { mutableStateOf(false) }

    // 在页面顶层持久记录测量高度，绝不随搜索页面切换而重置为 0，彻底消除返回时“先回高位再跳动”的测量延迟
    var persistentSavedCardHeightPx by remember { mutableIntStateOf(0) }
    var persistentCoordCardHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val savedCardHeightDp = if (persistentSavedCardHeightPx > 0) {
        with(density) { persistentSavedCardHeightPx.toDp() }
    } else {
        180.dp
    }

    val coordCardHeightDp = if (persistentCoordCardHeightPx > 0) {
        with(density) { persistentCoordCardHeightPx.toDp() }
    } else {
        210.dp
    }

    var panelState by remember {
        mutableStateOf(
            if (uiState.isSpoofingActive) LocationPanelState.COLLAPSED else LocationPanelState.DEFAULT
        )
    }

    LaunchedEffect(uiState.isSpoofingActive) {
        if (uiState.isSpoofingActive) {
            panelState = LocationPanelState.COLLAPSED
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importEnvironmentData(it) {
                manageDataViewModel.loadManageData()
                Toast.makeText(context, context.getString(R.string.import_merge_success), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val submitSearch: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
        val now = System.currentTimeMillis()
        val hasRecentSearchCache =
            spoofingUiState.searchResults.isNotEmpty() &&
                    spoofingUiState.cachedSearchQuery == spoofingUiState.searchQuery &&
                    now - spoofingUiState.cachedSearchAt <= searchCacheDurationMs

        if (hasRecentSearchCache) {
            onIntent(
                SpoofingIntent.SetSearchResults(
                    results = spoofingUiState.searchResults,
                    show = true,
                    query = spoofingUiState.searchQuery
                )
            )
        } else if (uiState.searchMode == SearchMode.LOCAL) {
            coroutineScope.launch {
                val results = viewModel.performLocalSearch()
                onIntent(
                    SpoofingIntent.SetSearchResults(
                        results = results,
                        show = true,
                        query = spoofingUiState.searchQuery
                    )
                )
            }
        } else if (spoofingUiState.searchQuery.isNotBlank()) {
            performPoiSearch(
                context = context,
                mapEngine = uiState.mapEngine,
                keyword = spoofingUiState.searchQuery,
                isDomestic = viewModel.isDomesticEnvironment()
            ) { results ->
                onIntent(
                    SpoofingIntent.SetSearchResults(
                        results = results,
                        show = true,
                        query = spoofingUiState.searchQuery
                    )
                )
            }
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    BackHandler(enabled = isSearching) {
        focusManager.clearFocus()
        keyboardController?.hide()
        onIntent(SpoofingIntent.SetSearchActive(false))
    }

    // 悬浮卡片避让底部控制栏高度：
    // COLLAPSED（仅搜索）：避让搜索框，停留在 tabBarHeight + 74dp
    // DEFAULT（展示坐标卡片）：避让坐标卡片与搜索框顶部，停留在 tabBarHeight + coordCardHeightDp + 82dp
    // EXPANDED（完全展开收藏卡片）：保持 DEFAULT 状态高度，无需进一步顶至屏幕最上方
    val rawFabBottomPadding by animateDpAsState(
        targetValue = when (panelState) {
            LocationPanelState.EXPANDED -> tabBarHeight + coordCardHeightDp + 82.dp
            LocationPanelState.DEFAULT -> tabBarHeight + coordCardHeightDp + 82.dp
            LocationPanelState.COLLAPSED -> tabBarHeight + 74.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "location_fab_bottom_padding"
    )
    val fabBottomPadding = rawFabBottomPadding.coerceAtLeast(0.dp)

    SharedTransitionLayout {
        AnimatedContent(
            targetState = isSearching,
            transitionSpec = {
                androidx.compose.animation.EnterTransition.None togetherWith
                        androidx.compose.animation.ExitTransition.None
            },
            label = "location_search_transition"
        ) content@{ searchActive ->
            val searchModifier = Modifier
                .sharedElement(
                    sharedContentState = rememberSharedContentState(key = "location_search_bar"),
                    animatedVisibilityScope = this@content,
                    boundsTransform = { initialBounds, targetBounds ->
                        val isTopSearchTransition = (initialBounds.top < 350f && targetBounds.top > 350f) ||
                                (initialBounds.top > 350f && targetBounds.top < 350f)
                        if (isTopSearchTransition) {
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        } else {
                            snap()
                        }
                    }
                )
                .onGloballyPositioned { searchBounds = it.boundsInRoot() }

            Box(modifier = Modifier.fillMaxSize()) {
                // 悬浮功能按钮（主动避让底部面板，在面板高度变化时平滑跟随）
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = fabBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    MapControlButton(
                        icon = Icons.Rounded.MyLocation,
                        onClick = {
                            viewModel.fetchCurrentLocation(context) { lat, lng ->
                                mapController?.animateCamera(lat, lng, 16f)
                            }
                        }
                    )
                    MapControlButton(
                        icon = Icons.Rounded.Layers,
                        onClick = {
                            onIntent(SpoofingIntent.SetMapTypeDialogVisible(true))
                        }
                    )
                    MapControlButton(
                        icon = Icons.Rounded.Star,
                        onClick = {
                            onIntent(SpoofingIntent.SetSavedLocationsVisible(true))
                        }
                    )
                    MapControlButton(
                        icon = Icons.Rounded.Storage,
                        onClick = {
                            manageDataViewModel.loadManageData()
                            showLocalDataDialog = true
                        }
                    )
                }

                LocationControlPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    uiState = uiState,
                    isDark = isDark,
                    panelState = panelState,
                    onPanelStateChange = { panelState = it },
                    viewModel = viewModel,
                    tabBarHeight = tabBarHeight,
                    savedCardHeightDp = savedCardHeightDp,
                    coordCardHeightDp = coordCardHeightDp,
                    onSavedCardHeightMeasured = { persistentSavedCardHeightPx = it },
                    onCoordCardHeightMeasured = { persistentCoordCardHeightPx = it },
                    onSaveClick = { onIntent(SpoofingIntent.SetSaveDialogVisible(true)) },
                    onCustomClick = { onIntent(SpoofingIntent.SetCustomCoordDialogVisible(true)) },
                    onStartFixedSpoofing = {
                        onIntent(SpoofingIntent.SetStartSpoofingDialogVisible(true))
                    },
                    onStopSpoofing = { viewModel.stopSpoofing() },
                    onSelectSavedLocation = { location ->
                        viewModel.loadSavedLocation(location)
                        mapController?.animateCamera(location.lat, location.lng, 17.5f)
                        Toast.makeText(
                            context,
                            context.getString(R.string.located_to_saved_point, location.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDeleteSavedLocation = { location ->
                        viewModel.removeSavedLocation(location)
                    },
                    onOpenManageSavedLocations = {
                        onIntent(SpoofingIntent.SetSavedLocationsVisible(true))
                    },
                    searchBar = { barModifier ->
                        if (!searchActive) {
                            HomeSearchBar(
                                query = spoofingUiState.searchQuery,
                                searchMode = uiState.searchMode,
                                onSearchModeChange = viewModel::setSearchMode,
                                onQueryChange = { onIntent(SpoofingIntent.UpdateSearchQuery(it)) },
                                onSearch = submitSearch,
                                onFocus = { onIntent(SpoofingIntent.SetSearchActive(true)) },
                                modifier = searchModifier.then(barModifier),
                                focusRequester = searchFocusRequester
                            )
                        } else {
                            // 保持占位高度与尺寸结构恒定，绝不在全屏搜索返回时发生二次重构与停顿
                            Spacer(modifier = barModifier.height(52.dp))
                        }
                    }
                )

                if (searchActive && spoofingUiState.showSearchResults) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(searchBounds, searchResultBounds) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val up = waitForUpOrCancellation()
                                    if (up != null &&
                                        (up.position - down.position).getDistance() < viewConfiguration.touchSlop &&
                                        !searchBounds.contains(up.position) &&
                                        !searchResultBounds.contains(up.position)
                                    ) {
                                        onIntent(SpoofingIntent.HideSearchResults)
                                    }
                                }
                            }
                    )
                }

                if (searchActive) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 8.dp)
                    ) {
                        HomeSearchBar(
                            query = spoofingUiState.searchQuery,
                            searchMode = uiState.searchMode,
                            onSearchModeChange = viewModel::setSearchMode,
                            onQueryChange = { onIntent(SpoofingIntent.UpdateSearchQuery(it)) },
                            onSearch = submitSearch,
                            onFocus = { onIntent(SpoofingIntent.SetSearchActive(true)) },
                            modifier = searchModifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                            focusRequester = searchFocusRequester
                        )

                        AnimatedVisibility(
                            visible = spoofingUiState.showSearchResults && spoofingUiState.searchResults.isNotEmpty(),
                            enter = fadeIn(tween(160)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(120)) + shrinkVertically(tween(160))
                        ) {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .onGloballyPositioned { searchResultBounds = it.boundsInRoot() }
                            ) {
                                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                                    items(spoofingUiState.searchResults) { poi ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .noRippleClickable {
                                                    viewModel.updateLatitude(poi.lat.toString())
                                                    viewModel.updateLongitude(poi.lng.toString())
                                                    mapController?.animateCamera(
                                                        poi.lat,
                                                        poi.lng,
                                                        17.5f
                                                    )
                                                    onIntent(SpoofingIntent.HideSearchResults)
                                                    onIntent(SpoofingIntent.SetSearchActive(false))
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(AccentBlue.copy(alpha = 0.12f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Place,
                                                    null,
                                                    tint = AccentBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    poi.title,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onBackground
                                                )
                                                Text(
                                                    poi.snippet,
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(
                                                        alpha = 0.6f
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLocalDataDialog) {
        val manageDataUiState by manageDataViewModel.uiState.collectAsState()
        LocalEnvironmentDataDialog(
            dataList = manageDataUiState.dataList,
            isLoading = manageDataUiState.isLoading,
            onSelectPoint = { item ->
                val lat = item.location.lat
                val lng = item.location.lng
                viewModel.selectCollectedLocation(item.location.id)
                mapController?.animateCamera(lat, lng, 17.5f)
                val label = when {
                    item.location.remark.isNotBlank() -> item.location.remark
                    item.location.placeName.isNotBlank() -> item.location.placeName
                    else -> "(${String.format(Locale.US, "%.5f", lat)}, ${
                        String.format(
                            Locale.US,
                            "%.5f",
                            lng
                        )
                    })"
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.pinned_collected_location_toast, label),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onFavorite = { item ->
                viewModel.saveCollectedLocationToFavorites(item.location.id) { name ->
                    Toast.makeText(
                        context,
                        if (name != null) {
                            context.getString(R.string.favorited_toast, name)
                        } else {
                            context.getString(R.string.favorite_failed)
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onImportClick = {
                importLauncher.launch(arrayOf("application/json", "*/*"))
            },
            onDismiss = { showLocalDataDialog = false }
        )
    }

    if (spoofingUiState.showSavedLocationsDialog) {
        SavedLocationsDialog(
            savedLocations = uiState.savedLocations,
            onDismiss = { onIntent(SpoofingIntent.SetSavedLocationsVisible(false)) },
            onSelect = { location ->
                viewModel.loadSavedLocation(location)
                mapController?.animateCamera(location.lat, location.lng, 17.5f)
                onIntent(SpoofingIntent.SetSavedLocationsVisible(false))
                Toast.makeText(
                    context,
                    context.getString(R.string.located_to_saved_point, location.name),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDelete = viewModel::removeSavedLocation
        )
    }

    if (spoofingUiState.showSaveDialog) {
        SaveNameDialog(
            title = stringResource(R.string.save_current_location),
            isDark = isDark,
            onConfirm = { name ->
                viewModel.saveCurrentLocation(name)
                onIntent(SpoofingIntent.SetSaveDialogVisible(false))
            },
            onDismiss = { onIntent(SpoofingIntent.SetSaveDialogVisible(false)) }
        )
    }

    if (spoofingUiState.showStartSpoofingDialog) {
        StartSpoofingDialog(
            uiState = uiState,
            isDark = isDark,
            onDismiss = { onIntent(SpoofingIntent.SetStartSpoofingDialogVisible(false)) },
            onConfirm = {
                viewModel.startSpoofing()
                onIntent(SpoofingIntent.SetStartSpoofingDialogVisible(false))
            },
            onToggleWifi = viewModel::toggleMockWifi,
            onToggleCell = viewModel::toggleMockCell,
            onToggleBluetooth = viewModel::toggleMockBluetooth,
            onToggleJitter = viewModel::toggleEnableJitter,
            onAltitudeChange = viewModel::setAltitude,
            onSatelliteCountChange = viewModel::setSatelliteCount
        )
    }

    if (spoofingUiState.showCustomCoordDialog) {
        CustomCoordinateDialog(
            initialLat = uiState.latitudeInput,
            initialLng = uiState.longitudeInput,
            isDark = isDark,
            onDismiss = { onIntent(SpoofingIntent.SetCustomCoordDialogVisible(false)) },
            onConfirm = { lat, lng ->
                viewModel.updateLatitude(lat)
                viewModel.updateLongitude(lng)
                lat.toDoubleOrNull()?.let { latVal ->
                    lng.toDoubleOrNull()?.let { lngVal ->
                        mapController?.animateCamera(latVal, lngVal, 17.5f)
                    }
                }
                onIntent(SpoofingIntent.SetCustomCoordDialogVisible(false))
            }
        )
    }

    if (spoofingUiState.showMapTypeDialog) {
        com.suseoaa.locationspoofer.ui.components.MapTypeDialog(
            currentMapType = uiState.mapType,
            onMapTypeSelected = viewModel::setMapType,
            currentMapEngine = uiState.mapEngine,
            onMapEngineSelected = viewModel::setMapEngine,
            onDismiss = { onIntent(SpoofingIntent.SetMapTypeDialogVisible(false)) }
        )
    }
}


