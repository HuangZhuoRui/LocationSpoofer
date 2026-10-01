package com.vincenthzr.locationspoofer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vincenthzr.locationspoofer.data.repository.LocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 在设备重启 / App 更新广播中恢复保活和模拟状态，不依赖 Activity/ViewModel。
 * 最新配置由 ConfigManager 在框架连接后自动发布。
 */
class BootCompletedReceiver : BroadcastReceiver(), KoinComponent {
    private val locationRepository: LocationRepository by inject()

    companion object {
        private const val TAG = "LocationSpoofer"

        // BOOT_COMPLETED/MY_PACKAGE_REPLACED 允许从后台调用 startForegroundService()
        // 的豁免窗口较短，必须确保整条 su 链路在窗口内完成；超时后仍要保证
        // pendingResult.finish() 被调用，避免广播被系统判定超时或 PendingResult 泄漏。
        private const val RECOVERY_TIMEOUT_MS = 8_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val appContext = context.applicationContext
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val hasRoot = withTimeoutOrNull(RECOVERY_TIMEOUT_MS) {
                            locationRepository.recoverAfterBoot(appContext)
                        }
                        android.util.Log.i(
                            TAG,
                            "BootCompletedReceiver: action=${intent.action}, recoverAfterBoot 结果 hasRoot=${hasRoot ?: "TIMEOUT"}"
                        )
                    } catch (e: Throwable) {
                        android.util.Log.w(TAG, "BootCompletedReceiver: 恢复流程异常", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
