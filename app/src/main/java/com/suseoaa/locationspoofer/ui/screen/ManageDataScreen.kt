package com.suseoaa.locationspoofer.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.db.CompleteLocation
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.ui.components.AppMapView
import com.suseoaa.locationspoofer.ui.screen.managedata.ModernEditDataDialog
import com.suseoaa.locationspoofer.ui.screen.managedata.SwipeableDataListItem
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.AppColors
import com.suseoaa.locationspoofer.ui.theme.noRippleClickable
import com.suseoaa.locationspoofer.utils.MapCoverageHelper
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import com.suseoaa.locationspoofer.viewmodel.ManageDataViewModel
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@Composable
fun ManageDataScreen(
    viewModel: MainViewModel,
    uiState: com.suseoaa.locationspoofer.data.model.AppState,
    isDark: Boolean,
    onClose: () -> Unit,
    manageDataViewModel: ManageDataViewModel = koinViewModel()
) {
    val context = LocalContext.current
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    var editingItem by remember { mutableStateOf<CompleteLocation?>(null) }
    var itemToDelete by remember { mutableStateOf<CompleteLocation?>(null) }

    val manageDataUiState by manageDataViewModel.uiState.collectAsState()
    val dataList = manageDataUiState.dataList

    LaunchedEffect(dataList) {
        viewModel.onManageDataChanged()
    }

    BackHandler(onBack = onClose)

    LaunchedEffect(mapController, uiState.mapType) {
        mapController?.setMapType(uiState.mapType)
    }

    LaunchedEffect(mapController, dataList) {
        val controller = mapController ?: return@LaunchedEffect
        controller.clear()
        val locations = dataList.map { it.location }
        val last = locations.lastOrNull()
        MapCoverageHelper.drawCoverage(controller, locations, last?.lat, last?.lng)
        if (last != null) {
            controller.moveCamera(last.lat, last.lng, 15f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 顶部导航栏（现代化独立圆形返回按键与标题，无右侧杂乱按钮）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 独立立体圆形返回按键
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF22272E) else Color.White)
                        .border(
                            width = 1.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(
                                0xFFE5E8EC
                            ),
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

                Spacer(Modifier.width(14.dp))

                Column {
                    Text(
                        text = stringResource(R.string.title_manage_data),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.total_collected_data_count, dataList.size),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }

            if (manageDataUiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentBlue)
                }
            } else if (dataList.isEmpty()) {
                // 空数据状态质感呈现
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(
                                        alpha = 0.03f
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.no_data_collected),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.manage_data_empty_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                // 地图概览卡片
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.36f)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(
                            0.8.dp,
                            if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f),
                            RoundedCornerShape(18.dp)
                        )
                ) {
                    AppMapView(
                        mapEngine = uiState.mapEngine,
                        isDomestic = viewModel.isDomesticEnvironment(),
                        modifier = Modifier.fillMaxSize(),
                        onMapReady = { controller ->
                            mapController = controller
                            controller.disableUiControls()
                        }
                    )

                    // 地图右上角覆盖范围提示胶囊
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
                            .border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(AccentGreen)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.points_drawn_count, dataList.size),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // 下部数据卡片列表（向左滑动显露编辑与删除操作，顶部平滑溶解边界）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.64f)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(dataList, key = { it.location.id }) { item ->
                            SwipeableDataListItem(
                                item = item,
                                isDark = isDark,
                                onClick = {
                                    viewModel.selectCollectedLocation(item.location.id)
                                    mapController?.animateCamera(
                                        item.location.lat,
                                        item.location.lng,
                                        17f
                                    )
                                },
                                onEdit = { editingItem = item },
                                onDelete = { itemToDelete = item },
                                onFavorite = {
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
                                }
                            )
                        }
                    }

                    // 顶部边界模糊渐变过渡（消除生硬切边）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        AppColors.background(isDark),
                                        AppColors.background(isDark).copy(alpha = 0.85f),
                                        AppColors.background(isDark).copy(alpha = 0.40f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }
            }
        }
    }

    // 现代化编辑数据弹窗
    if (editingItem != null) {
        val currentItem = dataList.find { it.location.id == editingItem?.location?.id } ?: editingItem!!
        ModernEditDataDialog(
            item = currentItem,
            isDark = isDark,
            onDismiss = { editingItem = null },
            onSave = { lat, lng, placeName, remark, selectedWifiBssid, selectedBluetoothAddress, selectedCellKey ->
                manageDataViewModel.updateManageDataMetadata(
                    currentItem.location.id,
                    lat,
                    lng,
                    placeName,
                    remark,
                    selectedWifiBssid,
                    selectedBluetoothAddress,
                    selectedCellKey
                )
                viewModel.selectCollectedLocation(currentItem.location.id)
                editingItem = null
            },
            onSaveWifi = { bssid, ssid, frequency, level, capabilities, vendor, isConnected, isDesignated ->
                manageDataViewModel.saveOrUpdateLocationWifi(
                    locationId = currentItem.location.id,
                    bssid = bssid,
                    ssid = ssid,
                    frequency = frequency,
                    level = level,
                    capabilities = capabilities,
                    vendor = vendor,
                    isConnected = isConnected,
                    isDesignatedSimulation = isDesignated
                )
            },
            onDeleteWifi = { bssid ->
                manageDataViewModel.deleteLocationWifi(
                    locationId = currentItem.location.id,
                    bssid = bssid
                )
            },
            onSaveCell = { cellKey, type, mcc, mnc, tac, ci, pci, lac, cid, psc, nci, networkId, systemId, basestationId, dbm, isRegistered, isDesignated ->
                manageDataViewModel.saveOrUpdateLocationCell(
                    locationId = currentItem.location.id,
                    cellKey = cellKey,
                    type = type,
                    mcc = mcc,
                    mnc = mnc,
                    tac = tac,
                    ci = ci,
                    pci = pci,
                    lac = lac,
                    cid = cid,
                    psc = psc,
                    nci = nci,
                    networkId = networkId,
                    systemId = systemId,
                    basestationId = basestationId,
                    dbm = dbm,
                    isRegistered = isRegistered,
                    isDesignated = isDesignated
                )
            },
            onDeleteCell = { cellKey ->
                manageDataViewModel.deleteLocationCell(
                    locationId = currentItem.location.id,
                    cellKey = cellKey
                )
            },
            onSaveBluetooth = { address, name, scanRecordHex, rssi, isDesignated ->
                manageDataViewModel.saveOrUpdateLocationBluetooth(
                    locationId = currentItem.location.id,
                    address = address,
                    name = name,
                    scanRecordHex = scanRecordHex,
                    rssi = rssi,
                    isDesignated = isDesignated
                )
            },
            onDeleteBluetooth = { address ->
                manageDataViewModel.deleteLocationBluetooth(
                    locationId = currentItem.location.id,
                    address = address
                )
            }
        )
    }

    // 删除单项采集数据二次确认弹窗
    if (itemToDelete != null) {
        val targetItem = itemToDelete!!
        val displayName = when {
            targetItem.location.placeName.isNotBlank() -> targetItem.location.placeName
            targetItem.location.remark.isNotBlank() -> targetItem.location.remark
            else -> "坐标 (${
                String.format(
                    Locale.US,
                    "%.4f",
                    targetItem.location.lat
                )
            }, ${String.format(Locale.US, "%.4f", targetItem.location.lng)})"
        }

        Dialog(
            onDismissRequest = { itemToDelete = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(Color(0xFFE53935).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.delete_data_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Text(
                        text = stringResource(R.string.delete_data_confirm_format, displayName),
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 20.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(
                                        alpha = 0.05f
                                    )
                                )
                                .noRippleClickable { itemToDelete = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.cancel),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }

                        Button(
                            onClick = {
                                manageDataViewModel.deleteManageDataSingle(targetItem.location.id)
                                itemToDelete = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(42.dp)
                        ) {
                            Text(
                                stringResource(R.string.delete),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
