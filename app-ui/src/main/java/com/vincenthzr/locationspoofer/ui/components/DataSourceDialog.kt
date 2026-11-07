package com.vincenthzr.locationspoofer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.SearchMode
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue

@Composable
fun DataSourceDialog(
    currentSearchMode: SearchMode,
    onSearchModeSelected: (SearchMode) -> Unit,
    onImportLocalData: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.select_data_source),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.select_data_source_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                DataSourceItem(
                    title = stringResource(R.string.network_online_search),
                    subtitle = stringResource(R.string.network_online_search_desc),
                    icon = Icons.Rounded.Public,
                    isSelected = currentSearchMode == SearchMode.NETWORK,
                    onClick = {
                        onSearchModeSelected(SearchMode.NETWORK)
                        onDismiss()
                    }
                )

                DataSourceItem(
                    title = stringResource(R.string.local_collected_datasource),
                    subtitle = stringResource(R.string.local_collected_datasource_desc),
                    icon = Icons.Rounded.Storage,
                    isSelected = currentSearchMode == SearchMode.LOCAL,
                    onClick = {
                        onSearchModeSelected(SearchMode.LOCAL)
                        onDismiss()
                    }
                )

                if (onImportLocalData != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )

                    Surface(
                        onClick = {
                            onDismiss()
                            onImportLocalData()
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.import_external_datasource),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataSourceItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) AccentBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(
            alpha = 0.35f
        ),
        border = if (isSelected) BorderStroke(1.5.dp, AccentBlue) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) AccentBlue.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.7f
                    ),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    lineHeight = 15.sp
                )
            }
            if (isSelected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
