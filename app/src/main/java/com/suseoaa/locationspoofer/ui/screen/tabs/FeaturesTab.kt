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

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            viewModel.exportEnvironmentData(it) { success ->
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
            viewModel.importEnvironmentData(it) {
                Toast.makeText(context, context.getString(R.string.import_merge_success), Toast.LENGTH_SHORT).show()
            }
        }
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
                            // 文件名带时间戳，保证每次导出都是新文件名。
                            // 固定用 environment_data.json 时，第二次导出必然撞名，
                            // 而部分 ROM 的 DocumentsProvider 处理重名的方式是：
                            // 另建一个 xxx_1.json 空占位文件，却把返回的 Uri 指向原来那个旧文件，
                            // 结果就是"旧备份被新数据覆盖 + 多出一个 0B 空文件"（见 issue #57）。
                            // 从源头避免重名，就不会走到 ROM 那套有问题的重名处理逻辑上。
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                            exportLauncher.launch("environment_data_$stamp.json")
                        }
                    )
                }
            }
        }
    }
}
