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
 * GNSS 卫星状态与 NMEA 消息拦截模块
 */
internal fun LocationHooker.hookGnssStatus(classLoader: ClassLoader) {
    try {
        val locationManagerClazz =
            XposedHelpers.findClass("android.location.LocationManager", classLoader)

        // Hook 注册 GpsStatusListener
        try {
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "addGpsStatusListener"
            ) { chain, method ->
                val listener = chain.args[0]
                if (listener != null) {
                    val clazz = listener.javaClass
                    if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                        try {
                            XposedHelpers.hookAllMethods(
                                clazz,
                                "onGpsStatusChanged"
                            ) { innerChain, innerMethod ->
                                return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // Hook 注册 GnssStatusCallback
        try {
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "registerGnssStatusCallback"
            ) { chain, method ->
                var callbackObj: Any? = null
                for (arg in chain.args) {
                    if (arg != null && LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "android.location.GnssStatus\$Callback"
                        )
                    ) {
                        callbackObj = arg
                        break
                    }
                }
                if (callbackObj != null) {
                    val clazz = callbackObj.javaClass
                    if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                        try {
                            XposedHelpers.hookAllMethods(
                                clazz,
                                "onSatelliteStatusChanged"
                            ) { innerChain, innerMethod ->
                                val statusObj = innerChain.args[0]
                                val config = readConfig()
                                if (config != null && config.optBoolean("active", false)) {
                                    val count = config.optInt("satellite_count", 20)
                                    val enableJitter = config.optBoolean("enable_jitter", true)
                                    val spoofedStatus = GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                                        classLoader = clazz.classLoader ?: classLoader,
                                        targetCount = count,
                                        enableJitter = enableJitter,
                                        fallbackOriginalObj = statusObj
                                    )
                                    if (spoofedStatus != null) {
                                        innerChain.args[0] = spoofedStatus
                                    }
                                }
                                return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // Hook 注册 GnssMeasurementsCallback / GnssNavigationMessageCallback：
        // 原始伪距、多普勒频移、导航电文比 Location 更底层，一旦透传真实值出去，
        // 等于把真实卫星几何和真实运动状态原样暴露给 App，而我们又没有可靠办法
        // 伪造出跟模拟坐标/速度物理自洽的原始测量数据（错误的伪造比不投递更容易被识破）。
        // 所以这里不去碰注册方法本身的返回值（它在不同 API 级别有 void/boolean 两种签名，
        // 硬改返回值风险高），而是照搬上面 GnssStatusCallback 的思路：拿到回调实例后
        // 直接 Hook 它自己的 onXxxReceived，命中模拟状态时不调用 proceed，让真实数据在这里被吞掉。
        fun suppressRealCallbackWhenActive(callback: Any, vararg methodNames: String) {
            val clazz = callback.javaClass
            if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return
            for (methodName in methodNames) {
                try {
                    XposedHelpers.hookAllMethods(clazz, methodName) { innerChain, _ ->
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            return@hookAllMethods null
                        }
                        return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                    }
                } catch (_: Throwable) {
                }
            }
        }

        try {
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "registerGnssMeasurementsCallback"
            ) { chain, method ->
                val callback = chain.args.firstOrNull { arg ->
                    arg != null && LocationHooker.hasTypeByName(
                        arg.javaClass,
                        "android.location.GnssMeasurementsEvent\$Callback"
                    )
                }
                if (callback != null) {
                    suppressRealCallbackWhenActive(callback, "onGnssMeasurementsReceived", "onStatusChanged")
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        try {
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "registerGnssNavigationMessageCallback"
            ) { chain, method ->
                val callback = chain.args.firstOrNull { arg ->
                    arg != null && LocationHooker.hasTypeByName(
                        arg.javaClass,
                        "android.location.GnssNavigationMessage\$Callback"
                    )
                }
                if (callback != null) {
                    suppressRealCallbackWhenActive(callback, "onGnssNavigationMessageReceived", "onStatusChanged")
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // Hook LocationManager.getGpsStatus 以适配通过 LocationManager 直接拉取卫星的旧版 SDK
        try {
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "getGpsStatus"
            ) { chain, method ->
                val result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val count = config.optInt("satellite_count", 20)
                    val statusObj = result ?: chain.args.firstOrNull()
                    if (statusObj != null) {
                        try {
                            val satellites = GnssFastMockEngine.getOrCreateSpoofedGpsSatellites(classLoader, count)
                            XposedHelpers.setObjectField(statusObj, "mSatellites", satellites)
                        } catch (_: Throwable) {}
                    }
                }
                return@hookAllMethods result
            }
        } catch (_: Throwable) {
        }

        // Hook GpsStatus.getSatellites() 以适配像 DevCheck 这样的旧应用
        try {
            XposedHelpers.hookMethod(
                "android.location.GpsStatus",
                classLoader,
                "getSatellites"
            ) { chain, method ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val count = config.optInt("satellite_count", 20)
                    return@hookMethod GnssFastMockEngine.getOrCreateSpoofedGpsSatellites(classLoader, count)
                }
                return@hookMethod chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        XposedBridge.log("[LocationSpoofer] GnssStatus hooks installed (High-Performance Engine)")
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer] GnssStatus hook failed: $e")
    }

    readConfig()
}

internal fun LocationHooker.createSpoofedGpsSatellites(classLoader: ClassLoader): Iterable<Any> {
    val config = readConfig()
    val count = config?.optInt("satellite_count", 20) ?: 20
    return GnssFastMockEngine.getOrCreateSpoofedGpsSatellites(classLoader, count)
}

// GNSS 卫星矩阵数据结构（预留，暂无调用方）
data class SatelliteData(
    val svid: Int,
    val type: Int, // 1=GPS, 3=GLONASS, 5=BDS
    val elevation: Float,
    val azimuth: Float,
    val cn0: Float,
    val usedInFix: Boolean
)
