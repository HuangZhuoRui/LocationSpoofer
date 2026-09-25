package com.vincenthzr.locationspoofer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.components.AppMapView
import com.vincenthzr.locationspoofer.ui.components.MapTypeDialog
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.ui.components.MapCoverageHelper
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.fetchCurrentLocation
import com.vincenthzr.locationspoofer.viewmodel.getAllLocations
import com.vincenthzr.locationspoofer.viewmodel.isDomesticEnvironment
import com.vincenthzr.locationspoofer.viewmodel.setMapEngine
import com.vincenthzr.locationspoofer.viewmodel.setMapType
import com.vincenthzr.locationspoofer.viewmodel.toggleContinuousScanning
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun ScannerMapScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    var showMapTypeDialog by remember { mutableStateOf(false) }

    // 进入页面时主动尝试刷新一次真实 GPS 定位
    LaunchedEffect(Unit) {
        viewModel.fetchCurrentLocation(context) { lat, lng ->
            mapController?.animateCamera(lat, lng, 16.5f)
        }
    }

    // 同步地图类型
    LaunchedEffect(mapController, uiState.mapType) {
        mapController?.setMapType(uiState.mapType)
    }

    // 地图就绪或记录数量变化时，绘制覆盖范围圆圈并定位至当前位置
    LaunchedEffect(mapController, uiState.environmentRecordCount) {
        val controller = mapController ?: return@LaunchedEffect
        val locations = viewModel.getAllLocations()
        val currentLat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
        val currentLng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
        controller.clear()

        // 绘制覆盖范围圆圈（带空间降采样与硬上限，确保高帧率流畅运行）
        MapCoverageHelper.drawCoverage(controller, locations, currentLat, currentLng)

        // 自动定位到当前位置
        controller.animateCamera(currentLat, currentLng, 16.5f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        AppMapView(
            mapEngine = uiState.mapEngine,
            isDomestic = viewModel.isDomesticEnvironment(),
            modifier = Modifier.fillMaxSize(),
            onMapReady = { controller ->
                mapController = controller
                controller.disableUiControls()
                // 地图加载就绪后立即聚焦到当前位置
                val currentLat = uiState.latitudeInput.toDoubleOrNull() ?: 39.9042
                val currentLng = uiState.longitudeInput.toDoubleOrNull() ?: 116.4074
                controller.moveCamera(currentLat, currentLng, 16.5f)
            }
        )

        // 顶部操作栏（左侧圆形返回胶囊，右侧扫描状态胶囊）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 独立立体圆形返回按钮（替换原先的 x 图标）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFF22272E) else Color.White)
                    .border(
                        width = 1.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFE5E8EC),
                        shape = CircleShape
                    )
                    .noRippleClickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = if (isDark) Color.White else Color(0xFF1A1D20),
                    modifier = Modifier.size(21.dp)
                )
            }

            // 状态指示胶囊
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(22.dp), clip = false)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isDark) Color(0xFF22272E) else Color.White)
                    .border(
                        width = 1.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFE5E8EC),
                        shape = RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.isContinuousScanning) AccentGreen else MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = 0.35f
                                )
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isContinuousScanning) {
                            stringResource(
                                R.string.scanning_status_active,
                                uiState.environmentRecordCount
                            )
                        } else {
                            stringResource(R.string.scanning_status_inactive)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 右上角实时扫描统计卡片 (放在状态胶囊下方)
        AnimatedVisibility(
            visible = uiState.isContinuousScanning || uiState.scannedWifiCount > 0 || uiState.scannedCellCount > 0 || uiState.scannedBluetoothCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 64.dp, end = 16.dp)
        ) {
            MiuixCard(
                cornerRadius = 14.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(
                            R.string.scanned_wifi_count,
                            uiState.scannedWifiCount
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.scanned_cell_count,
                            uiState.scannedCellCount
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.scanned_bt_count,
                            uiState.scannedBluetoothCount
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 右侧悬浮功能按钮（地图图层切换）
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            // 图层切换按钮
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0xFF22272E) else Color.White)
                    .border(
                        width = 1.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFE5E8EC),
                        shape = CircleShape
                    )
                    .noRippleClickable { showMapTypeDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Layers,
                    contentDescription = stringResource(R.string.map_layers),
                    tint = AccentGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 底部扫街采集控制按钮
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { viewModel.toggleContinuousScanning() },
                containerColor = if (uiState.isContinuousScanning) MaterialTheme.colorScheme.surface else AccentGreen,
                contentColor = if (uiState.isContinuousScanning) AccentGreen else Color.White,
                shape = RoundedCornerShape(24.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                icon = { Icon(Icons.Rounded.Radar, null, modifier = Modifier.size(22.dp)) },
                text = {
                    Text(
                        text = if (uiState.isContinuousScanning) {
                            stringResource(R.string.stop_collection)
                        } else {
                            stringResource(R.string.start_collection)
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
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
}
