package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.clearPinnedCollectedLocation
import top.yukonga.miuix.kmp.basic.Card as MiuixCard


// 坐标输入卡片 (Miuix 风格)
@Composable
fun CoordinateInputCard(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean,
    onSaveClick: () -> Unit,
    onCustomClick: () -> Unit
) {
    val textSecondary = AppColors.textSecondary(isDark)

    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(
                    Icons.Outlined.PinDrop,
                    stringResource(R.string.target_coordinates),
                    isDark
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onCustomClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        null,
                        modifier = Modifier.size(15.dp),
                        tint = AccentBlue
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.custom), fontSize = 13.sp, color = AccentBlue)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onSaveClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Rounded.StarBorder,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = AccentBlue
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.save), fontSize = 13.sp, color = AccentBlue)
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Longitude Chip
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.longitude),
                            fontSize = 11.sp,
                            color = textSecondary
                        )
                        Text(
                            text = uiState.longitudeInput.ifEmpty { "0.0" },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }

                // Latitude Chip
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.latitude),
                            fontSize = 11.sp,
                            color = textSecondary
                        )
                        Text(
                            text = uiState.latitudeInput.ifEmpty { "0.0" },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            if (uiState.pinnedCollectedLocationId != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentBlue.copy(alpha = 0.10f))
                        .border(0.8.dp, AccentBlue.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.PinDrop,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                R.string.pinned_collected_location_badge,
                                uiState.pinnedLocationName ?: ""
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentBlue,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(AccentBlue.copy(alpha = 0.15f))
                                .noRippleClickable { viewModel.clearPinnedCollectedLocation() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.unpin_collected_location),
                                tint = AccentBlue,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }

            if (uiState.showCoordinateError) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.invalid_coordinates),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun coordinateFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
    disabledTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
    cursorColor = AccentBlue
)
