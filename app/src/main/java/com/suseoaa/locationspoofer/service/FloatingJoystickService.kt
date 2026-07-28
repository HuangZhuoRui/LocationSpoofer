package com.suseoaa.locationspoofer.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.suseoaa.locationspoofer.data.repository.SettingsRepository
import com.suseoaa.locationspoofer.provider.SpooferProvider
import com.suseoaa.locationspoofer.utils.ConfigManager
import com.suseoaa.locationspoofer.utils.RootManager
import com.suseoaa.locationspoofer.utils.SettingsManager
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class FloatingJoystickService : Service(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private lateinit var rootManager: RootManager
    private lateinit var configManager: ConfigManager
    private lateinit var settingsRepo: SettingsRepository
    
    private var lastConfigSaveTime = 0L

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        rootManager = RootManager()
        configManager = ConfigManager(rootManager)
        settingsRepo = SettingsRepository(SettingsManager(this))
        
        showFloatingWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingJoystickService)
            setViewTreeViewModelStoreOwner(this@FloatingJoystickService)
            setViewTreeSavedStateRegistryOwner(this@FloatingJoystickService)

            setContent {
                MaterialTheme {
                    JoystickOverlay(
                        onMoveWindow = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            windowManager.updateViewLayout(this, params)
                        },
                        onJoystickChanged = { angle, intensity ->
                            if (intensity > 0) {
                                val bearing = (Math.toDegrees(angle) + 90 + 360) % 360
                                SpooferProvider.simBearing = bearing.toFloat()
                                updateLocationBasedOnJoystick(bearing, intensity)
                            } else {
                                lifecycleScope.launch {
                                    val rootManager = RootManager()
                                    val configManager = ConfigManager(rootManager)
                                    val settingsRepo = SettingsRepository(
                                        SettingsManager(this@FloatingJoystickService)
                                    )
                                    configManager.saveConfig(
                                        lat = SpooferProvider.latitude,
                                        lng = SpooferProvider.longitude,
                                        active = SpooferProvider.isActive,
                                        simMode = SpooferProvider.simMode,
                                        simBearing = SpooferProvider.simBearing,
                                        startTimestamp = SpooferProvider.startTimestamp,
                                        wifiJson = SpooferProvider.wifiJson,
                                        cellJson = SpooferProvider.cellJson,
                                        bluetoothJson = SpooferProvider.bluetoothJson,
                                        appCoordinateSystems = settingsRepo.getAppCoordinateSystems(),
                                        mockWifi = settingsRepo.mockWifi,
                                        mockCell = settingsRepo.mockCell,
                                        mockBluetooth = settingsRepo.mockBluetooth,
                                        enableJitter = settingsRepo.enableJitter
                                    )
                                }
                            }
                        },
                        onClose = { stopSelf() }
                    )
                }
            }
        }

        windowManager.addView(composeView, params)
    }

    private var lastUpdateTime = System.currentTimeMillis()

    private fun updateLocationBasedOnJoystick(bearing: Double, intensity: Float) {
        val now = System.currentTimeMillis()
        val elapsedSec = (now - lastUpdateTime) / 1000.0
        lastUpdateTime = now

        if (elapsedSec <= 0 || elapsedSec > 1.0) return // 忽略过大的跳跃

        // 根据强度使用最大 10 m/s 的基础速度
        val speedMs = 10.0 * intensity
        val distance = speedMs * elapsedSec
        val R = 6378137.0
        val bearingRad = Math.toRadians(bearing)

        val latRad = Math.toRadians(SpooferProvider.latitude)
        val lngRad = Math.toRadians(SpooferProvider.longitude)

        val newLatRad = Math.asin(
            sin(latRad) * cos(distance / R) + cos(latRad) * sin(distance / R) * cos(bearingRad)
        )
        val newLngRad = lngRad + Math.atan2(
            sin(bearingRad) * sin(distance / R) * cos(latRad),
            cos(distance / R) - sin(latRad) * sin(newLatRad)
        )

        SpooferProvider.latitude = Math.toDegrees(newLatRad)
        SpooferProvider.longitude = Math.toDegrees(newLngRad)
        SpooferProvider.startTimestamp = now // 将模拟开始时间重置为当前时间，以避免位置跳跃
        
        if (now - lastConfigSaveTime > 1000) {
            lastConfigSaveTime = now
            lifecycleScope.launch {
                configManager.saveConfig(
                    lat = SpooferProvider.latitude,
                    lng = SpooferProvider.longitude,
                    active = SpooferProvider.isActive,
                    simMode = SpooferProvider.simMode,
                    simBearing = SpooferProvider.simBearing,
                    startTimestamp = SpooferProvider.startTimestamp,
                    wifiJson = SpooferProvider.wifiJson,
                    cellJson = SpooferProvider.cellJson,
                    bluetoothJson = SpooferProvider.bluetoothJson,
                    appCoordinateSystems = settingsRepo.getAppCoordinateSystems(),
                    mockWifi = settingsRepo.mockWifi,
                    mockCell = settingsRepo.mockCell,
                    mockBluetooth = settingsRepo.mockBluetooth,
                    enableJitter = settingsRepo.enableJitter,
                    altitude = settingsRepo.altitude.toDoubleOrNull() ?: 0.0,
                    satelliteCount = settingsRepo.satelliteCount.toIntOrNull() ?: 20
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let {
            windowManager.removeView(it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun JoystickOverlay(
    onMoveWindow: (Float, Float) -> Unit,
    onJoystickChanged: (Double, Float) -> Unit,
    onClose: () -> Unit
) {
    var isDraggingWindow by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(8.dp)
            .background(Color.White.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
            .padding(8.dp)
    ) {
        // Drag Handle
        Row(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(32.dp)
                .background(Color.Gray.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isDraggingWindow = true },
                        onDragEnd = { isDraggingWindow = false },
                        onDragCancel = { isDraggingWindow = false }
                    ) { change, dragAmount ->
                        change.consume()
                        onMoveWindow(dragAmount.x, dragAmount.y)
                    }
                },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    stringResource(com.suseoaa.locationspoofer.R.string.close_joystick)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Joystick
        Joystick(onJoystickChanged = onJoystickChanged)
    }
}

@Composable
fun Joystick(onJoystickChanged: (Double, Float) -> Unit) {
    val maxRadius = 100f
    var thumbOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(
        modifier = Modifier
            .size(120.dp)
            .background(Color.LightGray, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        thumbOffset = androidx.compose.ui.geometry.Offset.Zero
                        onJoystickChanged(0.0, 0f)
                    },
                    onDragCancel = {
                        thumbOffset = androidx.compose.ui.geometry.Offset.Zero
                        onJoystickChanged(0.0, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val newX = thumbOffset.x + dragAmount.x
                    val newY = thumbOffset.y + dragAmount.y
                    val distance = sqrt(newX * newX + newY * newY)

                    if (distance <= maxRadius) {
                        thumbOffset = androidx.compose.ui.geometry.Offset(newX, newY)
                    } else {
                        val ratio = maxRadius / distance
                        thumbOffset =
                            androidx.compose.ui.geometry.Offset(newX * ratio, newY * ratio)
                    }

                    val angle = atan2(thumbOffset.y.toDouble(), thumbOffset.x.toDouble())
                    val intensity =
                        (sqrt(thumbOffset.x * thumbOffset.x + thumbOffset.y * thumbOffset.y) / maxRadius).coerceIn(
                            0f,
                            1f
                        )
                    onJoystickChanged(angle, intensity)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffset.x.roundToInt(), thumbOffset.y.roundToInt()) }
                .size(40.dp)
                .background(Color.DarkGray, CircleShape)
        )
    }
}
