package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.data.model.SavedLocation
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue

@Composable
fun SavedLocationsDialog(
    savedLocations: List<SavedLocation>,
    onDismiss: () -> Unit,
    onSelect: (SavedLocation) -> Unit,
    onDelete: (SavedLocation) -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentBlue.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Star,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.saved_locations),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (savedLocations.isEmpty()) stringResource(R.string.no_saved_locations) else stringResource(R.string.saved_locations_count_format, savedLocations.size),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                if (savedLocations.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.StarOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.no_saved_locations),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.map_save_location_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(savedLocations) { loc ->
                            Surface(
                                onClick = { onSelect(loc) },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(AccentBlue.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Place,
                                            contentDescription = null,
                                            tint = AccentBlue,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        val coordText = "${
                                            String.format(java.util.Locale.US, "%.6f", loc.lat)
                                        }, ${
                                            String.format(java.util.Locale.US, "%.6f", loc.lng)
                                        }"
                                        // 没有备注/地名的收藏点，名字本身就是坐标，
                                        // 再渲染一行坐标就是上下两行雷同（issue #56）。
                                        // 去掉括号与空格后归一化比较，一致就只显示一行。
                                        val normalize = { text: String ->
                                            text.replace("(", "").replace(")", "")
                                                .replace("（", "").replace("）", "")
                                                .replace(" ", "")
                                        }
                                        val nameIsCoordinate =
                                            normalize(loc.name) == normalize(coordText) ||
                                                    normalize(loc.name) == normalize(
                                                "${String.format(java.util.Locale.US, "%.5f", loc.lat)}, ${
                                                    String.format(java.util.Locale.US, "%.5f", loc.lng)
                                                }"
                                            )

                                        Text(
                                            if (nameIsCoordinate) coordText else loc.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (!nameIsCoordinate) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = coordText,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { onDelete(loc) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            stringResource(R.string.delete),
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        stringResource(R.string.close),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
