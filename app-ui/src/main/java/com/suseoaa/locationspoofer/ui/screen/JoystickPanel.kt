package com.suseoaa.locationspoofer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.suseoaa.locationspoofer.ui.theme.AccentOrange
import com.suseoaa.locationspoofer.viewmodel.MainViewModel
import com.suseoaa.locationspoofer.viewmodel.moveByJoystick
import kotlin.math.atan2
import kotlin.math.sqrt

// 摇杆控制面板
@Composable
fun JoystickPanel(viewModel: MainViewModel, maxSpeedMs: Float = 10f) {
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 120f
    var joystickState by remember { mutableStateOf(Pair(0.0, 0f)) }

    LaunchedEffect(joystickState) {
        val (angle, intensity) = joystickState
        if (intensity > 0) {
            while (true) {
                val bearing = (Math.toDegrees(angle) + 90 + 360) % 360
                viewModel.moveByJoystick(bearing, intensity, maxSpeedMs)
                kotlinx.coroutines.delay(100)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(160.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                CircleShape
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        thumbOffset = Offset.Zero
                        joystickState = Pair(0.0, 0f)
                    },
                    onDragCancel = {
                        thumbOffset = Offset.Zero
                        joystickState = Pair(0.0, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val raw = thumbOffset + dragAmount
                    val dist = sqrt(raw.x * raw.x + raw.y * raw.y)
                    thumbOffset = if (dist <= maxRadius) raw else raw * (maxRadius / dist)
                    val angle = atan2(thumbOffset.y.toDouble(), thumbOffset.x.toDouble())
                    val intensity =
                        (sqrt(thumbOffset.x * thumbOffset.x + thumbOffset.y * thumbOffset.y) / maxRadius).coerceIn(
                            0f,
                            1f
                        )
                    joystickState = Pair(angle, intensity)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.x.toInt(), thumbOffset.y.toInt()) }
                .size(52.dp)
                .background(AccentOrange, CircleShape)
        )
    }
}
