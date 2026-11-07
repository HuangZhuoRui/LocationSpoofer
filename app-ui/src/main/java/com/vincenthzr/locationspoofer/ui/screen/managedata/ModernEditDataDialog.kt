package com.vincenthzr.locationspoofer.ui.screen.managedata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.db.CompleteLocation
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun ModernEditDataDialog(
    item: CompleteLocation,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onSave: (lat: Double, lng: Double, placeName: String, remark: String, selectedWifiBssid: String?, selectedBluetoothAddress: String?, selectedCellKey: String?) -> Unit,
    onSaveWifi: (bssid: String, ssid: String, frequency: Int, level: Int, capabilities: String, vendor: String, isConnected: Boolean, isDesignated: Boolean) -> Unit,
    onDeleteWifi: (bssid: String) -> Unit,
    onSaveCell: (cellKey: String, type: String, mcc: Int, mnc: Int, tac: Int, ci: Int, pci: Int, lac: Int, cid: Int, psc: Int, nci: Long, networkId: Int, systemId: Int, basestationId: Int, dbm: Int, isRegistered: Boolean, isDesignated: Boolean) -> Unit,
    onDeleteCell: (cellKey: String) -> Unit,
    onSaveBluetooth: (address: String, name: String, scanRecordHex: String, rssi: Int, isDesignated: Boolean) -> Unit,
    onDeleteBluetooth: (address: String) -> Unit
) {
    var latText by remember(item.location.id) {
        mutableStateOf(String.format(Locale.US, "%.6f", item.location.lat))
    }
    var lngText by remember(item.location.id) {
        mutableStateOf(String.format(Locale.US, "%.6f", item.location.lng))
    }
    var placeName by remember(item.location.id) { mutableStateOf(item.location.placeName) }
    var remark by remember(item.location.id) { mutableStateOf(item.location.remark) }
    var selectedWifiBssid by remember(item.location.id, item.location.selectedWifiBssid) {
        mutableStateOf(item.location.selectedWifiBssid)
    }
    var selectedBluetoothAddress by remember(item.location.id, item.location.selectedBluetoothAddress) {
        mutableStateOf(item.location.selectedBluetoothAddress)
    }
    var selectedCellKey by remember(item.location.id, item.location.selectedCellKey) {
        mutableStateOf(item.location.selectedCellKey)
    }

    var selectedTab by remember { mutableStateOf(EditDataTab.WIFI) }

    // Wi-Fi 弹窗状态
    var wifiBeingEdited by remember { mutableStateOf<EditableWifiItem?>(null) }
    var isNewWifiDialog by remember { mutableStateOf(false) }
    var wifiToDelete by remember { mutableStateOf<String?>(null) }

    // 基站弹窗状态
    var cellBeingEdited by remember { mutableStateOf<EditableCellItem?>(null) }
    var isNewCellDialog by remember { mutableStateOf(false) }
    var cellToDelete by remember { mutableStateOf<String?>(null) }

    // 蓝牙弹窗状态
    var btBeingEdited by remember { mutableStateOf<EditableBluetoothItem?>(null) }
    var isNewBtDialog by remember { mutableStateOf(false) }
    var btToDelete by remember { mutableStateOf<String?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val timeStr =
        remember(item.location.timestamp) { dateFormat.format(Date(item.location.timestamp)) }

    val wifiList = remember(item, selectedWifiBssid) {
        val list = mutableListOf<EditableWifiItem>()
        val seenBssids = mutableSetOf<String>()

        item.connectedWifi?.let { cw ->
            seenBssids.add(cw.bssid.uppercase())
            list.add(
                EditableWifiItem(
                    bssid = cw.bssid,
                    ssid = cw.ssid,
                    frequency = cw.frequency,
                    level = cw.level,
                    capabilities = cw.capabilities,
                    vendor = cw.vendor,
                    isConnected = true,
                    isDesignated = selectedWifiBssid?.equals(cw.bssid, ignoreCase = true) == true
                )
            )
        }

        item.wifis.forEach { lw ->
            if (!seenBssids.contains(lw.device.bssid.uppercase())) {
                seenBssids.add(lw.device.bssid.uppercase())
                list.add(
                    EditableWifiItem(
                        bssid = lw.device.bssid,
                        ssid = lw.device.ssid,
                        frequency = lw.device.frequency,
                        level = lw.locationWifi.level,
                        capabilities = lw.device.capabilities,
                        vendor = lw.device.vendor,
                        isConnected = false,
                        isDesignated = selectedWifiBssid?.equals(lw.device.bssid, ignoreCase = true) == true
                    )
                )
            }
        }
        list
    }

    val cellList = remember(item, selectedCellKey) {
        val list = mutableListOf<EditableCellItem>()
        val seenKeys = mutableSetOf<String>()

        item.cells.forEach { lc ->
            if (!seenKeys.contains(lc.device.cellKey)) {
                seenKeys.add(lc.device.cellKey)
                list.add(
                    EditableCellItem(
                        cellKey = lc.device.cellKey,
                        type = lc.device.type,
                        mcc = lc.device.mcc,
                        mnc = lc.device.mnc,
                        tac = lc.device.tac,
                        ci = lc.device.ci,
                        pci = lc.device.pci,
                        lac = lc.device.lac,
                        cid = lc.device.cid,
                        psc = lc.device.psc,
                        nci = lc.device.nci,
                        networkId = lc.device.networkId,
                        systemId = lc.device.systemId,
                        basestationId = lc.device.basestationId,
                        dbm = lc.locationCell.dbm,
                        isRegistered = lc.locationCell.isRegistered,
                        isDesignated = selectedCellKey?.equals(lc.device.cellKey, ignoreCase = true) == true
                    )
                )
            }
        }
        list
    }

    val btList = remember(item, selectedBluetoothAddress) {
        val list = mutableListOf<EditableBluetoothItem>()
        val seenAddresses = mutableSetOf<String>()

        item.bluetooths.forEach { lb ->
            if (!seenAddresses.contains(lb.device.address.uppercase())) {
                seenAddresses.add(lb.device.address.uppercase())
                list.add(
                    EditableBluetoothItem(
                        address = lb.device.address,
                        name = lb.device.name,
                        scanRecordHex = lb.device.scanRecordHex,
                        rssi = lb.locationBluetooth.rssi,
                        isDesignated = selectedBluetoothAddress?.equals(lb.device.address, ignoreCase = true) == true
                    )
                )
            }
        }
        list
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(AccentBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.EditLocationAlt,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.edit_location_data),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = timeStr,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 可滚动内容区域
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 坐标概览与经纬度编辑卡片
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Rounded.Explore,
                                        contentDescription = null,
                                        tint = AccentBlue,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.edit_coordinates_title),
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Text(
                                    text = "ID: #${item.location.id}",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }

                            Text(
                                text = stringResource(R.string.edit_coordinates_desc),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                lineHeight = 16.sp
                            )

                            // 经纬度输入行
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 纬度输入
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text(
                                            text = stringResource(R.string.latitude),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                        )
                                        BasicTextField(
                                            value = latText,
                                            onValueChange = { latText = it },
                                            textStyle = TextStyle(
                                                fontSize = 13.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                    }
                                }

                                // 经度输入
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text(
                                            text = stringResource(R.string.longitude),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                        )
                                        BasicTextField(
                                            value = lngText,
                                            onValueChange = { lngText = it },
                                            textStyle = TextStyle(
                                                fontSize = 13.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SignalChip(
                                    icon = Icons.Rounded.Wifi,
                                    text = "${wifiList.size} Wi-Fi",
                                    tint = AccentBlue,
                                    isDark = isDark
                                )
                                SignalChip(
                                    icon = Icons.Rounded.CellTower,
                                    text = "${cellList.size} ${stringResource(R.string.tag_cell)}",
                                    tint = AccentOrange,
                                    isDark = isDark
                                )
                                SignalChip(
                                    icon = Icons.Rounded.Bluetooth,
                                    text = "${btList.size} ${stringResource(R.string.tag_bluetooth)}",
                                    tint = Color(0xFF9C27B0),
                                    isDark = isDark
                                )
                            }
                        }
                    }

                    // 地点名称输入框
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Place,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicTextField(
                                value = placeName,
                                onValueChange = { placeName = it },
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                ),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (placeName.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.set_place_name_hint),
                                            fontSize = 13.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (placeName.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                        .noRippleClickable { placeName = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 备注描述输入框
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Rounded.Notes,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicTextField(
                                value = remark,
                                onValueChange = { remark = it },
                                textStyle = TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Normal
                                ),
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (remark.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.add_remark_hint),
                                            fontSize = 13.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (remark.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                        .noRippleClickable { remark = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 选项卡切换器：[Wi-Fi] [基站] [蓝牙]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EditDataTab.values().forEach { tab ->
                            val isCurrent = selectedTab == tab
                            val tabTitle = when (tab) {
                                EditDataTab.WIFI -> stringResource(R.string.manage_tab_wifi, wifiList.size)
                                EditDataTab.CELL -> stringResource(R.string.manage_tab_cell, cellList.size)
                                EditDataTab.BLUETOOTH -> stringResource(R.string.manage_tab_bluetooth, btList.size)
                            }
                            val tabColor = when (tab) {
                                EditDataTab.WIFI -> AccentBlue
                                EditDataTab.CELL -> AccentOrange
                                EditDataTab.BLUETOOTH -> Color(0xFF9C27B0)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isCurrent) {
                                            if (isDark) Color.White.copy(alpha = 0.12f) else Color.White
                                        } else Color.Transparent
                                    )
                                    .then(
                                        if (isCurrent) {
                                            Modifier.border(
                                                0.6.dp,
                                                if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.08f),
                                                RoundedCornerShape(9.dp)
                                            )
                                        } else Modifier
                                    )
                                    .noRippleClickable { selectedTab = tab },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tabTitle,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) tabColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    // 选项卡内容区
                    when (selectedTab) {
                        EditDataTab.WIFI -> {
                            ManageWifiTabContent(
                                wifiList = wifiList,
                                selectedWifiBssid = selectedWifiBssid,
                                isDark = isDark,
                                onSelectBssid = { selectedWifiBssid = it },
                                onAddWifi = {
                                    isNewWifiDialog = true
                                    wifiBeingEdited = null
                                },
                                onEditWifi = { wifi ->
                                    wifiBeingEdited = wifi
                                    isNewWifiDialog = false
                                },
                                onDeleteWifi = { bssid -> wifiToDelete = bssid }
                            )
                        }

                        EditDataTab.CELL -> {
                            ManageCellTabContent(
                                cellList = cellList,
                                selectedCellKey = selectedCellKey,
                                isDark = isDark,
                                onSelectCellKey = { selectedCellKey = it },
                                onAddCell = {
                                    isNewCellDialog = true
                                    cellBeingEdited = null
                                },
                                onEditCell = { cell ->
                                    cellBeingEdited = cell
                                    isNewCellDialog = false
                                },
                                onDeleteCell = { cellKey -> cellToDelete = cellKey }
                            )
                        }

                        EditDataTab.BLUETOOTH -> {
                            ManageBluetoothTabContent(
                                btList = btList,
                                selectedBluetoothAddress = selectedBluetoothAddress,
                                isDark = isDark,
                                onSelectAddress = { selectedBluetoothAddress = it },
                                onAddBluetooth = {
                                    isNewBtDialog = true
                                    btBeingEdited = null
                                },
                                onEditBluetooth = { bt ->
                                    btBeingEdited = bt
                                    isNewBtDialog = false
                                },
                                onDeleteBluetooth = { address -> btToDelete = address }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(
                                    alpha = 0.05f
                                )
                            )
                            .noRippleClickable(onClick = onDismiss),
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
                            val parsedLat = latText.toDoubleOrNull() ?: item.location.lat
                            val parsedLng = lngText.toDoubleOrNull() ?: item.location.lng
                            onSave(parsedLat, parsedLng, placeName, remark, selectedWifiBssid, selectedBluetoothAddress, selectedCellKey)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(44.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.save_changes),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // 编辑或新增 Wi-Fi 详细参数弹窗
    if (wifiBeingEdited != null || isNewWifiDialog) {
        EditWifiDialog(
            initialItem = wifiBeingEdited,
            isDark = isDark,
            onDismiss = {
                wifiBeingEdited = null
                isNewWifiDialog = false
            },
            onSave = { bssid, ssid, frequency, level, capabilities, vendor, isConnected, isDesignated ->
                onSaveWifi(bssid, ssid, frequency, level, capabilities, vendor, isConnected, isDesignated)
                if (isDesignated) {
                    selectedWifiBssid = bssid
                }
                wifiBeingEdited = null
                isNewWifiDialog = false
            }
        )
    }

    // 删除单项 Wi-Fi 二次确认
    if (wifiToDelete != null) {
        val targetBssid = wifiToDelete!!
        ManageDeleteConfirmDialog(
            message = stringResource(R.string.delete_wifi_confirm, targetBssid),
            isDark = isDark,
            onDismiss = { wifiToDelete = null },
            onConfirm = {
                onDeleteWifi(targetBssid)
                if (selectedWifiBssid?.equals(targetBssid, ignoreCase = true) == true) {
                    selectedWifiBssid = null
                }
                wifiToDelete = null
            }
        )
    }

    // 编辑或新增基站详细参数弹窗
    if (cellBeingEdited != null || isNewCellDialog) {
        EditCellDialog(
            initialItem = cellBeingEdited,
            isDark = isDark,
            onDismiss = {
                cellBeingEdited = null
                isNewCellDialog = false
            },
            onSave = { cellKey, type, mcc, mnc, tac, ci, pci, lac, cid, psc, nci, networkId, systemId, basestationId, dbm, isRegistered, isDesignated ->
                onSaveCell(cellKey, type, mcc, mnc, tac, ci, pci, lac, cid, psc, nci, networkId, systemId, basestationId, dbm, isRegistered, isDesignated)
                if (isDesignated) {
                    selectedCellKey = cellKey
                }
                cellBeingEdited = null
                isNewCellDialog = false
            }
        )
    }

    // 删除单项基站二次确认
    if (cellToDelete != null) {
        val targetCellKey = cellToDelete!!
        ManageDeleteConfirmDialog(
            message = stringResource(R.string.delete_cell_confirm, targetCellKey),
            isDark = isDark,
            onDismiss = { cellToDelete = null },
            onConfirm = {
                onDeleteCell(targetCellKey)
                if (selectedCellKey?.equals(targetCellKey, ignoreCase = true) == true) {
                    selectedCellKey = null
                }
                cellToDelete = null
            }
        )
    }

    // 编辑或新增蓝牙详细参数弹窗
    if (btBeingEdited != null || isNewBtDialog) {
        EditBluetoothDialog(
            initialItem = btBeingEdited,
            isDark = isDark,
            onDismiss = {
                btBeingEdited = null
                isNewBtDialog = false
            },
            onSave = { address, name, scanRecordHex, rssi, isDesignated ->
                onSaveBluetooth(address, name, scanRecordHex, rssi, isDesignated)
                if (isDesignated) {
                    selectedBluetoothAddress = address
                }
                btBeingEdited = null
                isNewBtDialog = false
            }
        )
    }

    // 删除单项蓝牙二次确认
    if (btToDelete != null) {
        val targetAddress = btToDelete!!
        ManageDeleteConfirmDialog(
            message = stringResource(R.string.delete_bt_confirm, targetAddress),
            isDark = isDark,
            onDismiss = { btToDelete = null },
            onConfirm = {
                onDeleteBluetooth(targetAddress)
                if (selectedBluetoothAddress?.equals(targetAddress, ignoreCase = true) == true) {
                    selectedBluetoothAddress = null
                }
                btToDelete = null
            }
        )
    }
}
