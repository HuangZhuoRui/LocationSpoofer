package com.vincenthzr.locationspoofer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppInfoItem
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.setAppCoordinateSystem
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

private enum class CoordinateFilterTab {
    All,
    Customized,
    UserApps,
    SystemApps;

    @Composable
    fun getLabel(customCount: Int): String {
        return when (this) {
            All -> stringResource(R.string.tab_all)
            Customized -> stringResource(R.string.customized_count_format, customCount)
            UserApps -> stringResource(R.string.tab_user_apps)
            SystemApps -> stringResource(R.string.tab_system_apps)
        }
    }
}

@Composable
fun AppCoordinateScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(CoordinateFilterTab.All) }
    var selectedApp by remember { mutableStateOf<AppInfoItem?>(null) }
    val isDark = isSystemInDarkTheme()

    BackHandler(onBack = onBack)

    // 统计已自定义坐标系的应用数量
    val customConfiguredCount = remember(uiState.hookedApps, uiState.appCoordinateSystems) {
        uiState.hookedApps.count { app ->
            uiState.appCoordinateSystems.containsKey(app.packageName)
        }
    }

    val appsToShow =
        remember(uiState.hookedApps, searchQuery, selectedFilter, uiState.appCoordinateSystems) {
            uiState.hookedApps.filter { app ->
                val matchesSearch = searchQuery.isBlank() ||
                        app.appName.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true)

                if (!matchesSearch) return@filter false

                val isCustom = uiState.appCoordinateSystems.containsKey(app.packageName)

                when (selectedFilter) {
                    CoordinateFilterTab.All -> true
                    CoordinateFilterTab.Customized -> isCustom
                    CoordinateFilterTab.UserApps -> !app.isSystem
                    CoordinateFilterTab.SystemApps -> app.isSystem
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
        ) {
            // 顶部导航栏（独立圆形返回按键 + 标题）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 独立立体圆形返回胶囊
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
                        .noRippleClickable(onClick = onBack),
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
                        text = stringResource(R.string.custom_coordinate_algo),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.app_coord_subtitle),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }

            // 搜索胶囊栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0xFF22272E) else Color.White)
                        .border(
                            width = 0.8.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(
                                alpha = 0.05f
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = TextStyle(
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search_app_hint),
                                    fontSize = 13.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                                .noRippleClickable { searchQuery = "" },
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

            // 分类快捷过滤标签
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CoordinateFilterTab.values()) { tab ->
                    val isSelected = selectedFilter == tab
                    val label = tab.getLabel(customConfiguredCount)

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) AccentBlue
                                else if (isDark) Color.White.copy(alpha = 0.06f)
                                else Color.Black.copy(alpha = 0.04f)
                            )
                            .noRippleClickable { selectedFilter = tab }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface.copy(
                                alpha = 0.7f
                            )
                        )
                    }
                }
            }

            // 应用列表与顶部平滑溶解边界
            if (appsToShow.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(
                                        alpha = 0.03f
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.SearchOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.no_hooked_apps),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.search_no_match_hint),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                        items(appsToShow, key = { it.packageName }) { app ->
                            val currentSys = uiState.appCoordinateSystems[app.packageName]
                            AppItemCard(
                                appInfo = app,
                                currentCoordinateSystem = currentSys,
                                isDark = isDark,
                                onClick = { selectedApp = app }
                            )
                        }
                    }

                    // 顶部边界模糊渐变遮罩（上滑时消除生硬切边，平滑溶解过渡）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
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

    // 现代化坐标系选择弹窗
    selectedApp?.let { app ->
        CoordinateSelectionDialog(
            appInfo = app,
            currentSystem = uiState.appCoordinateSystems[app.packageName] ?: "AUTO",
            isDark = isDark,
            onDismiss = { selectedApp = null },
            onSelect = { sys ->
                viewModel.setAppCoordinateSystem(app.packageName, sys)
                selectedApp = null
            }
        )
    }
}

@Composable
private fun AppItemCard(
    appInfo: AppInfoItem,
    currentCoordinateSystem: String?,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val isCustom = currentCoordinateSystem != null
    val displaySystem = currentCoordinateSystem ?: "AUTO"

    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick = onClick),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(
                            alpha = 0.03f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                AppIconImage(
                    packageName = appInfo.packageName,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // 应用名称与包名
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = appInfo.appName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (appInfo.isSystem) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(
                                        alpha = 0.05f
                                    )
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.system_tag),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = appInfo.packageName,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 坐标系徽章与向右小箭头
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isCustom) AccentBlue.copy(alpha = 0.12f)
                            else if (isDark) Color.White.copy(alpha = 0.06f)
                            else Color.Black.copy(alpha = 0.04f)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = displaySystem,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCustom) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                            alpha = 0.6f
                        )
                    )
                }

                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun AppIconImage(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { imageView ->
            try {
                val icon = context.packageManager.getApplicationIcon(packageName)
                imageView.setImageDrawable(icon)
            } catch (e: Exception) {
                imageView.setImageDrawable(null)
            }
        },
        modifier = modifier
    )
}
