package com.vincenthzr.locationspoofer.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.progress.AdaptationProgress
import com.vincenthzr.locationspoofer.progress.AdaptationProgressSource
import com.vincenthzr.locationspoofer.ui.BuildConfig
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.screen.RenderMarkdownContent
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.vendor.VendorProfile
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/** 系统适配页的二级页面：完整的适配进度文档 */
@Composable
fun AdaptationProgressScreen(
    document: AdaptationProgressSource.Document,
    loading: Boolean,
    onRefresh: () -> Unit,
    isDark: Boolean = isSystemInDarkTheme(),
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

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
            SettingsPageHeader(
                title = stringResource(R.string.adaptation_progress_title),
                subtitle = stringResource(
                    when {
                        loading -> R.string.progress_source_loading
                        document.origin == AdaptationProgressSource.Origin.REMOTE -> R.string.progress_source_remote
                        document.origin == AdaptationProgressSource.Origin.CACHE -> R.string.progress_source_cache
                        else -> R.string.progress_source_bundled
                    }
                ),
                isDark = isDark,
                onBack = onClose
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentBlue)
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.progress_refresh),
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                MiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    insideMargin = PaddingValues(16.dp)
                ) {
                    // 页面标题已经是"适配进度"，去掉文档自己的一级标题
                    RenderMarkdownContent(
                        markdown = document.markdown.lineSequence()
                            .dropWhile { it.isBlank() || it.startsWith("# ") }
                            .joinToString("\n")
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 本机一句话状态：系统 + 方案 + 适配状态 */
@Composable
fun DeviceAdaptationCard(markdown: String) {
    val profile = VendorProfile.current
    val entry = remember(markdown) { AdaptationProgress.findGlobalSystem(markdown, profile.family.progressKeywords) }

    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile.systemLabel,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            SchemeBadge()
        }
        Spacer(Modifier.height(10.dp))
        if (BuildConfig.GLOBAL_SCHEME) {
            StatusLine(entry?.status ?: AdaptationProgress.Status.UNVERIFIED, entry?.verifiedVersions.orEmpty())
        } else {
            Text(
                stringResource(R.string.progress_scoped_simple),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}

@Composable
private fun StatusLine(status: AdaptationProgress.Status, verifiedVersions: String) {
    val (color, label) = when (status) {
        AdaptationProgress.Status.VERIFIED -> AccentGreen to R.string.progress_status_verified
        AdaptationProgress.Status.PARTIAL -> AccentOrange to R.string.progress_status_partial
        AdaptationProgress.Status.BROKEN -> Color(0xFFE5534B) to R.string.progress_status_broken
        AdaptationProgress.Status.UNVERIFIED -> Color(0xFF8B949E) to R.string.progress_status_unverified
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = color)
        if (verifiedVersions.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.progress_verified_versions, verifiedVersions),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

/** 当前安装的是哪个模拟方案，更新页、启动更新弹窗、系统适配页共用 */
@Composable
fun SchemeBadge(modifier: Modifier = Modifier) {
    val color = if (BuildConfig.GLOBAL_SCHEME) AccentOrange else AccentBlue
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(if (BuildConfig.GLOBAL_SCHEME) R.string.scheme_global else R.string.scheme_scoped),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
