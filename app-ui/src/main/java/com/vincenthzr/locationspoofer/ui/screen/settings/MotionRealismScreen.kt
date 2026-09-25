package com.vincenthzr.locationspoofer.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vincenthzr.locationspoofer.data.model.AppState
import com.vincenthzr.locationspoofer.data.model.GaitRecordingState
import com.vincenthzr.locationspoofer.ui.R
import com.vincenthzr.locationspoofer.ui.theme.AccentBlue
import com.vincenthzr.locationspoofer.ui.theme.AccentGreen
import com.vincenthzr.locationspoofer.ui.theme.AccentOrange
import com.vincenthzr.locationspoofer.ui.theme.AppColors
import com.vincenthzr.locationspoofer.ui.theme.noRippleClickable
import com.vincenthzr.locationspoofer.utils.GaitTemplate
import com.vincenthzr.locationspoofer.utils.MotionRealism
import com.vincenthzr.locationspoofer.viewmodel.MainViewModel
import com.vincenthzr.locationspoofer.viewmodel.cancelGaitRecording
import com.vincenthzr.locationspoofer.viewmodel.deleteGaitTemplate
import com.vincenthzr.locationspoofer.viewmodel.dismissGaitRecordingResult
import com.vincenthzr.locationspoofer.viewmodel.setRealismLevel
import com.vincenthzr.locationspoofer.viewmodel.setSpeedFluctuationPct
import com.vincenthzr.locationspoofer.viewmodel.setUseGaitTemplate
import com.vincenthzr.locationspoofer.viewmodel.startGaitRecording
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

@Composable
fun MotionRealismScreen(
    viewModel: MainViewModel,
    uiState: AppState,
    isDark: Boolean = isSystemInDarkTheme(),
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    // 录制时手机放进口袋，保持屏幕常亮，避免熄屏后传感器数据中断
    val recording = uiState.gaitRecording
    val keepAwake = recording is GaitRecordingState.Countdown || recording is GaitRecordingState.Recording
    val view = LocalView.current
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background(isDark))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0xFF22272E) else Color.White)
                        .border(
                            width = 1.dp,
                            color = if (isDark) Color.White.copy(alpha = 0.14f) else Color(0xFFE5E8EC),
                            shape = CircleShape
                        )
                        .noRippleClickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = if (isDark) Color.White else Color(0xFF1A1D20),
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(R.string.motion_realism_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.motion_realism_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RealismLevelCard(uiState, isDark) { viewModel.setRealismLevel(it) }
                SpeedFluctuationCard(uiState) { viewModel.setSpeedFluctuationPct(it) }
                GaitCard(viewModel, uiState, isDark)
                HintRow(stringResource(R.string.realism_next_session_hint), isDark)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CardHeader(icon: ImageVector, tint: Color, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = desc,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

@Composable
private fun RealismLevelCard(uiState: AppState, isDark: Boolean, onSelect: (Int) -> Unit) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.Rounded.Shuffle,
            AccentBlue,
            stringResource(R.string.realism_level_title),
            stringResource(R.string.realism_level_desc)
        )
        Spacer(Modifier.height(14.dp))
        val levels = listOf(
            MotionRealism.Level.OFF to stringResource(R.string.realism_level_off),
            MotionRealism.Level.LOW to stringResource(R.string.realism_level_low),
            MotionRealism.Level.MEDIUM to stringResource(R.string.realism_level_medium),
            MotionRealism.Level.HIGH to stringResource(R.string.realism_level_high)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            levels.forEach { (level, label) ->
                val selected = uiState.realismLevel == level.id
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) AccentBlue
                            else if (isDark) Color.White.copy(alpha = 0.05f)
                            else Color.Black.copy(alpha = 0.04f)
                        )
                        .noRippleClickable { onSelect(level.id) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.5.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedFluctuationCard(uiState: AppState, onChange: (Int) -> Unit) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.Rounded.Speed,
            AccentOrange,
            stringResource(R.string.speed_fluctuation_title),
            stringResource(R.string.speed_fluctuation_desc)
        )
        Spacer(Modifier.height(8.dp))
        var local by remember(uiState.speedFluctuationPct) { mutableFloatStateOf(uiState.speedFluctuationPct.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = local,
                onValueChange = { local = it },
                onValueChangeFinished = { onChange(local.roundToInt()) },
                valueRange = 0f..MotionRealism.MAX_SPEED_FLUCTUATION_PCT.toFloat(),
                steps = MotionRealism.MAX_SPEED_FLUCTUATION_PCT / 5 - 1,
                colors = SliderDefaults.colors(thumbColor = AccentOrange, activeTrackColor = AccentOrange),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.speed_fluctuation_value, local.roundToInt()),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AccentOrange,
                modifier = Modifier.widthIn(min = 48.dp)
            )
        }
    }
}

@Composable
private fun GaitCard(viewModel: MainViewModel, uiState: AppState, isDark: Boolean) {
    MiuixCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(16.dp)) {
        CardHeader(
            Icons.AutoMirrored.Rounded.DirectionsWalk,
            AccentGreen,
            stringResource(R.string.gait_title),
            stringResource(R.string.gait_desc)
        )
        Spacer(Modifier.height(12.dp))

        when (val recording = uiState.gaitRecording) {
            is GaitRecordingState.Countdown, is GaitRecordingState.Recording, GaitRecordingState.Processing -> {
                val text = when (recording) {
                    is GaitRecordingState.Countdown -> stringResource(R.string.gait_countdown_format, recording.secondsLeft)
                    is GaitRecordingState.Recording -> stringResource(R.string.gait_recording)
                    else -> stringResource(R.string.gait_processing)
                }
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                if (recording is GaitRecordingState.Recording) {
                    LinearProgressIndicator(
                        progress = { recording.progress },
                        color = AccentGreen,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(color = AccentGreen, modifier = Modifier.fillMaxWidth())
                }
                if (recording !is GaitRecordingState.Processing) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { viewModel.cancelGaitRecording() }) {
                        Text(stringResource(R.string.gait_cancel))
                    }
                }
            }

            is GaitRecordingState.Failed -> {
                val reason = when (recording.reason) {
                    GaitTemplate.Reason.TOO_SHORT -> stringResource(R.string.gait_failed_too_short)
                    GaitTemplate.Reason.NO_RHYTHM -> stringResource(R.string.gait_failed_no_rhythm)
                    GaitTemplate.Reason.TOO_FEW_STRIDES -> stringResource(R.string.gait_failed_too_few_strides)
                }
                Text(reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.startGaitRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) { Text(stringResource(R.string.gait_retry)) }
                    OutlinedButton(onClick = { viewModel.dismissGaitRecordingResult() }) {
                        Text(stringResource(R.string.gait_cancel))
                    }
                }
            }

            GaitRecordingState.Idle -> {
                val cadence = uiState.gaitTemplateCadence
                Text(
                    text = if (cadence != null) {
                        stringResource(R.string.gait_recorded_format, cadence, uiState.gaitTemplateStrides)
                    } else {
                        stringResource(R.string.gait_not_recorded)
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                if (cadence != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.gait_use_template),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = uiState.useGaitTemplate, onCheckedChange = { viewModel.setUseGaitTemplate(it) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.startGaitRecording() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                    ) {
                        Text(stringResource(if (cadence != null) R.string.gait_rerecord else R.string.gait_record))
                    }
                    if (cadence != null) {
                        OutlinedButton(onClick = { viewModel.deleteGaitTemplate() }) {
                            Text(stringResource(R.string.gait_delete))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HintRow(stringResource(R.string.gait_scope_hint), isDark)
    }
}

@Composable
private fun HintRow(text: String, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Rounded.Info,
            null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}
