package com.suseoaa.locationspoofer.ui.screen.managedata

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.db.CompleteLocation
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.AccentOrange
import com.suseoaa.locationspoofer.ui.theme.noRippleClickable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/**
 * 具有细腻物理阻尼手感与弹簧回弹的向左滑动列表项
 */
@Composable
fun SwipeableDataListItem(
    item: CompleteLocation,
    isDark: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // 显露操作区域总宽度（收藏 60dp + 编辑 60dp + 删除 60dp + 间距）
    val maxRevealWidthDp = 204.dp
    val maxRevealWidthPx = with(density) { maxRevealWidthDp.toPx() }

    val offsetX = remember { Animatable(0f) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val timeStr =
        remember(item.location.timestamp) { dateFormat.format(Date(item.location.timestamp)) }

    val wifiCount = (if (item.connectedWifi != null) 1 else 0) + item.wifis.size
    val cellCount = item.cells.size
    val btCount = item.bluetooths.size

    val hasPlaceName = item.location.placeName.isNotBlank()
    val hasRemark = item.location.remark.isNotBlank()

    val defaultRecordTitle = stringResource(R.string.coord_record_title)
    // 优先显示地名或备注为卡片的主标题
    val primaryTitle = when {
        hasPlaceName -> item.location.placeName
        hasRemark -> item.location.remark
        else -> defaultRecordTitle
    }

    val subtitle = when {
        hasPlaceName && hasRemark -> item.location.remark
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // 底层操作按键区（向左滑动时显露）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 收藏按钮
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentOrange)
                    .noRippleClickable {
                        coroutineScope.launch {
                            offsetX.animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = 500f
                                )
                            )
                        }
                        onFavorite()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = stringResource(R.string.add_to_favorites),
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                    Text(
                        text = stringResource(R.string.add_to_favorites),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // 编辑按钮
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentBlue)
                    .noRippleClickable {
                        coroutineScope.launch {
                            offsetX.animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = 500f
                                )
                            )
                        }
                        onEdit()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                    Text(
                        text = stringResource(R.string.edit),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // 删除按钮
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFE53935))
                    .noRippleClickable {
                        coroutineScope.launch {
                            offsetX.animateTo(
                                0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = 500f
                                )
                            )
                        }
                        onDelete()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.delete),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // 上层滑动卡片（带拖拽手势与非线性阻尼）
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
        ) {
            MiuixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable {
                        if (offsetX.value < -10f) {
                            coroutineScope.launch {
                                offsetX.animateTo(
                                    0f,
                                    spring(dampingRatio = 0.8f, stiffness = 450f)
                                )
                            }
                        } else {
                            onClick()
                        }
                    },
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 主标题与时间
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = primaryTitle,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            Text(
                                text = timeStr,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                        }

                        // 备注副标题
                        if (subtitle != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Description,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = subtitle,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }

                        // 经纬度坐标标签
                        Text(
                            text = "${
                                String.format(
                                    Locale.US,
                                    "%.5f",
                                    item.location.lat
                                )
                            }, ${String.format(Locale.US, "%.5f", item.location.lng)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )

                        // 信号设备标签组（Wi-Fi、基站、蓝牙）
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SignalChip(
                                icon = Icons.Rounded.Wifi,
                                text = "$wifiCount",
                                tint = AccentBlue,
                                isDark = isDark
                            )
                            SignalChip(
                                icon = Icons.Rounded.CellTower,
                                text = "$cellCount",
                                tint = AccentOrange,
                                isDark = isDark
                            )
                            SignalChip(
                                icon = Icons.Rounded.Bluetooth,
                                text = "$btCount",
                                tint = AccentGreen,
                                isDark = isDark
                            )
                        }
                    }

                    // 右侧指示可左滑的尖头小图标（随着滑动展开自然淡出）
                    val chevronAlpha =
                        ((1f - abs(offsetX.value) / maxRevealWidthPx) * 0.4f).coerceIn(0f, 0.4f)
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = stringResource(R.string.slide_left_hint),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = chevronAlpha),
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(18.dp)
                    )
                }
            }

            // 右侧 1/3 区域专用滑动手势触发层（左侧区域保留正常点击与纵向顺畅滚动）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.35f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                coroutineScope.launch {
                                    val target = if (offsetX.value < -maxRevealWidthPx * 0.45f) {
                                        -maxRevealWidthPx
                                    } else {
                                        0f
                                    }
                                    offsetX.animateTo(
                                        targetValue = target,
                                        animationSpec = spring(
                                            dampingRatio = 0.8f,
                                            stiffness = 450f
                                        )
                                    )
                                }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                coroutineScope.launch {
                                    val current = offsetX.value
                                    val newOffset = if (dragAmount < 0) {
                                        // 向左滑动
                                        if (current < -maxRevealWidthPx) {
                                            // 超过最大显露宽度时应用弹性阻尼
                                            val overDrag = abs(current) - maxRevealWidthPx
                                            val dampingFactor = 1f / (1f + overDrag / 60f)
                                            current + dragAmount * dampingFactor
                                        } else {
                                            current + dragAmount
                                        }
                                    } else {
                                        // 向右滑动
                                        if (current > 0) {
                                            // 右侧超出边界重阻尼
                                            current + dragAmount * 0.15f
                                        } else {
                                            current + dragAmount
                                        }
                                    }
                                    offsetX.snapTo(newOffset.coerceAtLeast(-maxRevealWidthPx * 1.35f))
                                }
                            }
                        )
                    }
            )
        }
    }
}

@Composable
fun SignalChip(
    icon: ImageVector,
    text: String,
    tint: Color,
    isDark: Boolean
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = if (isDark) 0.12f else 0.08f))
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = tint
            )
            Spacer(modifier = Modifier.width(3.5.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
        }
    }
}
