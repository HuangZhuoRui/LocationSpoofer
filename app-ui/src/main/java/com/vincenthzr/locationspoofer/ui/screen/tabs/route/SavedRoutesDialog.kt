package com.vincenthzr.locationspoofer.ui.screen.tabs.route

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.components.AppMapController
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.deleteSavedRoute
import com.vincenthzr.locationspoofer.viewmodel.loadSavedRoute

@Composable
fun SavedRoutesDialog(
    uiState: AppState,
    viewModel: MainViewModel,
    mapController: AppMapController?,
    bottomActionHeightDp: Dp,
    bottomBarHeight: Dp,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
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
                // 顶部标题
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
                                Icons.Rounded.Bookmarks,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.route_library),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (uiState.savedRoutes.isEmpty()) stringResource(R.string.no_saved_routes) else stringResource(R.string.total_saved_routes, uiState.savedRoutes.size),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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

                if (uiState.savedRoutes.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Rounded.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.no_saved_routes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.add_points_save_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.savedRoutes) { route ->
                            Surface(
                                onClick = {
                                    viewModel.loadSavedRoute(route)
                                    if (route.points.size >= 2) {
                                        val padLeft = with(density) { 36.dp.roundToPx() }
                                        val padTop = with(density) { 80.dp.roundToPx() }
                                        val padRight = with(density) { 36.dp.roundToPx() }
                                        val effectiveBottomDp =
                                            if (bottomActionHeightDp > 0.dp) bottomActionHeightDp + bottomBarHeight + 24.dp else bottomBarHeight + 160.dp
                                        val padBottom =
                                            with(density) { effectiveBottomDp.roundToPx() }
                                        mapController?.fitBounds(
                                            points = route.points.map { Pair(it.lat, it.lng) },
                                            paddingLeft = padLeft,
                                            paddingTop = padTop,
                                            paddingRight = padRight,
                                            paddingBottom = padBottom
                                        )
                                    } else {
                                        route.points.firstOrNull()?.let { firstPoint ->
                                            mapController?.animateCamera(
                                                firstPoint.lat,
                                                firstPoint.lng,
                                                17.5f
                                            )
                                        }
                                    }
                                    onDismiss()
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.loaded_route, route.name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
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
                                            Icons.Rounded.Route,
                                            contentDescription = null,
                                            tint = AccentBlue,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            route.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            stringResource(
                                                R.string.route_nodes_count,
                                                route.points.size
                                            ),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = 0.55f
                                            )
                                        )
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteSavedRoute(route) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
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
