package com.vincenthzr.locationspoofer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.data.db.CompleteCollectionRoute
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue

@Composable
fun CollectionRouteListItem(item: CompleteCollectionRoute, onClick: () -> Unit, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Route, null, tint = AccentBlue, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.route.name.ifBlank { stringResource(R.string.collection_route_unnamed) }, fontWeight = FontWeight.Bold)
                if (item.route.remark.isNotBlank()) Text(item.route.remark, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.collection_route_summary, item.samples.size, item.route.halfWidthM.toInt()), style = MaterialTheme.typography.bodySmall)
            }
            onEdit?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) } }
            onDelete?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) } }
        }
    }
}
