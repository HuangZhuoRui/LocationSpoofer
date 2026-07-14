package com.suseoaa.locationspoofer.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.suseoaa.locationspoofer.ui.components.AppMapView
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.AppMapMarker
import com.suseoaa.locationspoofer.ui.components.MarkerType
import androidx.compose.ui.res.stringResource
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.ui.components.MapTypeDialog
import com.suseoaa.locationspoofer.data.model.RoutePoint
import com.suseoaa.locationspoofer.data.model.RoutePlanStage
import com.suseoaa.locationspoofer.data.model.RouteRunMode
import com.suseoaa.locationspoofer.data.model.SimMode
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.AccentOrange
import com.suseoaa.locationspoofer.ui.theme.AppColors
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import kotlin.math.*

// 全屏路线规划页面

@Composable
@Suppress("UNUSED_PARAMETER")
fun FullScreenMapPage(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean,
    isInPipMode: Boolean = false,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var mapRef by remember { mutableStateOf<AppMapController?>(null) }
    var showSavedLocations by remember { mutableStateOf(false) }
    var showMapTypeDialog by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var showSavedRoutesDialog by remember { mutableStateOf(false) }
    val isDomestic = viewModel.isDomesticEnvironment()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AppPoiItem>>(emptyList()) }
    var showSearchResults by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // 拦截返回键：如果有搜索结果，按返回键先关闭搜索结果
    BackHandler(enabled = showSearchResults) {
        showSearchResults = false
    }

    val stage = uiState.routePlanStage
    val isRunning = stage == RoutePlanStage.RUNNING
    val isManual = uiState.routeRunMode == RouteRunMode.MANUAL
    val routePoints = uiState.routePoints

    // 同步地图类型
    LaunchedEffect(mapRef, uiState.mapType) {
        mapRef?.setMapType(uiState.mapType)
    }

    // 同步路点标记和折线到地图
    var liveMarker by remember { mutableStateOf<AppMapMarker?>(null) }
    LaunchedEffect(routePoints, mapRef, uiState.manageDataList) {
        val map = mapRef ?: return@LaunchedEffect
        map.clear()
        liveMarker = null
        val locations = uiState.manageDataList.map { it.location }
        com.suseoaa.locationspoofer.utils.MapCoverageHelper.drawCoverage(map, locations)

        if (routePoints.size >= 2) {
            map.addPolyline(
                routePoints.map { Pair(it.lat, it.lng) },
                android.graphics.Color.parseColor("#FF388BFD"),
                8f
            )
        }
        routePoints.forEachIndexed { idx, p ->
            val type = when (idx) {
                0 -> MarkerType.GREEN
                routePoints.lastIndex -> MarkerType.RED
                else -> MarkerType.DEFAULT
            }
            
            // 如果开启了真实路线且处于运行状态，则不绘制中间密集的轨迹点
            if (uiState.useRealRoute && uiState.routePlanStage == RoutePlanStage.RUNNING && type == MarkerType.DEFAULT) {
                return@forEachIndexed
            }
            
            map.addMarker(p.lat, p.lng, if (type == MarkerType.RED && uiState.useRealRoute && uiState.routePlanStage == RoutePlanStage.RUNNING) "终点" else "${idx + 1}", type)
        }

        // 确保被 clear() 清除的实时位置图标能够重新绘制
        if (uiState.isSpoofingActive) {
            val currentLat = uiState.latitudeInput.toDoubleOrNull()
            val currentLng = uiState.longitudeInput.toDoubleOrNull()
            if (currentLat != null && currentLng != null) {
                liveMarker = map.addMarker(
                    currentLat, currentLng,
                    context.getString(R.string.current_location),
                    MarkerType.ORANGE
                )
            }
        }
    }

    // 运行中时跟踪实时位置
    val lat = uiState.latitudeInput.toDoubleOrNull()
    val lng = uiState.longitudeInput.toDoubleOrNull()
    LaunchedEffect(lat, lng, uiState.isSpoofingActive, uiState.routePlanStage) {
        if (uiState.isSpoofingActive && lat != null && lng != null) {
            // 只有在非路线规划运行时才跟随坐标点移动镜头，如果是路线规划则需要纵览全局
            if (uiState.routePlanStage != RoutePlanStage.RUNNING) {
                mapRef?.animateCamera(lat, lng)
            }
            // 更新或创建实时位置标记
            if (liveMarker != null) {
                liveMarker?.setPosition(lat, lng)
            } else {
                liveMarker = mapRef?.addMarker(
                    lat, lng,
                    context.getString(R.string.current_location),
                    MarkerType.ORANGE
                )
            }
        }
    }

    // 根据路线和模式自动缩放
    LaunchedEffect(isInPipMode, uiState.routePlanStage, routePoints) {
        if (uiState.routePlanStage == RoutePlanStage.RUNNING && routePoints.size >= 2) {
            // 路线规划运行时，显示完整的路线范围
            val padding = if (isInPipMode) 30 else 150
            mapRef?.fitBounds(routePoints.map { Pair(it.lat, it.lng) }, padding)
        } else if (isInPipMode) {
            // 单点定位进入小窗时，放大到 18f
            val currentLat = uiState.latitudeInput.toDoubleOrNull()
            val currentLng = uiState.longitudeInput.toDoubleOrNull()
            if (currentLat != null && currentLng != null) {
                mapRef?.animateCamera(currentLat, currentLng, 18f)
            }
        }
    }

    // 就绪时弹出配置弹窗
    LaunchedEffect(stage) {
        if (stage == RoutePlanStage.READY) showConfigDialog = true
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // 地图
        AppMapView(mapEngine = uiState.mapEngine, isDomestic = isDomestic, modifier = Modifier.fillMaxSize()) { map ->
            mapRef = map
            map.disableUiControls()
            val initLat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
            val initLng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
            map.moveCamera(initLat, initLng, 18f)
        }

        // 选点模式的十字准星
        if (!isInPipMode && (stage == RoutePlanStage.SELECTING || stage == RoutePlanStage.IDLE)) {
            Icon(
                Icons.Rounded.AddLocationAlt, null,
                tint = AccentBlue.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).size(40.dp).padding(bottom = 16.dp)
            )
        }

        // 手动模式运行时的定位标志
        if (isRunning && isManual) {
            Icon(
                Icons.Rounded.PersonPin, null,
                tint = AccentOrange,
                modifier = Modifier.align(Alignment.Center).size(48.dp)
            )
        }

        // 顶部栏（含搜索）
        if (!isInPipMode) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
            TopBar(
                stage = stage,
                routePointCount = routePoints.size,
                isManual = isManual,
                onBack = onClose,
                canUndo = stage == RoutePlanStage.SELECTING && routePoints.isNotEmpty(),
                onUndo = { viewModel.undoLastRoutePoint() }
            )
            // 搜索栏
            if (stage == RoutePlanStage.SELECTING || stage == RoutePlanStage.IDLE) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            if (searchQuery.isNotBlank()) {
                                performPoiSearch(context, uiState.mapEngine, searchQuery, isDomestic) { r ->
                                    searchResults = r
                                    showSearchResults = r.isNotEmpty()
                                }
                            }
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .shadow(4.dp, RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(22.dp))
                            .padding(horizontal = 16.dp),
                        decorationBox = { innerTextField ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(stringResource(R.string.search_location_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                    }
                                    innerTextField()
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    )
                }
                // 搜索结果
                AnimatedVisibility(visible = showSearchResults && searchResults.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) {
                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                            items(searchResults.take(15)) { poi ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                        val pLat = poi.lat
                                        val pLng = poi.lng
                                        mapRef?.animateCamera(pLat, pLng, 16f)
                                        showSearchResults = false
                                        searchQuery = poi.title
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Place, null, tint = AccentBlue, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text(poi.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                                        Text(poi.snippet, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }
        } // End of if(!isInPipMode) for TopBar

        // 右侧定位和图层按钮
        if (!isInPipMode) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapFab(
                    icon = Icons.Rounded.Layers,
                    contentDescription = stringResource(R.string.map_type_label),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = AccentBlue
                ) {
                    showMapTypeDialog = true
                }

                AnimatedVisibility(visible = stage == RoutePlanStage.SELECTING && routePoints.isEmpty()) {
                    MapFab(
                        icon = Icons.Rounded.Route,
                        contentDescription = stringResource(R.string.route_library),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = AccentBlue
                    ) {
                        showSavedRoutesDialog = true
                    }
                }

                AnimatedVisibility(visible = (stage == RoutePlanStage.SELECTING && routePoints.isEmpty()) || stage == RoutePlanStage.IDLE) {
                    Column (
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MapFab(
                            icon = Icons.Rounded.Bookmarks,
                            contentDescription = stringResource(R.string.collection_list),
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = AccentBlue
                        ) {
                            showSavedLocations = true
                        }

                        MapFab(
                            icon = Icons.Rounded.MyLocation,
                            contentDescription = stringResource(R.string.locate_to_current),
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = AccentBlue
                        ) {
                            viewModel.fetchCurrentLocation(context) { lLat, lLng ->
                                mapRef?.animateCamera(lLat, lLng, 16f)
                            }
                        }
                    }
                }
            }

            // 摇杆（仅手动模式运行中）
            AnimatedVisibility(
                visible = isRunning && isManual,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp),
                enter = slideInVertically(tween(250)) { it / 2 } + fadeIn(tween(250)),
                exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(200))
            ) {
                JoystickPanel(viewModel = viewModel, maxSpeedMs = uiState.routeSimMode.speedMs.toFloat())
            }
        }

        // IDLE阶段（全屏选点模式）只显示单一确认选点按钮
        if (!isInPipMode) {
            if (stage == RoutePlanStage.IDLE) {
                Button(
                    onClick = {
                        val tLat = mapRef?.cameraTargetLat
                        val tLng = mapRef?.cameraTargetLng
                        if (tLat != null && tLng != null) {
                            viewModel.confirmMapPoint(tLat, tLng)
                            Toast.makeText(context, context.getString(R.string.coordinate_selected), Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.confirm_point), fontWeight = FontWeight.Bold)
                }
            } else {
                // 底部操作栏 (路线规划模式)
                BottomActionBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    stage = stage,
                    routePoints = routePoints,
                    onConfirmPoint = {
                        val tLat = mapRef?.cameraTargetLat
                        val tLng = mapRef?.cameraTargetLng
                        if (tLat != null && tLng != null) {
                            viewModel.addRoutePoint(tLat, tLng)
                        }
                    },
                    onFinishSelecting = { viewModel.finishSelectingPoints() },
                    onRestartSelecting = { viewModel.restartSelectingPoints() },
                    onSaveRoute = { showSaveRouteDialog = true },
                    onStartPlanning = { showConfigDialog = true },
                    onStopRoute = { viewModel.stopRoutePlanning(); onClose() }
                )
            }
        }
    }

    // 配置弹窗
    if (showConfigDialog) {
        RoutePlanConfigDialog(
            uiState = uiState,
            onDismiss = {
                showConfigDialog = false
                // 如果还没开始，回退到 READY
                if (stage == RoutePlanStage.READY) {
                    viewModel.restartSelectingPoints()
                }
            },
            onStartRoute = {
                showConfigDialog = false
                viewModel.startRoutePlanning()
            },
            onRunModeChange = viewModel::setRouteRunMode,
            onSpeedChange = viewModel::setRouteSimMode,
            onCustomSpeedChange = viewModel::setCustomSpeedMs,
            onUseRealRouteChange = viewModel::setUseRealRoute
        )
    }

    if (uiState.isFetchingRoute) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { }, // Block interactions
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("正在规划真实路线...", fontSize = 16.sp)
                }
            }
        }
    }

    if (showMapTypeDialog) {
        MapTypeDialog(
            currentMapType = uiState.mapType,
            onMapTypeSelected = { viewModel.setMapType(it) },
            currentMapEngine = uiState.mapEngine,
            onMapEngineSelected = { viewModel.setMapEngine(it) },
            onDismiss = { showMapTypeDialog = false }
        )
    }

    if (showSaveRouteDialog) {
        var routeName by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showSaveRouteDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("收藏当前路线", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = routeName,
                        onValueChange = { routeName = it },
                        label = { Text(stringResource(R.string.route_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { showSaveRouteDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            if (routeName.isNotBlank()) {
                                viewModel.saveRoute(routeName, routePoints)
                                Toast.makeText(context, context.getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                                showSaveRouteDialog = false
                            }
                        }) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }

    // 地点收藏列表
    if (showSavedLocations) {
        SavedLocationsDialog(
            savedLocations = uiState.savedLocations,
            onDismiss = { showSavedLocations = false },
            onSelect = { loc ->
                showSavedLocations = false
                mapRef?.animateCamera(loc.lat, loc.lng)
            },
            onDelete = { loc -> viewModel.removeSavedLocation(loc) }
        )
    }

    if (showSavedRoutesDialog) {
        Dialog(onDismissRequest = { showSavedRoutesDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.route_library), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    if (uiState.savedRoutes.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_saved_routes), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(uiState.savedRoutes) { route ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        viewModel.loadSavedRoute(route)
                                        showSavedRoutesDialog = false
                                    }.padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(route.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text(stringResource(R.string.route_nodes_count, route.points.size), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                    IconButton(onClick = { viewModel.removeSavedRoute(route) }) {
                                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { showSavedRoutesDialog = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

// 顶部栏

@Composable
private fun TopBar(
    stage: RoutePlanStage,
    routePointCount: Int,
    isManual: Boolean,
    onBack: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        if (stage != RoutePlanStage.IDLE) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = when (stage) {
                        RoutePlanStage.IDLE -> ""
                        RoutePlanStage.SELECTING -> stringResource(R.string.selecting_points_hint, routePointCount)
                        RoutePlanStage.READY -> stringResource(R.string.route_ready_hint, routePointCount)
                        RoutePlanStage.RUNNING -> if (isManual) stringResource(R.string.joystick_controlling) else stringResource(R.string.route_looping)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        AnimatedVisibility(visible = canUndo) {
            MapFab(
                icon = Icons.AutoMirrored.Rounded.Undo,
                contentDescription = stringResource(R.string.undo),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onBackground,
                onClick = onUndo
            )
        }
    }
}

// 底部操作栏（由阶段驱动）

@Composable
private fun BottomActionBar(
    modifier: Modifier,
    stage: RoutePlanStage,
    routePoints: List<RoutePoint>,
    onConfirmPoint: () -> Unit,
    onFinishSelecting: () -> Unit,
    onRestartSelecting: () -> Unit,
    onSaveRoute: () -> Unit,
    onStartPlanning: () -> Unit,
    onStopRoute: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.95f))
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (stage) {
            RoutePlanStage.IDLE -> { /* 不会到达此处 */ }

            RoutePlanStage.SELECTING -> {
                Text(
                    stringResource(R.string.selected_points_count, routePoints.size, if (routePoints.size < 2) stringResource(R.string.at_least_two_points) else ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onConfirmPoint,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Icon(Icons.Rounded.AddLocation, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.confirm_point), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Button(
                        onClick = onFinishSelecting,
                        enabled = routePoints.size >= 2,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.finish_selecting), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Button(
                        onClick = onSaveRoute,
                        enabled = routePoints.size >= 2,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Icon(Icons.Rounded.Star, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.save_route), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }

            RoutePlanStage.READY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onRestartSelecting,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.re_select), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onSaveRoute() },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Star, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("收藏", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onStartPlanning,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.start_planning), fontWeight = FontWeight.Bold)
                    }
                }
            }

            RoutePlanStage.RUNNING -> {
                Button(
                    onClick = onStopRoute,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.stop_planning), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// 路线配置对话框

@Composable
private fun RoutePlanConfigDialog(
    uiState: AppState,
    onDismiss: () -> Unit,
    onStartRoute: () -> Unit,
    onRunModeChange: (RouteRunMode) -> Unit,
    onSpeedChange: (SimMode) -> Unit,
    onCustomSpeedChange: (Double) -> Unit = {},
    onUseRealRouteChange: (Boolean) -> Unit = {}
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    stringResource(R.string.route_config),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // 模式选择
                Text(
                    stringResource(R.string.control_mode),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = uiState.routeRunMode == RouteRunMode.MANUAL,
                        onClick = { onRunModeChange(RouteRunMode.MANUAL) },
                        label = { Text(stringResource(R.string.manual_joystick)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentOrange.copy(alpha = 0.15f),
                            selectedLabelColor = AccentOrange
                        )
                    )
                    FilterChip(
                        selected = uiState.routeRunMode == RouteRunMode.LOOP,
                        onClick = { onRunModeChange(RouteRunMode.LOOP) },
                        label = { Text(stringResource(R.string.loop_auto)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentBlue.copy(alpha = 0.15f),
                            selectedLabelColor = AccentBlue
                        )
                    )
                }

                // 循环模式速度选择
                AnimatedVisibility(uiState.routeRunMode == RouteRunMode.LOOP) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.loop_auto),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        Text(
                            stringResource(R.string.loop_description),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.movement_speed),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                SimMode.WALKING, SimMode.RUNNING,
                                SimMode.CYCLING, SimMode.DRIVING
                            ).forEach { mode ->
                                FilterChip(
                                    selected = uiState.routeSimMode == mode,
                                    onClick = { onSpeedChange(mode) },
                                    label = {
                                        Text(
                                            "${when(mode){
                                                SimMode.WALKING -> stringResource(R.string.walking)
                                                SimMode.RUNNING -> stringResource(R.string.running)
                                                SimMode.CYCLING -> stringResource(R.string.cycling)
                                                SimMode.DRIVING -> stringResource(R.string.driving)
                                                else -> stringResource(R.string.custom)
                                            }}\n${mode.speedMs.toInt()}m/s",
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f),
                                        selectedLabelColor = AccentGreen
                                    )
                                )
                            }
                        }
                        // 自定义速度输入
                        AnimatedVisibility(uiState.routeSimMode == SimMode.CUSTOM) {
                            var customInput by remember { mutableStateOf(uiState.customSpeedMs.toString()) }
                            OutlinedTextField(
                                value = customInput,
                                onValueChange = { v ->
                                    customInput = v
                                    v.toDoubleOrNull()?.let { onCustomSpeedChange(it) }
                                },
                                label = { Text(stringResource(R.string.speed_unit)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }

                // 手动模式说明
                AnimatedVisibility(uiState.routeRunMode == RouteRunMode.MANUAL) {
                    Text(
                        stringResource(R.string.joystick_description),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }

                // 真实路线规划设置
                AnimatedVisibility(uiState.routePoints.size >= 2) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUseRealRouteChange(!uiState.useRealRoute) }
                            .padding(vertical = 4.dp)
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = uiState.useRealRoute,
                            onCheckedChange = { onUseRealRouteChange(it) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("使用真实路线规划", fontSize = 14.sp)
                            Text("通过API获取实际道路轨迹，遇到红绿灯自动停下15秒", fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                        }
                    }
                }

                // 操作按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.cancel)) }
                    Button(
                        onClick = onStartRoute,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) { Text(stringResource(R.string.start_simulation), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// 摇杆面板

@Composable
fun JoystickPanel(viewModel: MainViewModel, maxSpeedMs: Float = 10f) {
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 120f
    var joystickState by remember { mutableStateOf(Pair(0.0, 0f)) }

    LaunchedEffect(joystickState) {
        val (angle, intensity) = joystickState
        if (intensity > 0) {
            while (true) {
                val bearing = (Math.toDegrees(angle) + 90 + 360) % 360
                viewModel.moveByJoystick(bearing, intensity, maxSpeedMs)
                kotlinx.coroutines.delay(100)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(160.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { thumbOffset = Offset.Zero; joystickState = Pair(0.0, 0f) },
                    onDragCancel = { thumbOffset = Offset.Zero; joystickState = Pair(0.0, 0f) }
                ) { change, dragAmount ->
                    change.consume()
                    val raw = thumbOffset + dragAmount
                    val dist = sqrt(raw.x * raw.x + raw.y * raw.y)
                    thumbOffset = if (dist <= maxRadius) raw else raw * (maxRadius / dist)
                    val angle = atan2(thumbOffset.y.toDouble(), thumbOffset.x.toDouble())
                    val intensity = (sqrt(thumbOffset.x * thumbOffset.x + thumbOffset.y * thumbOffset.y) / maxRadius).coerceIn(0f, 1f)
                    joystickState = Pair(angle, intensity)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.x.toInt(), thumbOffset.y.toInt()) }
                .size(52.dp)
                .background(AccentOrange, CircleShape)
        )
    }
}

// 地图悬浮按钮辅助

@Composable
private fun MapFab(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.size(44.dp),
        containerColor = if (enabled) containerColor else containerColor.copy(alpha = 0.38f),
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
        shape = CircleShape
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(20.dp))
    }
}

// 保存名称对话框（SpoofingScreen复用）

@Composable
fun SaveNameDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
