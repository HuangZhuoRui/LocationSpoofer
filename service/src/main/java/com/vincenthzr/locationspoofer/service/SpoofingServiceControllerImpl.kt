package com.vincenthzr.locationspoofer.service

import android.content.Context
import android.content.Intent
import com.vincenthzr.locationspoofer.data.repository.SpoofingServiceController

class SpoofingServiceControllerImpl : SpoofingServiceController {
    override val isRunning: Boolean
        get() = SpoofingService.isRunning

    override fun startForeground(context: Context, lat: Double, lng: Double) {
        context.startForegroundService(
            Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_START
                putExtra(SpoofingService.EXTRA_LAT, lat)
                putExtra(SpoofingService.EXTRA_LNG, lng)
            }
        )
    }

    override fun stop(context: Context) {
        try {
            context.stopService(Intent(context, SpoofingService::class.java))
        } catch (e: Throwable) {
        }
        try {
            context.startService(Intent(context, SpoofingService::class.java).apply {
                action = SpoofingService.ACTION_STOP
            })
        } catch (e: Throwable) {
        }
    }

    override val isFloatingJoystickShowing: Boolean
        get() = FloatingJoystickService.isShowing

    override fun setFloatingJoystickVisible(context: Context, visible: Boolean) {
        val intent = Intent(context, FloatingJoystickService::class.java)
        if (visible) context.startService(intent) else context.stopService(intent)
    }
}
