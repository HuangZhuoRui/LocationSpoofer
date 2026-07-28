package com.suseoaa.locationspoofer.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.suseoaa.locationspoofer.ui.screen.performPoiSearch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.suseoaa.locationspoofer.BuildConfig
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.SearchMode
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.AppMapView
import com.suseoaa.locationspoofer.ui.components.MapTypeDialog
import com.suseoaa.locationspoofer.ui.screen.spoofing.SpoofingIntent
import com.suseoaa.locationspoofer.ui.screen.spoofing.SpoofingUiState
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.AppColors
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Composable
fun SpoofingScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean,
    onExpandMap: () -> Unit,
    onExpandScannerMap: () -> Unit,
    onExpandSettings: () -> Unit,
    updateViewModel: com.suseoaa.locationspoofer.viewmodel.UpdateViewModel = org.koin.androidx.compose.koinViewModel()
) {
    val spoofingUiState by viewModel.spoofingUiState.collectAsState()
    val updateUiState by updateViewModel.uiState.collectAsState()

    val onIntent = { intent: SpoofingIntent -> viewModel.handleSpoofingIntent(intent) }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            viewModel.exportEnvironmentData(it)
            Toast.makeText(context, "导出成功", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importEnvironmentData(it) {
                Toast.makeText(context, "导入合并成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var hasAutoCheckedUpdates by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasAutoCheckedUpdates) {
            updateViewModel.fetchReleases()
        }
    }

    LaunchedEffect(updateUiState.releases, updateUiState.isLoading) {
        if (!hasAutoCheckedUpdates && !updateUiState.isLoading && updateUiState.releases.isNotEmpty()) {
            val latestRelease = updateUiState.releases.firstOrNull()
            if (latestRelease != null) {
                val latestVersion = latestRelease.versionName
                val currentVersion = BuildConfig.VERSION_NAME
                val ignoredVersion = viewModel.getIgnoredVersion()
                if (isNewerVersion(
                        latestVersion,
                        currentVersion
                    ) && latestVersion != ignoredVersion
                ) {
                    onIntent(SpoofingIntent.SetUpdateDialogVisible(true))
                }
            }
            hasAutoCheckedUpdates = true
        }
    }

    BackHandler(enabled = spoofingUiState.showSearchResults) {
        onIntent(SpoofingIntent.ClearSearchResults(false))
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.fetchCurrentLocation(context)
        }
        viewModel.loadManageData()
    }

    var smallMapRef by remember { mutableStateOf<AppMapController?>(null) }
    val lat = uiState.latitudeInput.toDoubleOrNull()
    val lng = uiState.longitudeInput.toDoubleOrNull()

    LaunchedEffect(lat, lng, smallMapRef) {
        if (lat != null && lng != null) {
            smallMapRef?.animateCamera(lat, lng)
        }
    }

    LaunchedEffect(smallMapRef, uiState.mapType) {
        smallMapRef?.setMapType(uiState.mapType)
    }

    LaunchedEffect(smallMapRef, uiState.manageDataList) {
        val map = smallMapRef ?: return@LaunchedEffect
        map.clear()
        val locations = uiState.manageDataList.map { it.location }
        com.suseoaa.locationspoofer.utils.MapCoverageHelper.drawCoverage(map, locations)
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp
    val expandedMapHeight = screenHeightDp * 0.25f
    val collapsedMapHeight = screenHeightDp - (if (uiState.isSpoofingActive) 380.dp else 320.dp)

    val animatedMapHeight by animateDpAsState(
        targetValue = if (spoofingUiState.isSearchActive) screenHeightDp else if (spoofingUiState.isSheetExpanded) expandedMapHeight else collapsedMapHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        )
    )

    var isDragging by remember { mutableStateOf(false) }
    var wasAtTopBeforeDrag by remember { mutableStateOf(true) }
    val consumeAllUpwardScroll = remember { mutableStateOf(false) }

    LaunchedEffect(isDragging) {
        if (!isDragging) {
            wasAtTopBeforeDrag = (scrollState.value <= 0)
            consumeAllUpwardScroll.value = false
        }
    }

    val nestedScrollConnection = remember(spoofingUiState.isSearchActive, wasAtTopBeforeDrag) {
        var consumeNextUpwardFling = false

        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (spoofingUiState.isSearchActive) return Offset.Zero
                if (available.y < 0f) {
                    if (!spoofingUiState.isSheetExpanded || consumeAllUpwardScroll.value) {
                        if (!consumeAllUpwardScroll.value) consumeAllUpwardScroll.value = true
                        consumeNextUpwardFling = true
                        if (!spoofingUiState.isSheetExpanded) {
                            onIntent(SpoofingIntent.SetSheetExpanded(true))
                        }
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (consumeNextUpwardFling) {
                    consumeNextUpwardFling = false
                    if (available.y < 0f) {
                        return androidx.compose.ui.unit.Velocity(0f, available.y)
                    }
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (spoofingUiState.isSearchActive) return Offset.Zero
                if (spoofingUiState.isSheetExpanded && available.y > 10f) {
                    if (wasAtTopBeforeDrag) {
                        onIntent(SpoofingIntent.SetSheetExpanded(false))
                    }
                }
                return Offset.Zero
            }
        }
    }



    BackHandler(enabled = spoofingUiState.isSearchActive || spoofingUiState.showSearchResults) {
        onIntent(SpoofingIntent.SetSearchActive(false))
        onIntent(SpoofingIntent.ClearSearchResults(false))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.topBarBackground(isDark))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.MyLocation,
                        null,
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.app_name),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { onIntent(SpoofingIntent.SetSavedLocationsVisible(true)) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Bookmarks, stringResource(R.string.collection_list),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onExpandSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Settings, stringResource(R.string.settings),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)

            // Map Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedMapHeight)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppMapView(
                        mapEngine = uiState.mapEngine,
                        isDomestic = viewModel.isDomesticEnvironment(),
                        modifier = Modifier.fillMaxSize()
                    ) { map ->
                        smallMapRef = map
                        map.disableUiControls()
                        val initLat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
                        val initLng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
                        map.moveCamera(initLat, initLng, 15f)

                        map.setOnCameraChangeListener { lat, lng ->
                            onIntent(SpoofingIntent.ConfirmMapPoint(lat, lng))
                        }
                        map.setOnCameraMoveListener { lat, lng ->
                            onIntent(SpoofingIntent.MapPointMoved(lat, lng))
                        }
                    }

                    Icon(
                        Icons.Rounded.AddLocationAlt, null,
                        tint = AccentBlue.copy(alpha = 0.8f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                            .padding(bottom = 16.dp)
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 12.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Fullscreen Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                .clickable { onExpandMap() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Fullscreen,
                                null,
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Locate Current Position Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                .clickable { viewModel.fetchCurrentLocation(context) { _, _ -> } },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.MyLocation,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Select Map Type Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                .clickable { onIntent(SpoofingIntent.SetMapTypeDialogVisible(true)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Layers,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    )

                    // Floating Search Bar & Results Overlay (Fixed at top of map)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Transparent)
                        ) {
                            Box(modifier = Modifier.clickable {
                                if (!spoofingUiState.isSearchActive) onIntent(
                                    SpoofingIntent.SetSearchActive(true)
                                )
                            }) {
                                HomeSearchBar(
                                    query = spoofingUiState.searchQuery,
                                    searchMode = uiState.searchMode,
                                    onSearchModeChange = { mode -> viewModel.setSearchMode(mode) },
                                    onSearch = {
                                        focusManager.clearFocus()
                                        if (uiState.searchMode == SearchMode.LOCAL) {
                                            GlobalScope.launch(Dispatchers.Main) {
                                                val results = viewModel.performLocalSearch()
                                                onIntent(
                                                    SpoofingIntent.SetSearchResults(
                                                        results,
                                                        true
                                                    )
                                                )
                                            }
                                        } else if (spoofingUiState.searchQuery.isNotBlank()) {
                                            performPoiSearch(
                                                context,
                                                uiState.mapEngine,
                                                spoofingUiState.searchQuery,
                                                viewModel.isDomesticEnvironment()
                                            ) { results ->
                                                onIntent(
                                                    SpoofingIntent.SetSearchResults(
                                                        results,
                                                        true
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    onQueryChange = { onIntent(SpoofingIntent.UpdateSearchQuery(it)) }
                                )
                            }

                            AnimatedVisibility(visible = spoofingUiState.showSearchResults && spoofingUiState.searchResults.isNotEmpty()) {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .offset(y = (-4).dp)
                                ) {
                                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                        items(spoofingUiState.searchResults.take(15)) { poi ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        viewModel.updateLatitude(
                                                            String.format(
                                                                "%.6f",
                                                                poi.lat
                                                            )
                                                        )
                                                        viewModel.updateLongitude(
                                                            String.format(
                                                                "%.6f",
                                                                poi.lng
                                                            )
                                                        )
                                                        smallMapRef?.animateCamera(
                                                            poi.lat,
                                                            poi.lng,
                                                            16f
                                                        )
                                                        onIntent(
                                                            SpoofingIntent.ClearSearchResults(
                                                                false
                                                            )
                                                        )
                                                        onIntent(
                                                            SpoofingIntent.SetSearchActive(
                                                                false
                                                            )
                                                        )
                                                        onIntent(
                                                            SpoofingIntent.UpdateSearchQuery(
                                                                poi.title
                                                            )
                                                        )
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Place,
                                                    null,
                                                    tint = AccentBlue,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        poi.title,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                    Text(
                                                        poi.snippet,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.outline
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

            // Bottom Sheet
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event =
                                            awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        val isPressed = event.changes.any { it.pressed }
                                        if (isDragging != isPressed) {
                                            isDragging = isPressed
                                        }
                                    }
                                }
                            }
                            .nestedScroll(nestedScrollConnection)
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .navigationBarsPadding()
                    ) {


                        if (uiState.isSpoofingActive) {
                            WifiStatusCard(uiState)
                            Spacer(Modifier.height(12.dp))
                        }

                        CoordinateInputCard(
                            viewModel = viewModel,
                            uiState = uiState,
                            isDark = isDark,
                            onSaveClick = { onIntent(SpoofingIntent.SetSaveDialogVisible(true)) },
                            onCustomClick = {
                                onIntent(
                                    SpoofingIntent.SetCustomCoordDialogVisible(
                                        true
                                    )
                                )
                            }
                        )
                        Spacer(Modifier.height(12.dp))

                        ActionButtons(
                            viewModel,
                            uiState,
                            onExpandMap,
                            onStartFixedSpoofing = {
                                onIntent(
                                    SpoofingIntent.SetStartSpoofingDialogVisible(
                                        true
                                    )
                                )
                            })
                        Spacer(Modifier.height(16.dp))

                        SectionHeader(
                            Icons.Rounded.Search,
                            "搜索源",
                            isDark
                        )
                        Spacer(Modifier.height(8.dp))
                        SearchModeCard(isDark, uiState.searchMode) { mode ->
                            viewModel.setSearchMode(mode)
                            if (mode == SearchMode.LOCAL) {
                                focusManager.clearFocus()
                                GlobalScope.launch(Dispatchers.Main) {
                                    val results = viewModel.performLocalSearch()
                                    onIntent(SpoofingIntent.SetSearchResults(results, true))
                                }
                            } else {
                                onIntent(SpoofingIntent.ClearSearchResults(clearAll = true))
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        if (uiState.savedLocations.isNotEmpty()) {
                            SectionHeader(
                                Icons.Rounded.Bookmarks,
                                stringResource(R.string.collection_list),
                                isDark
                            )
                            Spacer(Modifier.height(8.dp))
                            SavedLocationsCard(
                                savedLocations = uiState.savedLocations,
                                onSelect = { loc -> viewModel.loadSavedLocation(loc) },
                                onDelete = { loc -> viewModel.removeSavedLocation(loc) }
                            )
                            Spacer(Modifier.height(16.dp))
                        }

                        SectionHeader(
                            Icons.Rounded.SystemUpdateAlt,
                            stringResource(R.string.check_updates),
                            isDark
                        )
                        Spacer(Modifier.height(8.dp))
                        UpdateCheckCard(isDark, onCheckClick = {
                            updateViewModel.fetchReleases()
                            onIntent(SpoofingIntent.SetUpdateDialogVisible(true))
                        })
                        Spacer(Modifier.height(16.dp))

                        Spacer(Modifier.height(16.dp))

                        AppCoordinateConfigCard(isDark) {
                            onIntent(SpoofingIntent.SetAppCoordinateScreenVisible(true))
                        }
                        Spacer(Modifier.height(16.dp))

                        ScannerMapCard(isDark, uiState) {
                            onExpandScannerMap()
                        }
                        Spacer(Modifier.height(8.dp))

                        ManageDataCard(isDark) {
                            viewModel.toggleManageDataScreen(true)
                        }
                        Spacer(Modifier.height(8.dp))

                        ImportExportDataCard(
                            isDark = isDark,
                            onImportClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "*/*"
                                    )
                                )
                            },
                            onExportClick = { exportLauncher.launch("environment_data.json") }
                        )
                        Spacer(Modifier.height(16.dp))

                        FooterLinks(isDark)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Dialogs
    if (spoofingUiState.showSaveDialog) {
        SaveNameDialog(
            title = stringResource(R.string.save_current_location),
            onConfirm = { name ->
                viewModel.saveCurrentLocation(name)
                onIntent(SpoofingIntent.SetSaveDialogVisible(false))
            },
            onDismiss = { onIntent(SpoofingIntent.SetSaveDialogVisible(false)) }
        )
    }

    if (spoofingUiState.showSavedLocationsDialog) {
        SavedLocationsDialog(
            savedLocations = uiState.savedLocations,
            onDismiss = { onIntent(SpoofingIntent.SetSavedLocationsVisible(false)) },
            onSelect = { loc ->
                viewModel.loadSavedLocation(loc)
                onIntent(SpoofingIntent.SetSavedLocationsVisible(false))
            },
            onDelete = { loc -> viewModel.removeSavedLocation(loc) }
        )
    }

    if (spoofingUiState.showUpdateDialog) {
        UpdateDialog(
            uiState = updateUiState,
            onDismiss = { onIntent(SpoofingIntent.SetUpdateDialogVisible(false)) },
            onDownload = { url, version -> updateViewModel.startDownload(url, version) },
            onCancel = { updateViewModel.cancelDownload() },
            onInstall = { updateViewModel.installApk() },
            onIgnore = { version ->
                viewModel.setIgnoredVersion(version)
                onIntent(SpoofingIntent.SetUpdateDialogVisible(false))
            }
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
            onToggleWifi = { viewModel.toggleMockWifi() },
            onToggleCell = { viewModel.toggleMockCell() },
            onToggleBluetooth = { viewModel.toggleMockBluetooth() },
            onToggleJitter = { viewModel.toggleEnableJitter() },
            onAltitudeChange = { viewModel.setAltitude(it) },
            onSatelliteCountChange = { viewModel.setSatelliteCount(it) }
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
                onIntent(SpoofingIntent.SetCustomCoordDialogVisible(false))
            }
        )
    }

    if (spoofingUiState.showMapTypeDialog) {
        MapTypeDialog(
            currentMapType = uiState.mapType,
            onMapTypeSelected = { viewModel.setMapType(it) },
            currentMapEngine = uiState.mapEngine,
            onMapEngineSelected = { viewModel.setMapEngine(it) },
            onDismiss = { onIntent(SpoofingIntent.SetMapTypeDialogVisible(false)) }
        )
    }

    AnimatedVisibility(
        visible = spoofingUiState.showAppCoordinateScreen,
        enter = androidx.compose.animation.slideInVertically(tween(400)) { it },
        exit = androidx.compose.animation.slideOutVertically(tween(400)) { it }
    ) {
        AppCoordinateScreen(
            viewModel = viewModel,
            uiState = uiState,
            onBack = { onIntent(SpoofingIntent.SetAppCoordinateScreenVisible(false)) }
        )
    }
}
