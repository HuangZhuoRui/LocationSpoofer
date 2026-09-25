@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.vincenthzr.locationspoofer.xposed.hooks

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.*
import com.vincenthzr.locationspoofer.xposed.hooks.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.*
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * 基础定位框架拦截模块 (Base Location Hooker)
 * 
 * 上下文:
 * Android 系统的原生定位服务由 `android.location.LocationManager` 提供，包含 GPS 卫星状态 (GnssStatus)、
 * NMEA 报文 (底层 GPS 硬件输出的数据格式) 以及基础坐标。
 * 
 * 作用:
 * 本模块是位置伪造的核心，直接 Hook Android 原生系统的底层接口，从而对所有的 App 生效。
 * 关键部分解释:
 * 1. hookLocationAPIs: 拦截 LocationManager 的 requestLocationUpdates 等方法，不仅伪造坐标，
 *    还会使用 Ornstein-Uhlenbeck 随机过程增加自然的坐标抖动，并清除 Location 对象的 `isFromMockProvider` 标志，
 *    这是绕过绝大多数检测的关键。
 * 2. hookGnssStatus / createSpoofedGpsSatellites: 伪造卫星数据。如果只伪造坐标但不伪造天上的卫星，
 *    高德/百度SDK会轻易发现异常（坐标在变但搜不到卫星）。这里动态生成了一套信噪比(SNR)和仰角逼真的卫星阵列。
 * 3. NMEA 拦截: NMEA-0183 报文是底层 GPS 芯片吐出的串口数据，高级的地图 SDK 会直接解析 NMEA 而非 Location 对象，
 *    所以我们必须在此处把伪造的经纬度按照 NMEA 格式 (GPGGA, GPRMC 等) 重新编码并注入。
 */


internal fun LocationHooker.getCurrentSpoofedMotion(defaultSystem: String = "GCJ-02"): SpoofedMotion? {
    val config = readConfig() ?: return null
    if (!config.optBoolean("active", false)) return null

    val rawMotion = RouteEngine.calculateCurrentPosition(config)
    val finalCoords = getAppTargetCoordinate(rawMotion.lat, rawMotion.lng, config, defaultSystem)
    val jittered = getJitteredLocation(finalCoords.first, finalCoords.second)
    return SpoofedMotion(jittered.first, jittered.second, rawMotion.bearing, rawMotion.speed)
}

internal fun LocationHooker.hookLocationAPIs(classLoader: ClassLoader, currentPkg: String) {
    hookLocationGetters(classLoader, currentPkg)
    hookLocationProviders(classLoader, currentPkg)

    // 第三方地图SDK深度Hook(高德/腾讯/百度)
    hookAllMapSdks(classLoader)
}
