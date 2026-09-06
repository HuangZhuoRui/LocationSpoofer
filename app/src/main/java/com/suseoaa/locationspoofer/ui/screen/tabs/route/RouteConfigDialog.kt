package com.suseoaa.locationspoofer.ui.screen.tabs.route

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suseoaa.locationspoofer.R
import com.suseoaa.locationspoofer.data.model.AppState
import com.suseoaa.locationspoofer.data.model.RouteRunMode
import com.suseoaa.locationspoofer.data.model.SimMode
import com.suseoaa.locationspoofer.ui.theme.AccentBlue
import java.util.Locale

@Composable
fun RouteConfigDialog(
    uiState: AppState,
    onDismiss: () -> Unit,
    onStartRoute: () -> Unit,
    onRunModeChange: (RouteRunMode) -> Unit,
    onSpeedChange: (SimMode) -> Unit,
    onCustomSpeedChange: (Double) -> Unit = {},
    onUseRealRouteChange: (Boolean) -> Unit = {},
    onStopAtDestinationChange: (Boolean) -> Unit = {},
    onEnableStepSimulationChange: (Boolean) -> Unit = {},
    onStepCadenceChange: (Int) -> Unit = {},
    onIsAutoCadenceChange: (Boolean) -> Unit = {},
    onToggleWifi: () -> Unit = {},
    onToggleCell: () -> Unit = {},
    onToggleBluetooth: () -> Unit = {},
    onToggleJitter: () -> Unit = {},
    onSatelliteCountChange: (String) -> Unit = {}
) {
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
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部标题
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.route_sim_settings),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 运行模式选择 (手动 / 循环)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.run_mode),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RouteSelectionCard(
                            title = stringResource(R.string.loop_auto_cruise),
                            subtitle = stringResource(R.string.loop_auto_cruise_desc),
                            icon = Icons.Rounded.Autorenew,
                            isSelected = uiState.routeRunMode == RouteRunMode.LOOP,
                            onClick = { onRunModeChange(RouteRunMode.LOOP) },
                            modifier = Modifier.weight(1f)
                        )
                        RouteSelectionCard(
                            title = stringResource(R.string.joystick_manual_control),
                            subtitle = stringResource(R.string.joystick_manual_control_desc),
                            icon = Icons.Rounded.SportsEsports,
                            isSelected = uiState.routeRunMode == RouteRunMode.MANUAL,
                            onClick = { onRunModeChange(RouteRunMode.MANUAL) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 速度选择
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.cruise_speed),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            Triple(SimMode.WALKING, stringResource(R.string.walking), "1.4m/s"),
                            Triple(SimMode.RUNNING, stringResource(R.string.running), "3.0m/s"),
                            Triple(SimMode.CYCLING, stringResource(R.string.cycling), "5.5m/s"),
                            Triple(SimMode.DRIVING, stringResource(R.string.driving), "15m/s"),
                            Triple(
                                SimMode.CUSTOM,
                                stringResource(R.string.custom),
                                "${
                                    String.format(
                                        Locale.US,
                                        "%.1f",
                                        uiState.customSpeedMs
                                    )
                                }m/s"
                            )
                        ).forEach { (mode, name, speed) ->
                            val isSelected = uiState.routeSimMode == mode
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) AccentBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = 0.35f
                                ),
                                border = if (isSelected) BorderStroke(1.5.dp, AccentBlue) else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onSpeedChange(mode) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        name,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        speed,
                                        fontSize = 10.5.sp,
                                        color = if (isSelected) AccentBlue.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.45f
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 自定义速度调节模块
                    if (uiState.routeSimMode == SimMode.CUSTOM) {
                        var sliderValue by remember(uiState.customSpeedMs) {
                            mutableFloatStateOf(
                                uiState.customSpeedMs.toFloat()
                            )
                        }
                        val paceSec = if (sliderValue > 0.2f) (1000.0 / sliderValue).toInt() else 0
                        val paceMin = paceSec / 60
                        val paceRem = paceSec % 60
                        val paceStr = if (paceSec in 60..3599) "${paceMin}'${
                            String.format(
                                Locale.US,
                                "%02d",
                                paceRem
                            )
                        }\"/km" else "--"

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            stringResource(R.string.custom_speed),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            stringResource(R.string.pace_format, paceStr),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    Text(
                                        "${
                                            String.format(
                                                Locale.US,
                                                "%.1f",
                                                sliderValue
                                            )
                                        } m/s (${
                                            String.format(
                                                Locale.US,
                                                "%.1f",
                                                sliderValue * 3.6
                                            )
                                        } km/h)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentBlue
                                    )
                                }

                                Slider(
                                    value = sliderValue,
                                    onValueChange = {
                                        sliderValue = it
                                        onCustomSpeedChange(it.toDouble())
                                    },
                                    valueRange = 0.2f..45.0f,
                                    steps = 447,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AccentBlue,
                                        activeTrackColor = AccentBlue,
                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )

                                // 微调步进按钮
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(-1.0f, -0.2f, 0.2f, 1.0f).forEach { step ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(
                                                alpha = 0.6f
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    val newVal =
                                                        (sliderValue + step).coerceIn(0.2f, 45.0f)
                                                    sliderValue = newVal
                                                    onCustomSpeedChange(newVal.toDouble())
                                                }
                                        ) {
                                            Text(
                                                if (step > 0) "+${step}m/s" else "${step}m/s",
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 步频与计步模拟卡片
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.step_cadence_simulation),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(R.string.step_cadence_simulation_desc),
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            Switch(
                                checked = uiState.enableStepSimulation,
                                onCheckedChange = onEnableStepSimulationChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AccentBlue
                                )
                            )
                        }

                        if (uiState.enableStepSimulation) {
                            // 步频模式切换：自适应 vs 固定步频
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (uiState.isAutoCadence) AccentBlue.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.5f
                                    ),
                                    border = if (uiState.isAutoCadence) BorderStroke(
                                        1.dp,
                                        AccentBlue
                                    ) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onIsAutoCadenceChange(true) }
                                ) {
                                    Text(
                                        stringResource(R.string.smart_adaptive_cadence),
                                        fontSize = 12.sp,
                                        fontWeight = if (uiState.isAutoCadence) FontWeight.Bold else FontWeight.Normal,
                                        color = if (uiState.isAutoCadence) AccentBlue else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (!uiState.isAutoCadence) AccentBlue.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.5f
                                    ),
                                    border = if (!uiState.isAutoCadence) BorderStroke(
                                        1.dp,
                                        AccentBlue
                                    ) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onIsAutoCadenceChange(false) }
                                ) {
                                    Text(
                                        stringResource(R.string.custom_fixed_cadence),
                                        fontSize = 12.sp,
                                        fontWeight = if (!uiState.isAutoCadence) FontWeight.Bold else FontWeight.Normal,
                                        color = if (!uiState.isAutoCadence) AccentBlue else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                            }

                            if (!uiState.isAutoCadence) {
                                var cadenceSlider by remember(uiState.stepCadenceSpm) {
                                    mutableFloatStateOf(
                                        uiState.stepCadenceSpm.toFloat()
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 2.dp, vertical = 2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            stringResource(R.string.set_cadence),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                        Text(
                                            "${cadenceSlider.toInt()} 步/分 (约 ${
                                                String.format(
                                                    Locale.US,
                                                    "%.1f",
                                                    cadenceSlider / 60.0
                                                )
                                            } 步/秒)",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AccentBlue
                                        )
                                    }
                                    Slider(
                                        value = cadenceSlider,
                                        onValueChange = {
                                            cadenceSlider = it
                                            onStepCadenceChange(it.toInt())
                                        },
                                        valueRange = 60f..240f,
                                        steps = 179,
                                        colors = SliderDefaults.colors(
                                            thumbColor = AccentBlue,
                                            activeTrackColor = AccentBlue,
                                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(R.string.smart_cadence_hint),
                                    fontSize = 11.sp,
                                    color = AccentBlue.copy(alpha = 0.85f),
                                    modifier = Modifier.padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }

                // 到达终点后停下开关
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.stop_at_destination_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                if (uiState.stopAtDestination) stringResource(R.string.stop_at_destination_desc_on) else stringResource(R.string.stop_at_destination_desc_off),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        Switch(
                            checked = uiState.stopAtDestination,
                            onCheckedChange = onStopAtDestinationChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AccentBlue
                            )
                        )
                    }
                }

                // 真实道路匹配开关
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.real_road_matching),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.real_road_matching_desc),
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        Switch(
                            checked = uiState.useRealRoute,
                            onCheckedChange = onUseRealRouteChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AccentBlue
                            )
                        )
                    }
                }


                // 环境模拟（Wi-Fi / 基站 / 蓝牙 / 抖动 / 卫星数）
                // 这些参数路线模拟本来就已经传给底层了，只是之前没有入口，
                // 用户必须回定点模拟那边才能改（issue #50）。这里复用同一份 uiState 与回调。
                Text(
                    stringResource(R.string.env_simulation_section),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                RouteEnvToggleRow(
                    title = stringResource(R.string.mock_wifi_data),
                    checked = uiState.mockWifi,
                    onCheckedChange = { onToggleWifi() }
                )
                RouteEnvToggleRow(
                    title = stringResource(R.string.mock_cell_data),
                    checked = uiState.mockCell,
                    onCheckedChange = { onToggleCell() }
                )
                RouteEnvToggleRow(
                    title = stringResource(R.string.mock_bluetooth_data),
                    checked = uiState.mockBluetooth,
                    onCheckedChange = { onToggleBluetooth() }
                )
                RouteEnvToggleRow(
                    title = stringResource(R.string.enable_slight_jitter),
                    subtitle = stringResource(R.string.enable_slight_jitter_desc),
                    checked = uiState.enableJitter,
                    onCheckedChange = { onToggleJitter() }
                )

                OutlinedTextField(
                    value = uiState.satelliteCountInput,
                    onValueChange = onSatelliteCountChange,
                    label = { Text(stringResource(R.string.satellite_count), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentBlue,
                        focusedLabelColor = AccentBlue
                    )
                )

                // 启动按钮
                Button(
                    onClick = onStartRoute,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.start_route_simulation), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun RouteSelectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) AccentBlue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(
            alpha = 0.35f
        ),
        border = if (isSelected) BorderStroke(1.5.dp, AccentBlue) else null,
        modifier = modifier.height(90.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface.copy(
                    alpha = 0.6f
                ),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
    }
}


@Composable
private fun RouteEnvToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentBlue
                )
            )
        }
    }
}
