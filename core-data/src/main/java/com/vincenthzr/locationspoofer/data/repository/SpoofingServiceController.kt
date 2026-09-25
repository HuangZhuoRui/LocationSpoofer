package com.vincenthzr.locationspoofer.data.repository

import android.content.Context

/**
 * 对前台模拟服务的抽象。LocationRepository 所在的 :core-data 模块不能直接依赖
 * :service 模块（会与 service -> core-data 的依赖方向形成循环），
 * 因此把"启停模拟服务"这件事声明成接口，具体实现放在 :service 模块里，
 * 通过 Koin 注入进来。
 */
interface SpoofingServiceController {
    val isRunning: Boolean
    fun startForeground(context: Context, lat: Double, lng: Double)
    fun stop(context: Context)

    /** 悬浮摇杆当前是否显示 */
    val isFloatingJoystickShowing: Boolean

    /** 显示 / 关闭悬浮摇杆；调用方需先确认已获得悬浮窗权限 */
    fun setFloatingJoystickVisible(context: Context, visible: Boolean)
}
