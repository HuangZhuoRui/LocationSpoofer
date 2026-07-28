package com.suseoaa.locationspoofer.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiSearch
import androidx.compose.ui.res.stringResource
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.GithubRelease
import com.suseoaa.locationspoofer.data.model.SavedLocation
import com.suseoaa.locationspoofer.data.model.WifiLoadStatus
import com.suseoaa.locationspoofer.ui.components.AppMapView
import com.suseoaa.locationspoofer.ui.components.AppMapController
import com.suseoaa.locationspoofer.data.model.AppMapType
import com.suseoaa.locationspoofer.ui.components.MapTypeDialog
import androidx.compose.material.icons.rounded.Layers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AccentGreen
import com.suseoaa.locationspoofer.ui.theme.AccentOrange
import com.suseoaa.locationspoofer.ui.theme.AppColors
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import com.suseoaa.locationspoofer.BuildConfig
import androidx.compose.runtime.Composable
import com.suseoaa.locationspoofer.ui.theme.*

@Composable
fun UpdateDialog(
    uiState: com.suseoaa.locationspoofer.viewmodel.UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: (String, String) -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: (String) -> Unit
) {
    val context = LocalContext.current
    val currentVersion = BuildConfig.VERSION_NAME

    // 查找遗漏的版本（比当前版本更新）
    val missed = remember(uiState.releases) {
        uiState.releases.filter { isNewerVersion(it.versionName, currentVersion) }
    }

    val displayList = remember(uiState.releases, missed) {
        if (missed.size > 1) {
            val latest = missed.first()
            val grouped = parseAndCategorizeReleaseNotes(missed)
            val mergedBody = generateMergedMarkdown(context, grouped)
            val mergedRelease = latest.copy(body = mergedBody)
            val historical = uiState.releases.filter { it !in missed }
            listOf(mergedRelease) + historical
        } else {
            uiState.releases
        }
    }

    LocalizedDialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.update_dialog_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))

                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (uiState.error != null) {
                    Text(uiState.error, color = MaterialTheme.colorScheme.error)
                } else if (uiState.releases.isEmpty()) {
                    Text(stringResource(R.string.no_updates_available))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(displayList) { release ->
                            val isCurrentVersion =
                                release.versionName.contains(BuildConfig.VERSION_NAME) ||
                                        BuildConfig.VERSION_NAME.contains(release.versionName)
                            val isMergedRelease = missed.size > 1 && release == displayList.first()

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(R.string.version, release.versionName),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    if (isCurrentVersion) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AccentGreen.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.current_version),
                                                fontSize = 10.sp,
                                                color = AccentGreen,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    if (isMergedRelease) {
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(AccentBlue.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.merged_updates_badge,
                                                    missed.size
                                                ),
                                                fontSize = 10.sp,
                                                color = AccentBlue,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = parseMarkdown(release.body),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                if (release.downloadUrl != null || release.downloadUrl32Bit != null) {
                                    val isDownloadingThis = uiState.activeDownloadId != null && 
                                        (uiState.activeDownloadUrl == release.downloadUrl || uiState.activeDownloadUrl == release.downloadUrl32Bit)
                                        
                                    if (isDownloadingThis) {
                                        if (uiState.downloadStatus == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                            Button(
                                                onClick = onInstall,
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                                            ) {
                                                Text(stringResource(R.string.install))
                                            }
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        stringResource(
                                                            R.string.downloading,
                                                            uiState.downloadProgress
                                                        ),
                                                        color = MaterialTheme.colorScheme.onBackground,
                                                        fontSize = 12.sp
                                                    )
                                                    Spacer(Modifier.height(4.dp))
                                                    LinearProgressIndicator(
                                                        progress = { uiState.downloadProgress / 100f },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        color = AccentBlue
                                                    )
                                                }
                                                Spacer(Modifier.width(12.dp))
                                                IconButton(
                                                    onClick = onCancel,
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Cancel,
                                                        stringResource(R.string.cancel),
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    } else if (uiState.activeDownloadId == null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (release.downloadUrl != null) {
                                                Button(
                                                    onClick = {
                                                        onDownload(
                                                            release.downloadUrl,
                                                            release.versionName
                                                        )
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                                                ) {
                                                    Text(
                                                        if (release.downloadUrl32Bit != null) 
                                                            stringResource(R.string.download) + " (64位/默认)" 
                                                        else 
                                                            stringResource(R.string.download)
                                                    )
                                                }
                                            }
                                            
                                            if (release.downloadUrl32Bit != null) {
                                                TextButton(
                                                    onClick = {
                                                        onDownload(
                                                            release.downloadUrl32Bit,
                                                            release.versionName + "_32bit"
                                                        )
                                                    }
                                                ) {
                                                    Text("下载 32位版本")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val latestRelease = uiState.releases.firstOrNull()
                    if (latestRelease != null) {
                        val isCurrentVersion =
                            latestRelease.versionName.contains(BuildConfig.VERSION_NAME) ||
                                    BuildConfig.VERSION_NAME.contains(latestRelease.versionName)
                        if (!isCurrentVersion) {
                            TextButton(
                                onClick = { onIgnore(latestRelease.versionName) }
                            ) {
                                Text(
                                    stringResource(R.string.ignore_this_version),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

@Composable
fun SavedLocationsDialog(
    savedLocations: List<SavedLocation>,
    onDismiss: () -> Unit,
    onSelect: (SavedLocation) -> Unit,
    onDelete: (SavedLocation) -> Unit
) {
    LocalizedDialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.saved_locations),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))
                if (savedLocations.isEmpty()) {
                    Text(
                        stringResource(R.string.no_saved_locations),
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 14.sp
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(savedLocations) { loc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(loc) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Place, null, tint = AccentBlue)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        loc.name,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        "${loc.lat}, ${loc.lng}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                IconButton(onClick = { onDelete(loc) }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(
                        stringResource(R.string.close)
                    )
                }
            }
        }
    }
}

@Composable
fun CustomCoordinateDialog(
    initialLat: String,
    initialLng: String,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var lat by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            initialLat
        )
    }
    var lng by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            initialLng
        )
    }
    val textSecondary = AppColors.textSecondary(isDark)

    val currentContext = androidx.compose.ui.platform.LocalContext.current
    val currentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        title = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides currentContext,
                androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration
            ) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.R.string.custom_coordinate_title),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
        },
        text = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides currentContext,
                androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration
            ) {
                Column {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.R.string.custom_coord_desc),
                        color = textSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = lng,
                        onValueChange = { lng = it },
                        label = {
                            androidx.compose.material3.Text(
                                androidx.compose.ui.res.stringResource(
                                    com.suseoaa.locationspoofer.R.string.longitude
                                )
                            )
                        },
                        placeholder = {
                            androidx.compose.material3.Text(
                                androidx.compose.ui.res.stringResource(
                                    com.suseoaa.locationspoofer.R.string.coordinate_hint
                                ), color = textSecondary
                            )
                        },
                        leadingIcon = {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Outlined.East,
                                null,
                                tint = textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = coordinateFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = {
                            androidx.compose.material3.Text(
                                androidx.compose.ui.res.stringResource(
                                    com.suseoaa.locationspoofer.R.string.latitude
                                )
                            )
                        },
                        placeholder = {
                            androidx.compose.material3.Text(
                                androidx.compose.ui.res.stringResource(
                                    com.suseoaa.locationspoofer.R.string.coordinate_hint
                                ), color = textSecondary
                            )
                        },
                        leadingIcon = {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Outlined.North,
                                null,
                                tint = textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = coordinateFieldColors()
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides currentContext,
                androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration
            ) {
                androidx.compose.material3.TextButton(onClick = { onConfirm(lat, lng) }) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.R.string.confirm),
                        color = AccentBlue
                    )
                }
            }
        },
        dismissButton = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides currentContext,
                androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration
            ) {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text(
                        androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.R.string.cancel),
                        color = textSecondary
                    )
                }
            }
        }
    )
}

@Composable
fun LocalizedDialog(
    onDismissRequest: () -> Unit,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
    content: @Composable () -> Unit
) {
    val currentContext = androidx.compose.ui.platform.LocalContext.current
    val currentConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalContext provides currentContext,
            androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration
        ) {
            content()
        }
    }
}

@Composable
fun StartSpoofingDialog(
    uiState: AppState,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onToggleWifi: () -> Unit,
    onToggleCell: () -> Unit,
    onToggleBluetooth: () -> Unit,
    onToggleJitter: () -> Unit,
    onAltitudeChange: (String) -> Unit,
    onSatelliteCountChange: (String) -> Unit
) {
    LocalizedDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppColors.cardBackground(isDark)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.spoofing_options_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.spoofing_options_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(16.dp))

                if (uiState.canMockWifi || uiState.wigleToken.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Wifi,
                            null,
                            tint = AccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.mock_wifi_data),
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Switch(checked = uiState.mockWifi, onCheckedChange = { onToggleWifi() })
                    }
                }

                if (uiState.canMockCell || uiState.opencellidToken.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.CellTower,
                            null,
                            tint = AccentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.mock_cell_data),
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Switch(checked = uiState.mockCell, onCheckedChange = { onToggleCell() })
                    }
                }

                if (uiState.canMockBluetooth) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Bluetooth,
                            null,
                            tint = AccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.mock_bluetooth_data),
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Switch(
                            checked = uiState.mockBluetooth,
                            onCheckedChange = { onToggleBluetooth() })
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        null,
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.enable_slight_jitter),
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Switch(checked = uiState.enableJitter, onCheckedChange = { onToggleJitter() })
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = uiState.altitudeInput,
                        onValueChange = onAltitudeChange,
                        label = { Text("海拔 (米)", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            focusedLabelColor = AccentBlue
                        )
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = uiState.satelliteCountInput,
                        onValueChange = onSatelliteCountChange,
                        label = { Text("卫星数", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            focusedLabelColor = AccentBlue
                        )
                    )
                }

                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        Text(stringResource(R.string.start_simulation))
                    }
                }
            }
        }
    }
}