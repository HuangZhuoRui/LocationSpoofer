package com.suseoaa.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.ui.R
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.AccentOrange
import com.suseoaa.locationspoofer.ui.theme.AppColors
import com.suseoaa.locationspoofer.ui.theme.noRippleClickable
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun SectionHeader(icon: ImageVector, title: String, isDark: Boolean) {
    val textSecondary = AppColors.textSecondary(isDark)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            color = textSecondary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun AppCoordinateConfigCard(isDark: Boolean, onClick: () -> Unit) {
    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { onClick() },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Extension,
                    null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.config_app_coordinate),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.config_app_coordinate_desc),
                    color = AppColors.textSecondary(isDark),
                    fontSize = 12.sp
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                tint = AppColors.textSecondary(isDark).copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun SystemHookAppsConfigCard(uiState: com.suseoaa.locationspoofer.data.model.AppState, isDark: Boolean, onClick: () -> Unit) {
    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { onClick() },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentOrange.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Shield,
                    null,
                    tint = AccentOrange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.system_hook_apps_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                val statusDesc = if (uiState.isSystemHookGlobalMode) {
                    stringResource(R.string.system_hook_global_mode_title) + "（所有应用生效）"
                } else if (uiState.systemHookPackages.isNotEmpty()) {
                    stringResource(R.string.selected_count_format, uiState.systemHookPackages.size)
                } else {
                    stringResource(R.string.system_hook_apps_card_desc)
                }
                Text(
                    statusDesc,
                    color = if (uiState.isSystemHookGlobalMode) AccentBlue else AppColors.textSecondary(isDark),
                    fontSize = 12.sp
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                tint = AppColors.textSecondary(isDark).copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ScannerMapCard(
    isDark: Boolean,
    uiState: AppState,
    onClick: () -> Unit
) {
    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { onClick() },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentGreen.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Map, null, tint = AccentGreen, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.env_map_scan),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                val statusText = if (uiState.isContinuousScanning) {
                    stringResource(
                        R.string.scanning_reference_points,
                        uiState.environmentRecordCount
                    )
                } else {
                    stringResource(R.string.view_heatmap_start_scan)
                }
                Text(statusText, color = AppColors.textSecondary(isDark), fontSize = 12.sp)
            }
            if (uiState.isContinuousScanning) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentGreen)
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                tint = AppColors.textSecondary(isDark).copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ManageDataCard(isDark: Boolean, onClick: () -> Unit) {
    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable { onClick() },
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentOrange.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.FolderShared,
                    null,
                    tint = AccentOrange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.title_manage_data),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.manage_collected_data_desc),
                    color = AppColors.textSecondary(isDark),
                    fontSize = 12.sp
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                null,
                tint = AppColors.textSecondary(isDark).copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun ImportExportDataCard(isDark: Boolean, onImportClick: () -> Unit, onExportClick: () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(AccentBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ImportExport,
                    null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.env_data_sharing),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.env_data_sharing_desc),
                    color = AppColors.textSecondary(isDark),
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onImportClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.import_data), color = AccentBlue, fontSize = 13.sp)
                }
                TextButton(
                    onClick = onExportClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(stringResource(R.string.export_data), color = AccentBlue, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun FooterLinks(isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

        // GitHub 胶囊
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(if (isDark) Color(0xFF24292E) else Color(0xFF24292E))
                .noRippleClickable { uriHandler.openUri("https://github.com/HuangZhuoRui/LocationSpoofer") }
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_github),
                    contentDescription = stringResource(R.string.brand_github),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.brand_github),
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // Telegram 胶囊
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(if (isDark) Color(0xFF24A1DE).copy(alpha = 0.22f) else Color(0xFFE8F4FA))
                .noRippleClickable { uriHandler.openUri("https://t.me/+CsxZGItXdW40ZWVl") }
                .padding(horizontal = 16.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_telegram),
                    contentDescription = stringResource(R.string.brand_telegram),
                    tint = Color.Unspecified,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.brand_telegram),
                    color = Color(0xFF24A1DE),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
