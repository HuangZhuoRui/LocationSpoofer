package com.suseoaa.locationspoofer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.suseoaa.locationspoofer.data.repository.LocationRepository
import com.suseoaa.locationspoofer.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 供 Tasker/MacroDroid 等自动化 App 通过发送广播开启/关闭模拟定位：
 * - com.suseoaa.locationspoofer.START（附带 int extra "favorite"：收藏列表里的索引，从 0 开始）
 * - com.suseoaa.locationspoofer.STOP
 * 只能从用户自己已保存的收藏点里选一个启动，不接受外部传入的任意坐标。
 */
class SpoofingControlReceiver : BroadcastReceiver(), KoinComponent {
    private val locationRepository: LocationRepository by inject()
    private val settingsRepository: SettingsRepository by inject()

    companion object {
        const val ACTION_START = "com.suseoaa.locationspoofer.START"
        const val ACTION_STOP = "com.suseoaa.locationspoofer.STOP"
        const val EXTRA_FAVORITE = "favorite"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_START -> handleStart(context, intent)
                    ACTION_STOP -> handleStop(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleStart(context: Context, intent: Intent) {
        val index = intent.getIntExtra(EXTRA_FAVORITE, -1)
        val loc = settingsRepository.getSavedLocations().getOrNull(index) ?: return

        val now = System.currentTimeMillis()
        locationRepository.startSpoofing(
            context, loc.lat, loc.lng,
            "STILL", 0f, now,
            emptyList(), false,
            settingsRepository.getAppCoordinateSystems(),
            loc.wifiJson, loc.cellJson, loc.bluetoothJson,
            settingsRepository.mockWifi, settingsRepository.mockCell, settingsRepository.mockBluetooth,
            settingsRepository.enableJitter
        )
        settingsRepository.isSpoofingActive = true
        settingsRepository.lastSpoofedLat = loc.lat.toString()
        settingsRepository.lastSpoofedLng = loc.lng.toString()
    }

    private suspend fun handleStop(context: Context) {
        settingsRepository.isSpoofingActive = false
        locationRepository.stopSpoofing(context)
    }
}
