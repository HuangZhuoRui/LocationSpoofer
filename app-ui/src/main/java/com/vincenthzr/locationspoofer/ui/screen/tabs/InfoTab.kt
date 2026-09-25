package com.vincenthzr.locationspoofer.ui.screen.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.vincenthzr.locationspoofer.ui.BuildConfig
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.data.model.MapEngine
import com.vincenthzr.locationspoofer.ui.screen.FooterLinks
import com.vincenthzr.locationspoofer.ui.screen.LANGUAGES
import com.vincenthzr.locationspoofer.ui.screen.UpdateCheckCard
import com.vincenthzr.locationspoofer.ui.screen.isNewerVersion
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.UpdateUiState
import com.vincenthzr.locationspoofer.viewmodel.getSavedLanguage
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun InfoTab(
    viewModel: MainViewModel,
    uiState: AppState,
    updateUiState: UpdateUiState? = null,
    tabBarHeight: Dp = 90.dp,
    onNavigateToUpdate: () -> Unit = {},
    onNavigateToLanguage: () -> Unit = {},
    onNavigateToMapEngine: () -> Unit = {},
    onNavigateToSignatureAuth: () -> Unit = {},
    onNavigateToRootDiagnostics: () -> Unit = {},
    onNavigateToVendorScheme: () -> Unit = {},
    onNavigateToMotionRealism: () -> Unit = {},
    onNavigateToBackgroundKeepAlive: () -> Unit = {},
    onNavigateToEnvTokens: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()

    val currentVersion = BuildConfig.VERSION_NAME
    val targetReleases = remember(updateUiState?.releases, uiState.checkBetaUpdates) {
        val list = updateUiState?.releases ?: emptyList()
        if (uiState.checkBetaUpdates) list else list.filter { !it.isPrerelease }
    }
    val latestRelease = targetReleases.firstOrNull()
    val hasNewVersion = remember(targetReleases) {
        latestRelease != null && isNewerVersion(latestRelease.versionName, currentVersion)
    }

    // 获取当前生效的语言名称
    val defaultLangText = stringResource(R.string.default_language)
    val savedLangCode = viewModel.getSavedLanguage()
    val currentLangName = remember(savedLangCode, defaultLangText) {
        LANGUAGES.firstOrNull { it.code == savedLangCode }?.nativeName ?: defaultLangText
    }

    // 获取当前厂商适配方案名称
    val vendorAutoText = stringResource(R.string.vendor_scheme_auto)
    val vendorHyperOsText = stringResource(R.string.vendor_scheme_hyperos)
    val vendorColorOsText = stringResource(R.string.vendor_scheme_coloros)
    val vendorOneUiText = stringResource(R.string.vendor_scheme_oneui)
    val vendorAospText = stringResource(R.string.vendor_scheme_aosp)
    val currentVendorSchemeName = remember(
        uiState.vendorScheme, vendorAutoText, vendorHyperOsText, vendorColorOsText, vendorOneUiText, vendorAospText
    ) {
        when (uiState.vendorScheme) {
            com.vincenthzr.locationspoofer.data.model.VendorScheme.AUTO -> vendorAutoText
            com.vincenthzr.locationspoofer.data.model.VendorScheme.HYPEROS -> vendorHyperOsText
            com.vincenthzr.locationspoofer.data.model.VendorScheme.COLOROS -> vendorColorOsText
            com.vincenthzr.locationspoofer.data.model.VendorScheme.ONEUI -> vendorOneUiText
            com.vincenthzr.locationspoofer.data.model.VendorScheme.AOSP -> vendorAospText
        }
    }

    val realismLevelNames = listOf(
        stringResource(R.string.realism_level_off),
        stringResource(R.string.realism_level_low),
        stringResource(R.string.realism_level_medium),
        stringResource(R.string.realism_level_high)
    )
    val currentRealismLevelName = realismLevelNames.getOrNull(uiState.realismLevel) ?: realismLevelNames[2]

    // 获取当前地图引擎名称
    val autoText = stringResource(R.string.map_engine_auto)
    val amapText = stringResource(R.string.map_engine_amap)
    val baiduText = stringResource(R.string.map_engine_baidu)
    val googleText = stringResource(R.string.map_engine_google)
    val currentEngineName = remember(uiState.mapEngine, autoText, amapText, baiduText, googleText) {
        when (uiState.mapEngine) {
            MapEngine.AUTO -> autoText
            MapEngine.AMAP -> amapText
            MapEngine.BAIDU -> baiduText
            MapEngine.GOOGLE -> googleText
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = tabBarHeight + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 顶部软件品牌与简介 Hero 区域（替换原先单调的信息图标与标题）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 18.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 软件图标（原生平滑圆角与柔和阴影，无外层黑边缝隙）
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(18.dp),
                                clip = false
                            )
                            .clip(RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.ImageView(ctx).apply {
                                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    try {
                                        val icon =
                                            ctx.packageManager.getApplicationIcon(ctx.packageName)
                                        setImageDrawable(icon)
                                    } catch (e: Exception) {
                                        // fallback
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // 软件名称
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(Modifier.height(6.dp))

                    // 软件简介介绍
                    Text(
                        text = stringResource(R.string.app_slogan),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            // 1. 软件更新卡片
            item {
                UpdateCheckCard(
                    isDark = isDark,
                    hasNewVersion = hasNewVersion,
                    newVersionName = if (hasNewVersion) latestRelease?.versionName else null,
                    onCheckClick = onNavigateToUpdate
                )
            }

            // 2. 设置聚合卡片：原来的 6 个"软件配置"区块拆开后，各自独立成页，
            // 这里收进一个紧凑列表，避免变成 6 个各自占一屏高度的大卡片。
            item {
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column {
                        SettingsEntryRow(
                            icon = Icons.Rounded.Language,
                            tint = AccentBlue,
                            title = stringResource(R.string.select_language),
                            previewChip = currentLangName,
                            onClick = onNavigateToLanguage
                        )
                        SettingsEntryDivider()
                        SettingsEntryRow(
                            icon = Icons.Rounded.Map,
                            tint = AccentGreen,
                            title = stringResource(R.string.map_config),
                            previewChip = currentEngineName,
                            onClick = onNavigateToMapEngine
                        )
                        SettingsEntryDivider()
                        SettingsEntryRow(
                            icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                            tint = AccentOrange,
                            title = stringResource(R.string.motion_realism_title),
                            previewChip = currentRealismLevelName,
                            onClick = onNavigateToMotionRealism
                        )
                        SettingsEntryDivider()
                        SettingsEntryRow(
                            icon = Icons.Rounded.Security,
                            tint = AccentOrange,
                            title = stringResource(R.string.env_and_signature),
                            onClick = onNavigateToSignatureAuth
                        )
                        SettingsEntryDivider()
                        SettingsEntryRow(
                            icon = Icons.Rounded.Shield,
                            tint = AccentBlue,
                            title = stringResource(R.string.root_solution_title),
                            onClick = onNavigateToRootDiagnostics
                        )
                        SettingsEntryDivider()
                        // 厂商适配只作用于系统级 Hook，仅全局方案显示
                        if (BuildConfig.GLOBAL_SCHEME) {
                            SettingsEntryRow(
                                icon = Icons.Rounded.Devices,
                                tint = AccentGreen,
                                title = stringResource(R.string.vendor_scheme_title),
                                previewChip = currentVendorSchemeName,
                                onClick = onNavigateToVendorScheme
                            )
                            SettingsEntryDivider()
                        }
                        SettingsEntryRow(
                            icon = Icons.Rounded.BatteryChargingFull,
                            tint = AccentOrange,
                            title = stringResource(R.string.background_keep_alive_title),
                            onClick = onNavigateToBackgroundKeepAlive
                        )
                        SettingsEntryDivider()
                        SettingsEntryRow(
                            icon = Icons.Rounded.VpnKey,
                            tint = AccentBlue,
                            title = stringResource(R.string.env_datasource_tokens_title),
                            onClick = onNavigateToEnvTokens
                        )
                    }
                }
            }

            // 3. 底部开源协议与项目链接
            item {
                Spacer(Modifier.height(6.dp))
                FooterLinks(isDark = isDark)
            }
        }
    }
}

/** 设置聚合卡片里的单行入口：图标 + 标题 + 可选状态预览 chip + 箭头。 */
@Composable
private fun SettingsEntryRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    onClick: () -> Unit,
    previewChip: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (previewChip != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = 0.1f))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Text(text = previewChip, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsEntryDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    )
}
