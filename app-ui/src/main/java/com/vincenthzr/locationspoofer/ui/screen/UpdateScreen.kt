package com.vincenthzr.locationspoofer.ui.screen

import android.app.DownloadManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import com.vincenthzr.locationspoofer.ui.BuildConfig
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.GithubRelease
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.UpdateUiState
import com.vincenthzr.locationspoofer.viewmodel.UpdateViewModel
import com.vincenthzr.locationspoofer.viewmodel.setCheckBetaUpdates
import com.vincenthzr.locationspoofer.viewmodel.setIgnoredVersion
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@Composable
fun UpdateScreen(
    updateViewModel: UpdateViewModel,
    viewModel: MainViewModel,
    isDark: Boolean = isSystemInDarkTheme(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by updateViewModel.uiState.collectAsState()
    val appState by viewModel.uiState.collectAsState()
    val currentVersion = BuildConfig.VERSION_NAME
    val listState = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()

    BackHandler(onBack = onBack)

    // 进入页面时自动检查一次最新版本
    LaunchedEffect(Unit) {
        if (uiState.releases.isEmpty()) {
            updateViewModel.fetchReleases()
        }
    }

    // 根据是否接收 Beta 通道过滤候选版本
    val filteredReleases = remember(uiState.releases, appState.checkBetaUpdates) {
        if (appState.checkBetaUpdates) {
            uiState.releases
        } else {
            uiState.releases.filter { !it.isPrerelease }
        }
    }

    // 查找比当前版本更新的未升级版本
    val missed = remember(filteredReleases, currentVersion) {
        filteredReleases.filter { isNewerVersion(it.versionName, currentVersion) }
    }

    val hasNewVersion = missed.isNotEmpty()
    val latestRelease = filteredReleases.firstOrNull()

    val displayList = remember(filteredReleases, missed) {
        if (missed.size > 1) {
            val latest = missed.first()
            val grouped = parseAndCategorizeReleaseNotes(missed)
            val mergedBody = generateMergedMarkdown(context, grouped)
            val mergedRelease = latest.copy(body = mergedBody)
            val historical = filteredReleases.filter { it !in missed }
            listOf(mergedRelease) + historical
        } else {
            filteredReleases
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        // 主内容列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 112.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 大标题区域（距离顶部充裕舒展）
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.software_update),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.software_update_desc),
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    )
                }
            }

            // 当前版本卡片（无大图标，Miuix 质感卡片，仅最新版支持忽略）
            item {
                CurrentVersionHeroCard(
                    uiState = uiState,
                    hasNewVersion = hasNewVersion,
                    currentVersion = currentVersion,
                    latestRelease = latestRelease,
                    isDark = isDark,
                    onCheckAgain = { updateViewModel.fetchReleases() },
                    onDownload = { url, version -> updateViewModel.startDownload(url, version) },
                    onInstall = { updateViewModel.installApk() },
                    onCancelDownload = { updateViewModel.cancelDownload() },
                    onIgnore = { version ->
                        viewModel.setIgnoredVersion(version)
                        onBack()
                    }
                )
            }

            // Beta 测试版通道开关
            item {
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.check_beta_channel_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = stringResource(R.string.check_beta_channel_desc),
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        top.yukonga.miuix.kmp.basic.Switch(
                            checked = appState.checkBetaUpdates,
                            onCheckedChange = { viewModel.setCheckBetaUpdates(it) }
                        )
                    }
                }
            }

            // 更新日志及历史版本列表
            if (displayList.isNotEmpty()) {
                item {
                    Text(
                        text = if (hasNewVersion) stringResource(R.string.changelog) else stringResource(R.string.version_history),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                items(displayList) { release ->
                    val cleanRelease = release.versionName.trim().lowercase().removePrefix("v")
                    val cleanCurrent = currentVersion.trim().lowercase().removePrefix("v")
                    val isCurrent = cleanRelease == cleanCurrent
                    val isMerged = missed.size > 1 && release == displayList.first()

                    ReleaseItemCard(
                        release = release,
                        isCurrentVersion = isCurrent,
                        isMergedRelease = isMerged,
                        mergedCount = missed.size,
                        uiState = uiState,
                        onDownload = { url, version -> updateViewModel.startDownload(url, version) }
                    )
                }
            }
        }

        // 顶部边界模糊渐变遮罩（消除生硬边缘，平滑模糊溶解）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AppColors.background(isDark),
                            AppColors.background(isDark).copy(alpha = 0.92f),
                            AppColors.background(isDark).copy(alpha = 0.45f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 顶部悬浮栏（独立圆形返回按钮，无顶栏文字干扰）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 独立的圆形立体纯白返回按钮
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
                    .noRippleClickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = if (isDark) Color.White else Color(0xFF1A1D20),
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun CurrentVersionHeroCard(
    uiState: UpdateUiState,
    hasNewVersion: Boolean,
    currentVersion: String,
    latestRelease: GithubRelease?,
    isDark: Boolean,
    onCheckAgain: () -> Unit,
    onDownload: (String, String) -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onIgnore: (String) -> Unit
) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 第一行：应用名与当前安装版本号徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Location Spoofer",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                val cleanCurrentVersion = currentVersion.trim().removePrefix("v").removePrefix("V")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 当前安装的是哪个模拟方案变体，更新时只会拉取同一变体的安装包
                    com.vincenthzr.locationspoofer.ui.screen.settings.SchemeBadge()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "v$cleanCurrentVersion",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

            // 状态标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue
                    )
                    Text(
                        text = stringResource(R.string.checking_latest_release),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                } else if (uiState.error != null) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Text(
                        text = stringResource(R.string.check_failed_network),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (hasNewVersion && latestRelease != null) {
                    val cleanLatestVersion =
                        latestRelease.versionName.trim().removePrefix("v").removePrefix("V")
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE53935))
                    )
                    Text(
                        text = stringResource(R.string.new_version_found_format, cleanLatestVersion),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE53935)
                    )
                    if (latestRelease.isPrerelease) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFFF9800).copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.prerelease_badge),
                                fontSize = 10.5.sp,
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(AccentGreen)
                    )
                    Text(
                        text = stringResource(R.string.already_latest_version),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AccentGreen
                    )
                }
            }

            // 下载进度或操作按钮（非全宽，精致尺寸，最新版本独有忽略此版本选项）
            val isDownloading = uiState.activeDownloadId != null
            if (isDownloading) {
                if (uiState.downloadStatus == DownloadManager.STATUS_SUCCESSFUL) {
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(stringResource(R.string.install_new_version), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.downloading_progress, uiState.downloadProgress),
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            )
                            Text(
                                text = stringResource(R.string.cancel),
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.noRippleClickable(onClick = onCancelDownload)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { uiState.downloadProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentBlue,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            } else if (hasNewVersion && latestRelease != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val latestDownloadUrl = latestRelease.downloadUrl
                        val latestDownloadUrl32Bit = latestRelease.downloadUrl32Bit
                        if (latestDownloadUrl != null) {
                            Button(
                                onClick = {
                                    onDownload(latestDownloadUrl, latestRelease.versionName)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Text(
                                    text = if (latestDownloadUrl32Bit != null) stringResource(R.string.update_now_64) else stringResource(R.string.update_now),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (latestDownloadUrl32Bit != null) {
                            OutlinedButton(
                                onClick = {
                                    onDownload(
                                        latestDownloadUrl32Bit,
                                        "${latestRelease.versionName}_32bit"
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Text(stringResource(R.string.download_32_compat), fontSize = 13.sp, color = AccentBlue)
                            }
                        }
                    }

                    // 仅最新版显示“忽略此版本”
                    Text(
                        text = stringResource(R.string.ignore_this_version),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .noRippleClickable { onIgnore(latestRelease.versionName) }
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onCheckAgain,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(stringResource(R.string.recheck), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ReleaseItemCard(
    release: GithubRelease,
    isCurrentVersion: Boolean,
    isMergedRelease: Boolean,
    mergedCount: Int,
    uiState: UpdateUiState,
    onDownload: (String, String) -> Unit
) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val cleanReleaseVersion =
                        release.versionName.trim().removePrefix("v").removePrefix("V")
                    Text(
                        text = "v$cleanReleaseVersion",
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (isCurrentVersion) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.current_version),
                                fontSize = 10.5.sp,
                                color = AccentGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (isMergedRelease) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentBlue.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.merged_count_badge, mergedCount),
                                fontSize = 10.5.sp,
                                color = AccentBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (release.isPrerelease) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFF9800).copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.prerelease_badge),
                                fontSize = 10.5.sp,
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (release.publishedAt.isNotBlank()) {
                    Text(
                        text = release.publishedAt.take(10),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

            // 更新说明详情（结构化渲染）
            RenderMarkdownContent(markdown = release.body)

            // 操作按键（历史版本仅提供下载操作，不显示忽略）
            if (!isCurrentVersion && (release.downloadUrl != null || release.downloadUrl32Bit != null)) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val releaseDownloadUrl = release.downloadUrl
                        val releaseDownloadUrl32Bit = release.downloadUrl32Bit
                        if (releaseDownloadUrl != null) {
                            Button(
                                onClick = {
                                    onDownload(releaseDownloadUrl, release.versionName)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = if (releaseDownloadUrl32Bit != null) stringResource(R.string.download_64) else stringResource(R.string.download),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 仅当包含 32 位安装包时展示
                        if (releaseDownloadUrl32Bit != null) {
                            OutlinedButton(
                                onClick = {
                                    onDownload(
                                        releaseDownloadUrl32Bit,
                                        "${release.versionName}_32bit"
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(stringResource(R.string.download_32), fontSize = 12.sp, color = AccentBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}
