package com.suseoaa.locationspoofer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.model.ImportExportCounts
import com.suseoaa.locationspoofer.data.model.ImportExportSelection
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.noRippleClickable

/**
 * 导入 / 导出共用的分类选择对话框。
 * 导出时 counts 是"当前设备上有多少"，导入时是"文件里有多少"；数量为 0 的分类禁用。
 */
@Composable
fun ImportExportSelectionDialog(
    isExport: Boolean,
    counts: ImportExportCounts,
    onConfirm: (ImportExportSelection) -> Unit,
    onDismiss: () -> Unit
) {
    var selection by remember { mutableStateOf(ImportExportSelection()) }

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
                            Icons.Rounded.ImportExport,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(
                                if (isExport) R.string.export_select_title else R.string.import_select_title
                            ),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                if (isExport) R.string.export_select_desc else R.string.import_select_desc
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SelectionRow(
                        label = stringResource(R.string.category_collected_data),
                        count = counts.locations,
                        checked = selection.locations,
                        onCheckedChange = { selection = selection.copy(locations = it) }
                    )
                    SelectionRow(
                        label = stringResource(R.string.category_saved_locations),
                        count = counts.savedLocations,
                        checked = selection.savedLocations,
                        onCheckedChange = { selection = selection.copy(savedLocations = it) }
                    )
                    SelectionRow(
                        label = stringResource(R.string.category_saved_routes),
                        count = counts.savedRoutes,
                        checked = selection.savedRoutes,
                        onCheckedChange = { selection = selection.copy(savedRoutes = it) }
                    )
                    SelectionRow(
                        label = stringResource(R.string.category_app_coordinates),
                        count = counts.appCoordinateSystems,
                        checked = selection.appCoordinateSystems,
                        onCheckedChange = { selection = selection.copy(appCoordinateSystems = it) }
                    )
                    SelectionRow(
                        label = stringResource(R.string.category_app_settings),
                        count = counts.settings,
                        showCount = false,
                        checked = selection.settings,
                        onCheckedChange = { selection = selection.copy(settings = it) }
                    )
                    SelectionRow(
                        label = stringResource(R.string.category_api_keys),
                        count = counts.apiKeys,
                        warning = stringResource(R.string.category_api_keys_warning),
                        checked = selection.apiKeys,
                        onCheckedChange = { selection = selection.copy(apiKeys = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.cancel), fontSize = 14.sp)
                    }
                    Button(
                        onClick = { onConfirm(selection) },
                        enabled = selection.hasAny,
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text(
                            stringResource(if (isExport) R.string.export_action else R.string.import_action),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionRow(
    label: String,
    count: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    warning: String? = null,
    showCount: Boolean = true
) {
    val enabled = count > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .noRippleClickable { if (enabled) onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked && enabled,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = AccentBlue)
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (showCount && enabled) {
                    stringResource(R.string.category_with_count, label, count)
                } else label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
            )
            if (warning != null) {
                Text(
                    text = warning,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 0.85f else 0.4f)
                )
            } else if (!enabled) {
                Text(
                    text = stringResource(R.string.category_empty),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}
