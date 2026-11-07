package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.data.db.CollectionRouteRecord
import com.vincenthzr.locationspoofer.ui.R

@Composable
fun CollectionRouteInfoDialog(route: CollectionRouteRecord, saving: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable(route.id) { mutableStateOf(route.name) }
    var remark by rememberSaveable(route.id) { mutableStateOf(route.remark) }
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.collection_route_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.collection_route_save_description))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.collection_route_name)) },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(remark, { remark = it }, label = { Text(stringResource(R.string.remark_note)) },
                    maxLines = 3, enabled = !saving, modifier = Modifier.fillMaxWidth())
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.collection_info_skip)) } },
        confirmButton = {
            Button(enabled = name.isNotBlank() && !saving, onClick = { onSave(name, remark) }) {
                Text(stringResource(if (saving) R.string.collection_info_saving else R.string.save))
            }
        }
    )
}
