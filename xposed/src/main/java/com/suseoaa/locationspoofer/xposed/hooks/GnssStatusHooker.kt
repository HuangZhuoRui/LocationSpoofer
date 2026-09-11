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

package com.suseoaa.locationspoofer.xposed.hooks

import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.*
import com.suseoaa.locationspoofer.xposed.hooks.*
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
