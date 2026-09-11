package com.suseoaa.locationspoofer.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.East
import androidx.compose.material.icons.outlined.North
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import com.suseoaa.locationspoofer.ui.theme.AppColors

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
        modifier = Modifier.imePadding(),
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        title = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides currentContext,
                androidx.compose.ui.platform.LocalConfiguration provides currentConfiguration
            ) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.ui.R.string.custom_coordinate_title),
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
                        androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.ui.R.string.custom_coord_desc),
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
                                    com.suseoaa.locationspoofer.ui.R.string.longitude
                                )
                            )
                        },
                        placeholder = {
                            androidx.compose.material3.Text(
                                androidx.compose.ui.res.stringResource(
                                    com.suseoaa.locationspoofer.ui.R.string.coordinate_hint
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
                                    com.suseoaa.locationspoofer.ui.R.string.latitude
                                )
                            )
                        },
                        placeholder = {
                            androidx.compose.material3.Text(
                                androidx.compose.ui.res.stringResource(
                                    com.suseoaa.locationspoofer.ui.R.string.coordinate_hint
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
                        androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.ui.R.string.confirm),
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
                        androidx.compose.ui.res.stringResource(com.suseoaa.locationspoofer.ui.R.string.cancel),
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
