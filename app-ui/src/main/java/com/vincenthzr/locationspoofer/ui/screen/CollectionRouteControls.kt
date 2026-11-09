package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.viewmodel.*

@Composable
fun CollectionRouteControls(viewModel: MainViewModel, state: AppState, map: AppMapController?, modifier: Modifier = Modifier) {
    val busy = state.isContinuousScanning || state.isStoppingCollection
    Surface(modifier = modifier.fillMaxWidth(0.92f), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !state.isRouteCollection, onClick = { viewModel.setRouteCollection(false) },
                        label = { Text(stringResource(R.string.collection_point_mode)) }, modifier = Modifier.weight(1f))
                    FilterChip(selected = state.isRouteCollection, onClick = { viewModel.setRouteCollection(true) },
                        label = { Text(stringResource(R.string.collection_route_mode)) }, modifier = Modifier.weight(1f))
                }
            }
            if (state.isRouteCollection) {
                Text(stringResource(if (state.isDrawingCollectionRoute) R.string.collection_route_draw_hint else R.string.collection_route_collect_hint), style = MaterialTheme.typography.bodySmall)
                if (state.isDrawingCollectionRoute) {
                    Text(stringResource(R.string.selected_waypoints_count, state.collectionRoutePoints.size), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = state.collectionRoutePoints.isNotEmpty(), onClick = viewModel::undoCollectionRoutePoint) {
                            Text(stringResource(R.string.collection_route_undo))
                        }
                        Button(modifier = Modifier.weight(1f), onClick = {
                            val lat = map?.cameraTargetLat ?: state.latitudeInput.toDoubleOrNull()
                            val lng = map?.cameraTargetLng ?: state.longitudeInput.toDoubleOrNull()
                            if (lat != null && lng != null) viewModel.addCollectionRoutePoint(lat, lng)
                        }) { Text(stringResource(R.string.collection_route_add_point)) }
                        TextButton(enabled = state.collectionRoutePoints.size >= 2, onClick = viewModel::finishCollectionRouteDrawing) {
                            Text(stringResource(R.string.collection_route_done))
                        }
                    }
                } else if (!busy) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.selected_waypoints_count, state.collectionRoutePoints.size), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::drawCollectionRoute, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.collection_route_edit_drawing))
                        }
                    }
                }
            }
            if (!state.isRouteCollection || !state.isDrawingCollectionRoute) {
                Button(
                    enabled = !state.isStoppingCollection,
                    onClick = viewModel::toggleContinuousScanning,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.isContinuousScanning) MaterialTheme.colorScheme.error else AccentGreen)
                ) {
                    Text(stringResource(when {
                        state.isStoppingCollection -> R.string.collection_stopping
                        state.isContinuousScanning -> R.string.stop_collection
                        state.isRouteCollection -> R.string.collection_route_start
                        else -> R.string.start_collection
                    }))
                }
            }
        }
    }
}
