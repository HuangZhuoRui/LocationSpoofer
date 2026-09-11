package com.suseoaa.locationspoofer.ui.screen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.suseoaa.locationspoofer.ui.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.ImportExportSelection
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.AccentOrange
import com.suseoaa.locationspoofer.ui.theme.AppColors
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import com.suseoaa.locationspoofer.viewmodel.exportEnvironmentData
import com.suseoaa.locationspoofer.viewmodel.refreshRecordCount
import com.suseoaa.locationspoofer.viewmodel.toggleContinuousScanning
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

private val REQUIRED_PERMISSIONS: List<String>
    get() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

/**
 * 没有 root 时的落地页：主功能（虚拟定位注入）用不了，但环境数据采集
 * （EnvironmentScanner.scanWifi/scanCell/scanBluetooth）纯走标准 Android API，
 * 不依赖 root/LSPosed，所以让没有 root 的设备也能完成"实地采集 + 导出"，
 * 交给另一台有 root 的设备导入使用。
 */
@Composable
fun DataCollectionAssistScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshRecordCount()
    }

    var missingPermissions by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportEnvironmentData(
                it,
                ImportExportSelection(
                    locations = true,
                    savedLocations = false,
                    savedRoutes = false,
                    appCoordinateSystems = false,
                    settings = false,
                    apiKeys = false
                )
            ) { success ->
                Toast.makeText(
                    context,
                    context.getString(if (success) R.string.export_success else R.string.export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 说明卡：视觉呼应 BlockingScreen，同时说明依然能帮忙采集数据
            MiuixCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                insideMargin = PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.root_required),
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.root_message),
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.data_collection_assist_intro),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }

            // 权限缺失提示
            if (missingPermissions.isNotEmpty()) {
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.data_collection_permission_missing),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val activity = context as? androidx.activity.ComponentActivity
                            val stillHidden = missingPermissions.any {
                                activity != null && !activity.shouldShowRequestPermissionRationale(it)
                            }
                            if (stillHidden) {
                                try {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            } else {
                                permissionLauncher.launch(missingPermissions.toTypedArray())
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange)
                    ) {
                        Text(stringResource(R.string.go_to_settings), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 采集状态与操作卡
            MiuixCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                insideMargin = PaddingValues(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.isContinuousScanning) AccentGreen
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isContinuousScanning) {
                            stringResource(R.string.scanning_status_active, uiState.environmentRecordCount)
                        } else {
                            stringResource(R.string.scanning_status_inactive)
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ScanCountBadge(
                        icon = Icons.Rounded.Wifi,
                        tint = AccentBlue,
                        text = stringResource(R.string.scanned_wifi_count, uiState.scannedWifiCount),
                        modifier = Modifier.weight(1f)
                    )
                    ScanCountBadge(
                        icon = Icons.Rounded.CellTower,
                        tint = AccentGreen,
                        text = stringResource(R.string.scanned_cell_count, uiState.scannedCellCount),
                        modifier = Modifier.weight(1f)
                    )
                    ScanCountBadge(
                        icon = Icons.Rounded.Bluetooth,
                        tint = AccentOrange,
                        text = stringResource(R.string.scanned_bt_count, uiState.scannedBluetoothCount),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.data_collection_assist_hint),
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = { viewModel.toggleContinuousScanning() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isContinuousScanning) MaterialTheme.colorScheme.error else AccentGreen
                    )
                ) {
                    Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
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
            }

            // 导出卡
            MiuixCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                insideMargin = PaddingValues(16.dp)
            ) {
                if (uiState.environmentRecordCount > 0) {
                    Button(
                        onClick = {
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                            exportLauncher.launch("environment_data_$stamp.json")
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text(stringResource(R.string.export_data), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = stringResource(R.string.no_data_collected),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanCountBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.1f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}
