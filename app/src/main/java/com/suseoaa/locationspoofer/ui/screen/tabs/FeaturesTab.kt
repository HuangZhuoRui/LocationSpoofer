package com.suseoaa.locationspoofer.ui.screen.tabs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.ui.screen.AppCoordinateConfigCard
import com.suseoaa.locationspoofer.ui.screen.FooterLinks
import com.suseoaa.locationspoofer.ui.screen.ImportExportDataCard
import com.suseoaa.locationspoofer.ui.screen.ManageDataCard
import com.suseoaa.locationspoofer.ui.screen.ScannerMapCard
import com.suseoaa.locationspoofer.ui.components.ImportExportSelectionDialog
import com.suseoaa.locationspoofer.data.model.ImportExportCounts
import com.suseoaa.locationspoofer.data.model.ImportExportSelection
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AppColors
import com.suseoaa.locationspoofer.viewmodel.MainViewModel

@Composable
fun FeaturesTab(
    viewModel: MainViewModel,
    uiState: AppState,
    tabBarHeight: Dp = 90.dp,
    onNavigateToCoordinate: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToManageData: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current

    // 导出：先让用户选内容，再拉起系统选择器（SAF 必须在拉起前就知道要写什么）
    var exportCounts by remember { mutableStateOf<ImportExportCounts?>(null) }
    var exportSelection by remember { mutableStateOf(ImportExportSelection()) }
    // 导入：先解析出文件里有什么，再让用户勾选要应用的部分
    var pendingImportPackage by remember {
        mutableStateOf<com.suseoaa.locationspoofer.data.db.LocationSpooferDataPackage?>(null)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            viewModel.exportEnvironmentData(it, exportSelection) { success ->
                Toast.makeText(
                    context,
                    context.getString(if (success) R.string.export_success else R.string.export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.parseImportPackage(it) { pkg ->
                if (pkg == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_parse_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    pendingImportPackage = pkg
                }
            }
        }
    }

    exportCounts?.let { counts ->
        ImportExportSelectionDialog(
            isExport = true,
            counts = counts,
            onConfirm = { selection ->
                exportSelection = selection
                exportCounts = null
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(java.util.Date())
                exportLauncher.launch("environment_data_$stamp.json")
            },
            onDismiss = { exportCounts = null }
        )
    }

    pendingImportPackage?.let { pkg ->
        ImportExportSelectionDialog(
            isExport = false,
            counts = ImportExportCounts(
                locations = pkg.locations.size,
                savedLocations = pkg.savedLocations.size,
                savedRoutes = pkg.savedRoutes.size,
                appCoordinateSystems = pkg.appCoordinateSystems.size,
                settings = if (pkg.settings != null) 1 else 0,
                apiKeys = if (pkg.apiKeys != null) 1 else 0
            ),
            onConfirm = { selection ->
                pendingImportPackage = null
                viewModel.applyImportPackage(pkg, selection) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_merge_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { pendingImportPackage = null }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(AccentBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Extension, null, tint = AccentBlue)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.features_tab_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.features_tab_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = tabBarHeight + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. 采集本地数据
                item {
                    ScannerMapCard(
                        uiState = uiState,
                        isDark = isDark,
                        onClick = onNavigateToScanner
                    )
                }

                // 2. 管理本地数据
                item {
                    ManageDataCard(
                        isDark = isDark,
                        onClick = onNavigateToManageData
                    )
                }

                // 3. 配置应用坐标系
                item {
                    AppCoordinateConfigCard(
                        isDark = isDark,
                        onClick = onNavigateToCoordinate
                    )
                }

                // 4. 导入与导出数据
                item {
                    ImportExportDataCard(
                        isDark = isDark,
                        onImportClick = {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        onExportClick = {
                            // 先查各分类数量，弹出选择对话框；实际拉起 SAF 在对话框确认之后。
                            // 文件名在那里再带上时间戳，保证每次导出都是新文件名：
                            // 固定用 environment_data.json 时第二次导出必然撞名，而部分 ROM 的
                            // DocumentsProvider 处理重名的方式是另建一个 xxx_1.json 空占位文件、
                            // 却把返回的 Uri 指向原来那个旧文件，导致"旧备份被覆盖 + 多出 0B 空文件"（issue #57）。
                            viewModel.collectExportCounts { counts -> exportCounts = counts }
                        }
                    )
                }
            }
        }
    }
}
