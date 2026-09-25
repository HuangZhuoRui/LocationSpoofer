package com.vincenthzr.locationspoofer.ui.screen.tabs.location

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.data.model.SavedLocation
import com.vincenthzr.locationspoofer.ui.screen.ActionButtons
import com.vincenthzr.locationspoofer.ui.screen.CoordinateInputCard
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import kotlin.math.roundToInt

@Composable
fun LocationControlPanel(
    modifier: Modifier,
    uiState: AppState,
    isDark: Boolean,
    panelState: LocationPanelState,
    onPanelStateChange: (LocationPanelState) -> Unit,
    viewModel: MainViewModel,
    tabBarHeight: Dp,
    savedCardHeightDp: Dp,
    coordCardHeightDp: Dp,
    onSavedCardHeightMeasured: (Int) -> Unit,
    onCoordCardHeightMeasured: (Int) -> Unit,
    onSaveClick: () -> Unit,
    onCustomClick: () -> Unit,
    onStartFixedSpoofing: () -> Unit,
    onStopSpoofing: () -> Unit,
    onSelectSavedLocation: (SavedLocation) -> Unit,
    onDeleteSavedLocation: (SavedLocation) -> Unit,
    onOpenManageSavedLocations: () -> Unit,
    searchBar: @Composable (Modifier) -> Unit
) {
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val dragGestureModifier = Modifier.pointerInput(panelState) {
        detectVerticalDragGestures(
            onDragStart = { dragAccumulator = 0f },
            onVerticalDrag = { _, dragAmount ->
                dragAccumulator += dragAmount
            },
            onDragEnd = {
                if (dragAccumulator > 28f) {
                    when (panelState) {
                        LocationPanelState.EXPANDED -> onPanelStateChange(LocationPanelState.DEFAULT)
                        LocationPanelState.DEFAULT -> onPanelStateChange(LocationPanelState.COLLAPSED)
                        LocationPanelState.COLLAPSED -> {}
                    }
                } else if (dragAccumulator < -28f) {
                    when (panelState) {
                        LocationPanelState.COLLAPSED -> onPanelStateChange(LocationPanelState.DEFAULT)
                        LocationPanelState.DEFAULT -> onPanelStateChange(LocationPanelState.EXPANDED)
                        LocationPanelState.EXPANDED -> {}
                    }
                }
                dragAccumulator = 0f
            },
            onDragCancel = { dragAccumulator = 0f }
        )
    }

    // 抽屉单通道物理 Spring 滑动位移：
    // EXPANDED（完全展开）：偏移为 0dp，整张收藏卡片向上抽拉拉出至屏幕中央，完整展现
    // DEFAULT（默认下沉状态）：向下偏移 savedCardHeightDp + 8dp，定点模拟按钮正好保持在 TabBar 上方稍微一点点（~8dp），收藏卡片全部自然下沉延伸至屏幕下方边缘外
    // COLLAPSED（折叠状态）：向下偏移 savedCardHeightDp + coordCardHeightDp + 16dp，坐标卡片也顺畅下沉隐藏，仅留单行搜索操作栏稳稳停留在 TabBar 上方
    val rawDrawerOffsetY by animateDpAsState(
        targetValue = when (panelState) {
            LocationPanelState.EXPANDED -> 0.dp
            LocationPanelState.DEFAULT -> savedCardHeightDp + 8.dp
            LocationPanelState.COLLAPSED -> savedCardHeightDp + coordCardHeightDp + 16.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "drawer_offset_y"
    )

    val drawerOffsetYPx = with(density) { rawDrawerOffsetY.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = (tabBarHeight + 10.dp).coerceAtLeast(0.dp))
            .offset { IntOffset(0, drawerOffsetYPx.roundToInt()) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部阻尼拖拽药丸指示条（支持点击快速折叠/展开与拖拽）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp)
                    .then(dragGestureModifier)
                    .noRippleClickable {
                        val next = when (panelState) {
                            LocationPanelState.COLLAPSED -> LocationPanelState.DEFAULT
                            LocationPanelState.DEFAULT -> LocationPanelState.EXPANDED
                            LocationPanelState.EXPANDED -> LocationPanelState.DEFAULT
                        }
                        onPanelStateChange(next)
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.20f)
                        )
                )
            }

            // 顶部常驻搜索栏行（无论是否折叠都平滑存在，右侧操作按钮采用柔和弥散阴影与平滑横向伸展）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .then(dragGestureModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                searchBar(
                    Modifier
                        .weight(1f)
                        .height(52.dp)
                )

                // 正在模拟时的停止模拟按钮
                AnimatedVisibility(
                    visible = uiState.isSpoofingActive,
                    enter = expandHorizontally(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                    exit = shrinkHorizontally(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(180))
                ) {
                    val errorColor = MaterialTheme.colorScheme.error
                    val errorContainer = MaterialTheme.colorScheme.errorContainer
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(52.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(26.dp),
                                ambientColor = Color.Black.copy(alpha = 0.08f),
                                spotColor = errorColor.copy(alpha = 0.25f)
                            )
                            .clip(RoundedCornerShape(26.dp))
                            .background(errorContainer.copy(alpha = 0.95f))
                            .noRippleClickable(onClick = onStopSpoofing),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Rounded.StopCircle,
                                contentDescription = null,
                                tint = errorColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.stop_simulation),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = errorColor
                            )
                        }
                    }
                }

                // 未模拟且处于 COLLAPSED 状态下的定点模拟胶囊按钮（间距内聚，收缩与渐隐严格同周期，绝无二次伸长）
                AnimatedVisibility(
                    visible = !uiState.isSpoofingActive && panelState == LocationPanelState.COLLAPSED,
                    enter = expandHorizontally(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                    exit = shrinkHorizontally(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(180))
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(52.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = RoundedCornerShape(26.dp),
                                ambientColor = Color.Black.copy(alpha = 0.08f),
                                spotColor = AccentBlue.copy(alpha = 0.32f)
                            )
                            .clip(RoundedCornerShape(26.dp))
                            .background(AccentBlue)
                            .noRippleClickable(onClick = onStartFixedSpoofing),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (uiState.isSavingConfig) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.starting_ellipsis),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.MyLocation,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.fixed_simulation),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // 目标坐标及操作卡片（包含定点模拟按钮，随抽屉 offset 顺畅沉降）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        if (it.size.height > 0) {
                            onCoordCardHeightMeasured(it.size.height)
                        }
                    }
                    .then(dragGestureModifier),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    CoordinateInputCard(
                        viewModel = viewModel,
                        uiState = uiState,
                        isDark = isDark,
                        onSaveClick = onSaveClick,
                        onCustomClick = onCustomClick
                    )

                    Spacer(Modifier.height(12.dp))

                    ActionButtons(
                        viewModel = viewModel,
                        uiState = uiState,
                        onOpenMap = {},
                        onStartFixedSpoofing = onStartFixedSpoofing
                    )
                }
            }

            // 收藏地点卡片：支持海量坐标平滑滚动，并支持抽拉下沉
            SavedLocationsCard(
                modifier = Modifier.onGloballyPositioned {
                    if (it.size.height > 0) {
                        onSavedCardHeightMeasured(it.size.height)
                    }
                },
                savedLocations = uiState.savedLocations,
                panelState = panelState,
                isDark = isDark,
                onSelect = onSelectSavedLocation,
                onDelete = onDeleteSavedLocation,
                onToggleExpand = {
                    val next = if (panelState == LocationPanelState.EXPANDED) {
                        LocationPanelState.DEFAULT
                    } else {
                        LocationPanelState.EXPANDED
                    }
                    onPanelStateChange(next)
                },
                onOpenManageDialog = onOpenManageSavedLocations,
                dragGestureModifier = dragGestureModifier
            )
        }
    }
}
