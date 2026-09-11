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
 * 在地图 SDK 的 Location 对象中注入地址/城市/省份等通用模拟值。
 * 当 WiFi/基站被模拟时，地图 SDK 的云端逆地理编码通常会失败，导致这些字段为空。
 * 返回 null 会导致目标 App (如钉钉、微信) 报“获取位置失败”。
 */
internal fun LocationHooker.hookAddressFields(clazz: Class<*>, classLoader: ClassLoader) {
    val stringMethods = arrayOf(
        "getCity",
        "getProvince",
        "getDistrict",
        "getAddress",
        "getAddrStr",
        "getCountry",
        "getNation",
        "getStreet",
        "getStreetNum",
        "getStreetNumber",
        "getStreetNo",
        "getCityCode",
        "getAdCode",
        "getPoiName",
        "getAoiName",
        "getTown",
        "getVillage",
        "getLocationDescribe"
    )
    for (methodName in stringMethods) {
        try {
            XposedHelpers.hookAllMethods(clazz, methodName) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                if (method !is Method) return@hookAllMethods result
                if (method.returnType != String::class.java) return@hookAllMethods result

                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val res = result as? String
                    if (res.isNullOrEmpty() || res.contains("Unknown", ignoreCase = true)) {
                        result = when (method.name) {
                            "getCity" -> cachedCity
                            "getProvince" -> cachedProvince
                            "getDistrict" -> cachedDistrict
                            "getAddress", "getAddrStr" -> cachedAddress
                            "getCountry", "getNation" -> cachedCountry
                            "getStreet" -> cachedStreet
                            "getStreetNum", "getStreetNumber", "getStreetNo" -> cachedStreetNum
                            "getCityCode" -> ""
                            "getAdCode" -> ""
                            "getPoiName" -> cachedPoiName
                            "getAoiName" -> cachedPoiName
                            "getTown" -> ""
                            "getVillage" -> ""
                            "getLocationDescribe" -> "在${cachedPoiName}附近"
                            else -> ""
                        }
                    }
                }
                return@hookAllMethods result
            }
        } catch (e: Throwable) { /* ignore */
        }
    }
}

/**
 * Google Play 服务定位 (FusedLocationProviderClient) 专项拦截。
 *
 * 上下文:
 * 除了国内几家地图 SDK，很多 App（包括 WebView 里跑的 H5 定位）实际用的是 Google Play 服务的
 * `FusedLocationProviderClient`，而不是原生 `LocationManager`。这条路径之前完全没有被 Hook 到。
 *
 * 做法跟 `capturedLocationListeners`（原生 LocationListener）完全一致：
 * 1. Hook `requestLocationUpdates`，捕获 `LocationCallback` 实例并直接 Hook 它自己的 `onLocationResult`，
 *    改写 `LocationResult` 里包着的 `List<Location>`。
 * 2. Hook `removeLocationUpdates`，注销时移出捕获列表。
 * 3. `getLastLocation()`/`getCurrentLocation()` 返回的是异步 `Task<Location>`，改在通用的
 *    `OnSuccessListener.onSuccess` 上按结果类型过滤——只在结果确实是 `Location` 时才改写，
 *    不影响 Play 服务其它 `Task<T>`（登录、支付等）的正常回调。
 */
internal fun LocationHooker.hookGoogleFusedLocation(classLoader: ClassLoader) {
    try {
        val clientClazz = XposedHelpers.findClass(
            "com.google.android.gms.location.FusedLocationProviderClient", classLoader
        )

        XposedHelpers.hookAllMethods(clientClazz, "requestLocationUpdates") { chain, _ ->
            for (arg in chain.args) {
                if (arg == null) continue
                if (!LocationHooker.hasTypeByName(
                        arg.javaClass,
                        "com.google.android.gms.location.LocationCallback"
                    )
                ) continue
                capturedFusedLocationCallbacks.addIfAbsent(arg)
                val callbackClazz = arg.javaClass
                if (hookedCallbackClasses.putIfAbsent(callbackClazz, true) == null) {
                    try {
                        XposedHelpers.hookAllMethods(callbackClazz, "onLocationResult") { lChain, _ ->
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false) &&
                                currentPackageName.substringBefore(":") != "com.suseoaa.locationspoofer"
                            ) {
                                val resultArg = lChain.args.getOrNull(0)
                                if (resultArg != null) {
                                    try {
                                        @Suppress("UNCHECKED_CAST")
                                        val locations =
                                            XposedHelpers.callMethod(resultArg, "getLocations") as? List<Any?>
                                        locations?.forEach { loc ->
                                            if (loc is android.location.Location) {
                                                val motion = getCurrentSpoofedMotion("WGS-84")
                                                if (motion != null) {
                                                    loc.latitude = motion.lat
                                                    loc.longitude = motion.lng
                                                    loc.accuracy = getJitteredAccuracy()
                                                    loc.speed = motion.speed
                                                    loc.bearing = motion.bearing
                                                    loc.time = System.currentTimeMillis()
                                                    loc.elapsedRealtimeNanos =
                                                        android.os.SystemClock.elapsedRealtimeNanos()
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }
                            return@hookAllMethods lChain.proceed(lChain.args.toTypedArray())
                        }
                    } catch (_: Throwable) {}
                }
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }

        XposedHelpers.hookAllMethods(clientClazz, "removeLocationUpdates") { chain, _ ->
            for (arg in chain.args) {
                if (arg != null) capturedFusedLocationCallbacks.remove(arg)
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }

        try {
            val onSuccessListenerClazz = XposedHelpers.findClass(
                "com.google.android.gms.tasks.OnSuccessListener", classLoader
            )
            XposedHelpers.hookAllMethods(onSuccessListenerClazz, "onSuccess") { chain, _ ->
                val result = chain.args.getOrNull(0)
                if (result is android.location.Location) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false) &&
                        currentPackageName.substringBefore(":") != "com.suseoaa.locationspoofer"
                    ) {
                        val motion = getCurrentSpoofedMotion("WGS-84")
                        if (motion != null) {
                            result.latitude = motion.lat
                            result.longitude = motion.lng
                            result.accuracy = getJitteredAccuracy()
                            result.time = System.currentTimeMillis()
                            result.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        XposedBridge.log("[LocationSpoofer] Google FusedLocationProvider hooks installed")
    } catch (_: Throwable) {
        XposedBridge.log("[LocationSpoofer] Google Play Services location not found, skipped")
    }
}

// Wi-Fi 环境伪造 — 覆盖 WifiInfo / WifiManager / NetworkInfo
