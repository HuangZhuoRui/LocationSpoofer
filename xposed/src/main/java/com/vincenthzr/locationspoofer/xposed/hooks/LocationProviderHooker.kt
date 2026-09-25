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
 * android.location.LocationManager 层面的伪造：provider 列表清洗、NMEA 监听器注入、
 * requestLocationUpdates/getCurrentLocation/getLastKnownLocation 主动派发假位置。
 */
internal fun LocationHooker.hookLocationProviders(classLoader: ClassLoader, currentPkg: String) {
    try {
        // ★ 拦截 LocationManager.getProviders() / getAllProviders()：移除 mock/test 提供者

        try {
            XposedHelpers.hookMethod(
                "android.location.LocationManager", classLoader, "getProviders",
                Boolean::class.javaPrimitiveType!!
            ) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())

                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    @Suppress("UNCHECKED_CAST")
                    val list = result as? MutableList<String> ?: return@hookMethod result
                    val cleaned = list.filterNot {
                        it.contains("mock", ignoreCase = true) ||
                                it.contains("test", ignoreCase = true) ||
                                it.contains("fake", ignoreCase = true)
                    }.toMutableList()
                    if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER))
                        cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                    result = cleaned
                }

                return@hookMethod result
            }
            XposedHelpers.hookMethod(
                "android.location.LocationManager", classLoader, "getAllProviders"
            ) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())

                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    @Suppress("UNCHECKED_CAST")
                    val list = result as? MutableList<String> ?: return@hookMethod result
                    val cleaned = list.filterNot {
                        it.contains("mock", ignoreCase = true) ||
                                it.contains("test", ignoreCase = true) ||
                                it.contains("fake", ignoreCase = true)
                    }.toMutableList()
                    if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER))
                        cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                    result = cleaned
                }

                return@hookMethod result
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // ★ NMEA-0183 报文劫持
        try {

            val locationManagerClazz =
                XposedHelpers.findClass("android.location.LocationManager", classLoader)
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "addNmeaListener"
            ) { chain, method ->

                // 强制启动代理注入，无论当前active是true还是false。
                // 真正的状态校验在代理注入器的Timer中进行。

                val args = chain.args
                for (i in args.indices) {
                    val arg = args[i] ?: continue

                    // 检查它是否实现了 OnNmeaMessageListener
                    val isOnNmea = try {
                        LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "android.location.OnNmeaMessageListener"
                        )
                    } catch (e: Exception) {
                        false
                    }

                    // 检查它是否实现了 GpsStatus.NmeaListener
                    val isGpsNmea = try {
                        LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "android.location.GpsStatus\$NmeaListener"
                        )
                    } catch (e: Exception) {
                        false
                    }

                    if (isOnNmea) {
                        XposedBridge.log("[GPS_Spoofer] Detected addNmeaListener(OnNmeaMessageListener)! Starting active injector.")
                        args[i] = createOnNmeaMessageListenerProxy(arg, classLoader)
                    } else if (isGpsNmea) {
                        XposedBridge.log("[GPS_Spoofer] Detected addNmeaListener(GpsStatus.NmeaListener)! Starting active injector.")
                        args[i] = createGpsStatusNmeaListenerProxy(arg, classLoader)
                    }
                }

                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            // Hook 取消注册 NmeaListener
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "removeNmeaListener"
            ) { chain, method ->

                for (arg in chain.args) {
                    if (arg != null) {
                        nmeaTimers.remove(arg)?.cancel()
                        XposedBridge.log("[GPS_Spoofer] removeNmeaListener called, canceled timer.")
                    }
                }

                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // ★ 捕获 LocationListener 和 Consumer 以便主动注入模拟位置，并直接Hook其回调方法
        try {
            val locationManagerClazz =
                XposedHelpers.findClass("android.location.LocationManager", classLoader)
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "requestLocationUpdates"
            ) { chain, method ->

                for (arg in chain.args) {
                    if (arg == null) continue
                    val className = arg.javaClass.name
                    if (className == "java.lang.String" || className == "android.os.Looper" || className == "android.location.Criteria" || className == "android.location.LocationRequest") continue

                    try {
                        val isExcluded = LocationHooker.hasTypeByName(arg.javaClass, "com.amap.api.location.AMapLocationListener") ||
                                LocationHooker.hasTypeByName(arg.javaClass, "com.baidu.location.BDLocationListener") ||
                                LocationHooker.hasTypeByName(arg.javaClass, "com.baidu.location.BDAbstractLocationListener") ||
                                LocationHooker.hasTypeByName(arg.javaClass, "com.tencent.map.geolocation.TencentLocationListener")

                        val isListener = !isExcluded && (LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "android.location.LocationListener"
                        ) || LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "java.util.function.Consumer"
                        ) || LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "androidx.core.util.Consumer"
                        ))

                        if (isListener) {
                            capturedLocationListeners.addIfAbsent(arg)

                            val listenerClazz = arg.javaClass
                            if (hookedCallbackClasses.putIfAbsent(listenerClazz, true) == null) {
                                try {
                                    XposedHelpers.hookAllMethods(listenerClazz, "onLocationChanged") { lChain, _ ->
                                        val config = readConfig()
                                        if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                                            if (lChain.args.isNotEmpty()) {
                                                val firstArg = lChain.args[0]
                                                if (firstArg != null) {
                                                    if (firstArg is android.location.Location) {
                                                        val defaultSys = when {
                                                            firstArg.javaClass.name.contains("amap", ignoreCase = true) || firstArg.javaClass.name.contains("autonavi", ignoreCase = true) -> "GCJ-02"
                                                            firstArg.javaClass.name.contains("baidu", ignoreCase = true) -> "BD-09"
                                                            firstArg.javaClass.name.contains("tencent", ignoreCase = true) -> "GCJ-02"
                                                            else -> {
                                                                val provider = try { firstArg.provider } catch (_: Throwable) { null }
                                                                when (provider?.lowercase()) {
                                                                    "baidu" -> "BD-09"
                                                                    else -> "GCJ-02"
                                                                }
                                                            }
                                                        }
                                                        val motion = getCurrentSpoofedMotion(defaultSys)
                                                        if (motion != null) {
                                                            firstArg.latitude = motion.lat
                                                            firstArg.longitude = motion.lng
                                                            firstArg.accuracy = getJitteredAccuracy()
                                                            firstArg.speed = motion.speed
                                                            firstArg.bearing = motion.bearing
                                                            firstArg.altitude = config.optDouble("altitude", 25.0)
                                                            firstArg.time = System.currentTimeMillis()
                                                            firstArg.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                            try {
                                                                val extras = firstArg.extras ?: android.os.Bundle()
                                                                val satCount = config.optInt("satellite_count", 20)
                                                                extras.putInt("satellites", satCount)
                                                                extras.putInt("satellites_in_view", satCount)
                                                                extras.putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                                                                extras.putBoolean("mockLocation", false)
                                                                firstArg.extras = extras
                                                            } catch (_: Throwable) {}
                                                        }
                                                    } else if (firstArg is List<*>) {
                                                        for (loc in firstArg) {
                                                            if (loc is android.location.Location) {
                                                                val defaultSys = when {
                                                                    loc.javaClass.name.contains("amap", ignoreCase = true) || loc.javaClass.name.contains("autonavi", ignoreCase = true) -> "GCJ-02"
                                                                    loc.javaClass.name.contains("baidu", ignoreCase = true) -> "BD-09"
                                                                    loc.javaClass.name.contains("tencent", ignoreCase = true) -> "GCJ-02"
                                                                    else -> {
                                                                        val provider = try { loc.provider } catch (_: Throwable) { null }
                                                                        when (provider?.lowercase()) {
                                                                            "baidu" -> "BD-09"
                                                                            else -> "GCJ-02"
                                                                        }
                                                                    }
                                                                }
                                                                val motion = getCurrentSpoofedMotion(defaultSys)
                                                                if (motion != null) {
                                                                    loc.latitude = motion.lat
                                                                    loc.longitude = motion.lng
                                                                    loc.accuracy = getJitteredAccuracy()
                                                                    loc.speed = motion.speed
                                                                    loc.bearing = motion.bearing
                                                                    loc.altitude = config.optDouble("altitude", 25.0)
                                                                    loc.time = System.currentTimeMillis()
                                                                    loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                                                    try {
                                                                        val extras = loc.extras ?: android.os.Bundle()
                                                                        val satCount = config.optInt("satellite_count", 20)
                                                                        extras.putInt("satellites", satCount)
                                                                        extras.putInt("satellites_in_view", satCount)
                                                                        extras.putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                                                                        extras.putInt("satellites_visible", satCount)
                                                                        extras.putBoolean("mockLocation", false)
                                                                        loc.extras = extras
                                                                    } catch (_: Throwable) {}
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        return@hookAllMethods lChain.proceed(lChain.args.toTypedArray())
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (e: Throwable) {
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "removeUpdates"
            ) { chain, method ->

                for (arg in chain.args) {
                    if (arg == null) continue
                    try {
                        capturedLocationListeners.remove(arg)
                    } catch (e: Throwable) {
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            // ★ Hook LocationManager.getCurrentLocation (Android 11+)
            XposedHelpers.hookAllMethods(
                locationManagerClazz,
                "getCurrentLocation"
            ) { chain, method ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    val provider = chain.args.getOrNull(0) as? String ?: "gps"
                    val defaultSys = when (provider.lowercase()) {
                        "baidu" -> "BD-09"
                        else -> "GCJ-02"
                    }
                    val motion = getCurrentSpoofedMotion(defaultSys)
                    if (motion != null) {
                        var consumerArg: Any? = null
                        var executorArg: java.util.concurrent.Executor? = null
                        for (arg in chain.args) {
                            if (arg is java.util.concurrent.Executor) {
                                executorArg = arg
                            } else if (arg != null && (arg.javaClass.name.contains("Consumer") || LocationHooker.hasTypeByName(arg.javaClass, "java.util.function.Consumer") || LocationHooker.hasTypeByName(arg.javaClass, "androidx.core.util.Consumer"))) {
                                consumerArg = arg
                            }
                        }
                        if (consumerArg != null) {
                            val timeNow = System.currentTimeMillis()
                            val locClass = Class.forName("android.location.Location", false, classLoader)
                            val fakeLoc = locClass.getConstructor(String::class.java).newInstance(if (provider.isNotBlank()) provider else android.location.LocationManager.GPS_PROVIDER)
                            XposedHelpers.callMethod(fakeLoc, "setLatitude", motion.lat)
                            XposedHelpers.callMethod(fakeLoc, "setLongitude", motion.lng)
                            XposedHelpers.callMethod(fakeLoc, "setAccuracy", getJitteredAccuracy())
                            XposedHelpers.callMethod(fakeLoc, "setSpeed", motion.speed)
                            XposedHelpers.callMethod(fakeLoc, "setBearing", motion.bearing)
                            XposedHelpers.callMethod(fakeLoc, "setAltitude", config.optDouble("altitude", 25.0))
                            XposedHelpers.callMethod(fakeLoc, "setTime", timeNow)
                            XposedHelpers.callMethod(fakeLoc, "setElapsedRealtimeNanos", android.os.SystemClock.elapsedRealtimeNanos())
                            try {
                                val extras = android.os.Bundle().apply {
                                    val satCount = config.optInt("satellite_count", 20)
                                    putInt("satellites", satCount)
                                    putInt("satellites_in_view", satCount)
                                    putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                                    putInt("satellites_visible", satCount)
                                    putBoolean("mockLocation", false)
                                }
                                XposedHelpers.callMethod(fakeLoc, "setExtras", extras)
                            } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(fakeLoc, "setIsFromMockProvider", false) } catch (_: Throwable) {}

                            val runDispatch = Runnable {
                                try {
                                    XposedHelpers.callMethod(consumerArg, "accept", fakeLoc)
                                } catch (_: Throwable) {}
                            }
                            if (executorArg != null) {
                                executorArg.execute(runDispatch)
                            } else {
                                android.os.Handler(android.os.Looper.getMainLooper()).post(runDispatch)
                            }
                            return@hookAllMethods null
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] Failed to hook requestLocationUpdates/getCurrentLocation: $e")
        }

        // getLastKnownLocation: 立即返回伪造的位置，修复“需要多次尝试才能定位”的问题
        try {
            val locationManagerClazz2 =
                XposedHelpers.findClass("android.location.LocationManager", classLoader)
            XposedHelpers.hookAllMethods(
                locationManagerClazz2,
                "getLastKnownLocation"
            ) { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())

                // 宿主 App 自身需要获取真实位置（"定位到当前位置"功能）
                // 不拦截宿主 App 的 getLastKnownLocation，只拦截目标 App 的
                val hostPkg = currentPkg.substringBefore(":")
                if (hostPkg == "com.vincenthzr.locationspoofer") return@hookAllMethods result

                val config = readConfig()
                if (config == null || !config.optBoolean(
                        "active",
                        false
                    )
                ) return@hookAllMethods result
                val provider = chain.args.getOrNull(0) as? String ?: "gps"
                val defaultSys = when (provider.lowercase()) {
                    "baidu" -> "BD-09"
                    else -> "GCJ-02"
                }
                val motion = getCurrentSpoofedMotion(defaultSys) ?: return@hookAllMethods result
                try {
                    val locClass = Class.forName("android.location.Location", false, classLoader)
                    val fakeLoc = locClass.getConstructor(String::class.java)
                        .newInstance(if (provider.isNotBlank()) provider else android.location.LocationManager.GPS_PROVIDER)
                    XposedHelpers.callMethod(fakeLoc, "setLatitude", motion.lat)
                    XposedHelpers.callMethod(fakeLoc, "setLongitude", motion.lng)
                    XposedHelpers.callMethod(fakeLoc, "setAccuracy", getJitteredAccuracy())
                    XposedHelpers.callMethod(fakeLoc, "setSpeed", motion.speed)
                    XposedHelpers.callMethod(fakeLoc, "setBearing", motion.bearing)
                    XposedHelpers.callMethod(fakeLoc, "setAltitude", config.optDouble("altitude", 25.0))
                    XposedHelpers.callMethod(fakeLoc, "setTime", System.currentTimeMillis())
                    XposedHelpers.callMethod(
                        fakeLoc, "setElapsedRealtimeNanos",
                        android.os.SystemClock.elapsedRealtimeNanos()
                    )
                    try {
                        val extras = android.os.Bundle().apply {
                            val satCount = config.optInt("satellite_count", 20)
                            putInt("satellites", satCount)
                            putInt("satellites_in_view", satCount)
                            putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                            putInt("satellites_visible", satCount)
                            putBoolean("mockLocation", false)
                        }
                        XposedHelpers.callMethod(fakeLoc, "setExtras", extras)
                    } catch (_: Throwable) {}
                    try {
                        XposedHelpers.callMethod(fakeLoc, "setIsFromMockProvider", false)
                    } catch (_: Throwable) {
                    }
                    result = fakeLoc
                } catch (e2: Throwable) {
                    XposedBridge.log("[LocationSpoofer] getLastKnownLocation build error: $e2")
                }
                return@hookAllMethods result
            }
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] Failed to hook getLastKnownLocation: $e")
        }

        // Hook isLocationEnabled & isProviderEnabled (确保应用自检定位开启状态时恒为 true)
        try {
            val lmClazz = XposedHelpers.findClass("android.location.LocationManager", classLoader)
            XposedHelpers.hookAllMethods(lmClazz, "isLocationEnabled") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    return@hookAllMethods true
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(lmClazz, "isProviderEnabled") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    return@hookAllMethods true
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(lmClazz, "getAllProviders") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    return@hookAllMethods listOf("gps", "network", "passive")
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(lmClazz, "getProviders") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && currentPkg.substringBefore(":") != "com.vincenthzr.locationspoofer") {
                    return@hookAllMethods listOf("gps", "network", "passive")
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }
}
