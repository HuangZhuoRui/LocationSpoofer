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
 * android.location.Location 对象各字段 getter 的伪造（坐标/精度/速度/航向/海拔/时间戳/mock 标志位等）。
 */
internal fun LocationHooker.hookLocationGetters(classLoader: ClassLoader, currentPkg: String) {
    try {
        // android.location.Location 标准接口: 默认输出 GCJ-02 坐标（与国内地图与考勤SDK保持一致，彻底消除500米偏移）
        // 若应用需 WGS-84（如 Google Maps / OSM）可在「自定义坐标算法」中指定其目标坐标系。
        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "getLatitude"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            if (currentPkg.substringBefore(":") == "com.vincenthzr.locationspoofer") return@hookMethod result
            val thisObj = chain.thisObject
            val defaultSys = when {
                thisObj != null && (thisObj.javaClass.name.contains("amap", ignoreCase = true) || thisObj.javaClass.name.contains("autonavi", ignoreCase = true)) -> "GCJ-02"
                thisObj != null && thisObj.javaClass.name.contains("baidu", ignoreCase = true) -> "BD-09"
                thisObj != null && thisObj.javaClass.name.contains("tencent", ignoreCase = true) -> "GCJ-02"
                else -> {
                    val provider = try { (thisObj as? android.location.Location)?.provider } catch (_: Throwable) { null }
                    when (provider?.lowercase()) {
                        "baidu" -> "BD-09"
                        else -> "WGS-84"
                    }
                }
            }
            val motion = getCurrentSpoofedMotion(defaultSys)
            if (motion != null) {
                result = motion.lat
            }

            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "getLongitude"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            if (currentPkg.substringBefore(":") == "com.vincenthzr.locationspoofer") return@hookMethod result
            val thisObj = chain.thisObject
            val defaultSys = when {
                thisObj != null && (thisObj.javaClass.name.contains("amap", ignoreCase = true) || thisObj.javaClass.name.contains("autonavi", ignoreCase = true)) -> "GCJ-02"
                thisObj != null && thisObj.javaClass.name.contains("baidu", ignoreCase = true) -> "BD-09"
                thisObj != null && thisObj.javaClass.name.contains("tencent", ignoreCase = true) -> "GCJ-02"
                else -> {
                    val provider = try { (thisObj as? android.location.Location)?.provider } catch (_: Throwable) { null }
                    when (provider?.lowercase()) {
                        "baidu" -> "BD-09"
                        else -> "WGS-84"
                    }
                }
            }
            val motion = getCurrentSpoofedMotion(defaultSys)
            if (motion != null) {
                result = motion.lng
            }

            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "getAccuracy"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            if (currentPkg.substringBefore(":") == "com.vincenthzr.locationspoofer") return@hookMethod result
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = getJitteredAccuracy()
            }

            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "hasAccuracy"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = true
            }
            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "getSpeed"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            if (currentPkg.substringBefore(":") == "com.vincenthzr.locationspoofer") return@hookMethod result
            val motion = getCurrentSpoofedMotion()
            if (motion != null) {
                result = motion.speed
            }

            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "hasSpeed"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = true
            }
            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "getBearing"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            if (currentPkg.substringBefore(":") == "com.vincenthzr.locationspoofer") return@hookMethod result
            val motion = getCurrentSpoofedMotion()
            if (motion != null) {
                result = motion.bearing
            }

            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "hasBearing"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = true
            }
            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "getAltitude"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val baseAlt = RouteEngine.realisticAltitude(config)
                val enableJitter = config.optBoolean("enable_jitter", true)
                result = if (enableJitter && baseAlt > 0.0) {
                    // 稍微抖动海拔，真实气压计存在起伏，±0.5米
                    baseAlt + (rng.nextDouble() - 0.5)
                } else {
                    baseAlt
                }
            }

            return@hookMethod result
        }

        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "hasAltitude"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = true
            }
            return@hookMethod result
        }

        // Android 8.0+ 速度、航向、垂直精度扩展接口
        try {
            XposedHelpers.hookMethod("android.location.Location", classLoader, "getSpeedAccuracyMetersPerSecond") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) 0.1f else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookMethod("android.location.Location", classLoader, "hasSpeedAccuracy") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookMethod("android.location.Location", classLoader, "getBearingAccuracyDegrees") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) 1.5f else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookMethod("android.location.Location", classLoader, "hasBearingAccuracy") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookMethod("android.location.Location", classLoader, "getVerticalAccuracyMeters") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) 1.2f else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookMethod("android.location.Location", classLoader, "hasVerticalAccuracy") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        // 动态注入最新时间戳，防止运动软件（Keep/悦跑圈/高德等）因时间戳陈旧判定为丢弃点
        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "getTime"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                result = System.currentTimeMillis()
            }
            return@hookMethod result
        }

        try {
            XposedHelpers.hookMethod(
                "android.location.Location",
                classLoader,
                "getElapsedRealtimeNanos"
            ) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    result = android.os.SystemClock.elapsedRealtimeNanos()
                }
                return@hookMethod result
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookMethod(
                "android.location.Location",
                classLoader,
                "getElapsedRealtimeMillis"
            ) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    result = android.os.SystemClock.elapsedRealtime()
                }
                return@hookMethod result
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookMethod(
                "android.location.Location",
                classLoader,
                "getElapsedRealtimeAgeMillis"
            ) { chain, method ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") 0L else chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        // ★ 拦截 getExtras：注入真实的卫星与定位元数据，防止百度/高德因卫星数为0判定为无定位信号丢弃坐标
        try {
            XposedHelpers.hookMethod(
                "android.location.Location",
                classLoader,
                "getExtras"
            ) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    val bundle = (result as? android.os.Bundle) ?: android.os.Bundle()
                    val satCount = config.optInt("satellite_count", 20)
                    bundle.putInt("satellites", satCount)
                    bundle.putInt("satellites_in_view", satCount)
                    bundle.putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                    bundle.putInt("satellites_visible", satCount)
                    bundle.putBoolean("mockLocation", false)
                    result = bundle
                }
                return@hookMethod result
            }
        } catch (_: Throwable) {}

        // 抹除 isFromMockProvider 标志位（strategy:100 的根本来源）

        // Android 6~11: isFromMockProvider()
        XposedHelpers.hookMethod(
            "android.location.Location",
            classLoader,
            "isFromMockProvider"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = false
            }

            return@hookMethod result
        }
        // Android 12+: isMock()
        try {
            XposedHelpers.hookMethod(
                "android.location.Location",
                classLoader,
                "isMock"
            ) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())

                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = false
                }

                return@hookMethod result
            }
        } catch (e: Throwable) { /* API < 31 的系统没有此方法 */
        }


        // ★ 拦截 getProvider：将 "mock" / "test" 提供者名隐藏，换成 "gps"
        XposedHelpers.hookMethod(
            "android.location.Location", classLoader, "getProvider"
        ) { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())

            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val provider = result as? String ?: return@hookMethod result
                if (provider.contains("mock", ignoreCase = true) ||
                    provider.contains("test", ignoreCase = true) ||
                    provider.contains("fake", ignoreCase = true)
                ) {
                    result = android.location.LocationManager.GPS_PROVIDER
                }
            }
            return@hookMethod result
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }
}
