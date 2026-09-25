package com.vincenthzr.locationspoofer.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.ui.BuildConfig
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors

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
    onToggleRestartApps: () -> Unit,
    onAltitudeChange: (String) -> Unit,
    onAltitudeVariationChange: (Int) -> Unit,
    onSatelliteCountChange: (String) -> Unit
) {
    LocalizedDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppColors.cardBackground(isDark),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
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

                // 全局方案由系统服务实时合成 Wi-Fi/基站/蓝牙数据，开关始终可用；
                // 非全局方案只有采集到对应数据（或配置了在线数据源 Token）时才显示
                val showWifiToggle = BuildConfig.GLOBAL_SCHEME || uiState.canMockWifi || uiState.wigleToken.isNotBlank()
                val showCellToggle = BuildConfig.GLOBAL_SCHEME || uiState.canMockCell || uiState.opencellidToken.isNotBlank()
                val showBluetoothToggle = BuildConfig.GLOBAL_SCHEME || uiState.canMockBluetooth

                if (showWifiToggle) {
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

                if (showCellToggle) {
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

                if (showBluetoothToggle) {
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.RestartAlt,
                        null,
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.restart_apps_on_spoof),
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Switch(
                        checked = uiState.restartAppsOnSpoof,
                        onCheckedChange = { onToggleRestartApps() })
                }

                Spacer(Modifier.height(8.dp))
                AltitudeSettingsCard(
                    altitudeInput = uiState.altitudeInput,
                    variationM = uiState.altitudeVariationM,
                    onAltitudeChange = onAltitudeChange,
                    onVariationChange = onAltitudeVariationChange
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = uiState.satelliteCountInput,
                    onValueChange = onSatelliteCountChange,
                    label = { Text(stringResource(R.string.satellite_count), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        focusedLabelColor = AccentBlue
                    )
                )

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
