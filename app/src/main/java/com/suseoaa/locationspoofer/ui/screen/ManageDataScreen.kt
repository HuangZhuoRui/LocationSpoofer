package com.suseoaa.locationspoofer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.db.CompleteLocation
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.suseoaa.locationspoofer.ui.components.AppMapView
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.utils.MapCoverageHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageDataScreen(
    viewModel: MainViewModel,
    uiState: com.suseoaa.locationspoofer.data.model.AppState,
    isDark: Boolean,
    onClose: () -> Unit
) {
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var mapController by remember { mutableStateOf<AppMapController?>(null) }
    
    var editingItem by remember { mutableStateOf<CompleteLocation?>(null) }

    val dataList = uiState.manageDataList
    
    LaunchedEffect(mapController, uiState.mapType) {
        mapController?.setMapType(uiState.mapType)
    }

    LaunchedEffect(mapController, dataList) {
        val controller = mapController ?: return@LaunchedEffect
        controller.clear()
        val locations = dataList.map { it.location }
        MapCoverageHelper.drawCoverage(controller, locations)
        if (locations.isNotEmpty()) {
            val last = locations.last()
            controller.moveCamera(last.lat, last.lng, 15f)
        }
    }

    // 拦截返回键：如果处于选择模式，则先退出选择模式
    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds.clear()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(stringResource(R.string.selected_items, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.title_manage_data))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedIds.clear()
                        } else {
                            onClose()
                        }
                    }) {
                        Icon(
                            if (isSelectionMode) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            if (selectedIds.size == dataList.size) {
                                selectedIds.clear()
                            } else {
                                selectedIds.clear()
                                selectedIds.addAll(dataList.map { it.location.id })
                            }
                        }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                        if (selectedIds.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.deleteManageData(selectedIds.toList())
                                isSelectionMode = false
                                selectedIds.clear()
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Rounded.Checklist, contentDescription = stringResource(R.string.select_all))
                        }
                        IconButton(onClick = { showClearAllConfirm = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear_all), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = com.suseoaa.locationspoofer.ui.theme.AppColors.topBarBackground(isDark),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        if (uiState.manageDataIsLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (dataList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_data_collected), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Top Map
                Box(modifier = Modifier.fillMaxWidth().weight(0.4f)) {
                    AppMapView(
                        mapEngine = uiState.mapEngine,
                        isDomestic = viewModel.isDomesticEnvironment(),
                        modifier = Modifier.fillMaxSize(),
                        onMapReady = { controller ->
                            mapController = controller
                            controller.disableUiControls()
                        }
                    )
                }
                
                // Bottom List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.6f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(dataList, key = { it.location.id }) { item ->
                        val isSelected = selectedIds.contains(item.location.id)
                        DataListItem(
                            item = item,
                            isDark = isDark,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onSelect = {
                                if (isSelected) selectedIds.remove(item.location.id)
                                else selectedIds.add(item.location.id)
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds.add(item.location.id)
                                }
                            },
                            onClick = {
                                viewModel.updateLatitude(String.format(Locale.US, "%.6f", item.location.lat))
                                viewModel.updateLongitude(String.format(Locale.US, "%.6f", item.location.lng))
                                mapController?.animateCamera(item.location.lat, item.location.lng, 17f)
                                // Optionally close the screen to return to SpoofingScreen?
                                // onClose() // Let's keep it open so they can see the map move
                            },
                            onDeleteSingle = {
                                viewModel.deleteManageDataSingle(item.location.id)
                            },
                            onEdit = {
                                editingItem = item
                            }
                        )
                    }
                }
            }
        }
    }

    if (editingItem != null) {
        var placeName by remember { mutableStateOf(editingItem!!.location.placeName) }
        var remark by remember { mutableStateOf(editingItem!!.location.remark) }
        
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text(stringResource(R.string.edit_location_data)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = placeName,
                        onValueChange = { placeName = it },
                        label = { Text(stringResource(R.string.place_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remark,
                        onValueChange = { remark = it },
                        label = { Text(stringResource(R.string.remark_note)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateManageDataMetadata(editingItem!!.location.id, placeName, remark)
                    editingItem = null
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingItem = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.clear_all_data)) },
            text = { Text(stringResource(R.string.clear_all_data_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllManageData()
                        showClearAllConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DataListItem(
    item: CompleteLocation,
    isDark: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDeleteSingle: () -> Unit,
    onEdit: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(item.location.timestamp) { dateFormat.format(Date(item.location.timestamp)) }
    
    val wifiCount = item.wifis.size
    val cellCount = item.cells.size
    val btCount = item.bluetooths.size

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { 
                    if (isSelectionMode) onSelect() 
                    else onClick()
                },
                onLongClick = onLongClick
            ),
        color = if (isSelected) com.suseoaa.locationspoofer.ui.theme.AccentBlue.copy(alpha = 0.15f) else com.suseoaa.locationspoofer.ui.theme.AppColors.cardBackground(isDark),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Lat: ${String.format("%.5f", item.location.lat)}, Lng: ${String.format("%.5f", item.location.lng)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconTextRow(Icons.Rounded.Wifi, "$wifiCount")
                    IconTextRow(Icons.Rounded.CellTower, "$cellCount")
                    IconTextRow(Icons.Rounded.Bluetooth, "$btCount")
                }
                
                if (item.location.placeName.isNotBlank() || item.location.remark.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(4.dp))
                    if (item.location.placeName.isNotBlank()) {
                        Text(
                            text = "📍 ${item.location.placeName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.location.remark.isNotBlank()) {
                        Text(
                            text = "📝 ${item.location.remark}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            if (!isSelectionMode) {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.edit_location_data),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                    IconButton(onClick = onDeleteSingle) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconTextRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}
