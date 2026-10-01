package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.saveCollectionInfo
import com.vincenthzr.locationspoofer.viewmodel.skipCollectionInfo
import com.vincenthzr.locationspoofer.viewmodel.saveCollectionRouteInfo
import com.vincenthzr.locationspoofer.viewmodel.skipCollectionRouteInfo
import java.util.Locale

/** 位于主界面层，采集中切换页面也能在停止后填写点位信息。 */
@Composable
fun CollectionInfoDialogHost(viewModel: MainViewModel, uiState: AppState) {
    val route = uiState.pendingCollectionRoute
    if (route != null) {
        CollectionRouteInfoDialog(
            route, uiState.isSavingCollectionInfo,
            onDismiss = viewModel::skipCollectionRouteInfo,
            onSave = { name, remark -> viewModel.saveCollectionRouteInfo(route.id, name, remark) }
        )
        return
    }
    val record = uiState.pendingCollectionLocations.firstOrNull() ?: return
    var placeName by rememberSaveable(record.id) { mutableStateOf(record.placeName) }
    var remark by rememberSaveable(record.id) { mutableStateOf(record.remark) }
    val saving = uiState.isSavingCollectionInfo

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (!saving) viewModel.skipCollectionInfo() },
        title = { Text(stringResource(R.string.collection_info_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.collection_info_description))
                if (uiState.pendingCollectionLocations.size > 1) {
                    Text(stringResource(R.string.collection_info_remaining, uiState.pendingCollectionLocations.size))
                }
                Text(
                    String.format(Locale.US, "%.6f, %.6f", record.lat, record.lng),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = placeName,
                    onValueChange = { placeName = it },
                    label = { Text(stringResource(R.string.collection_location_name)) },
                    placeholder = { Text(stringResource(R.string.set_place_name_hint)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = remark,
                    onValueChange = { remark = it },
                    label = { Text(stringResource(R.string.remark_note)) },
                    placeholder = { Text(stringResource(R.string.add_remark_hint)) },
                    minLines = 2,
                    maxLines = 3,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = viewModel::skipCollectionInfo) {
                Text(stringResource(R.string.collection_info_skip))
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = { viewModel.saveCollectionInfo(record.id, placeName, remark) }
            ) {
                Text(stringResource(if (saving) R.string.collection_info_saving else R.string.save))
            }
        }
    )
}
