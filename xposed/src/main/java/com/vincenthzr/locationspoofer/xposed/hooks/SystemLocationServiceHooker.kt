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

import android.os.IBinder
import android.os.IInterface
import android.util.Log
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry
import com.vincenthzr.locationspoofer.xposed.utils.*
import io.github.libxposed.api.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * system_server 系统级定位服务拦截与主动派发引擎
 *
 * 核心原理：
 * 全设备所有 App 的原生定位请求（LocationManager / FusedLocation）最终均通过 Binder IPC
 * 汇聚至 system_server 的 LocationManagerService。
 *
 * 本模块直接拦截 LocationManagerService，并在每次 IPC 入口处根据调用方 UID 与包名进行规则判定：
 * 1. 命中用户在 LocationSpoofer 中指定的应用白名单，或开启了【全局模拟模式】时，向该应用派发伪造的
 *    Location 对象、GNSS 20+ 卫星星座以及 NMEA-0183 报文流；
 * 2. 严禁污染 LocationSpoofer 自身与系统核心组件（SystemUI、电话、紧急呼叫）；
 * 3. 内置主动心跳推送定时器：当物理 GPS 芯片在室内无锁定时，主动向目标应用的 ILocationListener
 *    周期性推送平滑的伪造坐标序列，彻底杜绝目标应用无限期等待真实 GPS 回调的问题；
 * 4. 监听者通过 IBinder.linkToDeath 实现自动垃圾回收，客户端进程退出即刻清理，保证系统服务零内存泄露。
 */

private fun logLoc(msg: String) {
    XposedBridge.log(msg)
}

// 存储被标记为目标应用的 ILocationListener / ILocationCallback 实例
private val spoofedListenerInstances: MutableSet<Any> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

// 存储当前处于活跃状态的目标应用 ILocationListener 代理对象及其 Binder 映射（强引用避免 GC 导致失联）
private data class ListenerRegistrationInfo(
    val listener: Any,
    val packageName: String,
    val provider: String = android.location.LocationManager.GPS_PROVIDER,
    val registeredTime: Long = System.currentTimeMillis()
)

private val activeListenerBinders = ConcurrentHashMap<IBinder, ListenerRegistrationInfo>()

// 存储当前活跃的目标应用 IGnssStatusListener 映射
private data class GnssStatusRegistrationInfo(
    val listener: Any,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis()
)
private val activeGnssStatusBinders = ConcurrentHashMap<IBinder, GnssStatusRegistrationInfo>()

// 存储当前活跃的目标应用 IGnssNmeaListener 映射
private data class GnssNmeaRegistrationInfo(
    val listener: Any,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis()
)
private val activeGnssNmeaBinders = ConcurrentHashMap<IBinder, GnssNmeaRegistrationInfo>()

// 存储当前活跃的目标应用 ILocationCallback (单次定位) 映射
private data class CallbackRegistrationInfo(
    val callback: Any,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis()
)
private val activeCallbackBinders = ConcurrentHashMap<IBinder, CallbackRegistrationInfo>()

// 存储当前活跃的目标应用 PendingIntent 映射
private val activePendingIntents = ConcurrentHashMap<android.app.PendingIntent, String>()

@Volatile
private var isHeartbeatTimerStarted = false

/** 在 args 里递归找出所有 android.location.Location（单个、List、或 LocationResult 形态）并就地改写 */
private fun rewriteLocationArgs(
    args: List<Any?>,
    motion: SpoofedMotion,
    altitude: Double,
    accuracy: Float
) {
    for (arg in args) {
        when {
            arg == null -> continue
            LocationHooker.hasTypeByName(arg.javaClass, "android.location.Location") -> {
                SystemHookUtils.applyFakeLocationFields(arg, motion, altitude, accuracy)
            }
            arg.javaClass.name.contains("LocationResult") -> {
                try {
                    val list = XposedHelpers.callMethod(arg, "asList") as? List<*>
                    list?.forEach { item ->
                        if (item != null && LocationHooker.hasTypeByName(
                                item.javaClass,
                                "android.location.Location"
                            )
                        ) {
                            SystemHookUtils.applyFakeLocationFields(item, motion, altitude, accuracy)
                        }
                    }
                } catch (_: Throwable) {}
            }
            arg is List<*> -> {
                arg.forEach { item ->
                    if (item != null && LocationHooker.hasTypeByName(
                            item.javaClass,
                            "android.location.Location"
                        )
                    ) {
                        SystemHookUtils.applyFakeLocationFields(item, motion, altitude, accuracy)
                    }
                }
            }
        }
    }
}

/** 找到目标监听者/回调对象类之后，Hook 一次，按实例身份决定要不要改写投递内容 */
private fun LocationHooker.ensureCallbackHooked(callback: Any, vararg deliveryMethodNames: String) {
    val clazz = callback.javaClass
    if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return
    for (methodName in deliveryMethodNames) {
        try {
            XposedHelpers.hookAllMethods(clazz, methodName) { innerChain, _ ->
                val binder = (innerChain.thisObject as? IInterface)?.asBinder() ?: (innerChain.thisObject as? IBinder)
                val config = readConfig()
                val isGlobal = config?.optBoolean("system_hook_global_mode", false) == true
                val isTracked = (binder != null && (activeListenerBinders.containsKey(binder) || activeCallbackBinders.containsKey(binder))) ||
                        spoofedListenerInstances.contains(innerChain.thisObject)
                val isTarget = isTracked || isGlobal
                if (isTarget && config != null && config.optBoolean("active", false)) {
                    val motion = getCurrentSpoofedMotion("WGS-84")
                    if (motion != null) {
                        rewriteLocationArgs(
                            innerChain.args,
                            motion,
                            RouteEngine.realisticAltitude(config),
                            getJitteredAccuracy()
                        )
                        logLoc("[SysLoc] Rewrote location in callback $methodName for target binder (lat=${motion.lat}, lng=${motion.lng}, isGlobal=$isGlobal)"
                        )
                    }
                }
                return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
}

/**
 * 启动 system_server 内部的主动心跳推送定时器
 * 当目标应用处于活动状态时，此定时器主动向客户端 Binder 投递平滑的伪造坐标、
 * 20+ 真实多星座卫星（GnssStatus）以及标准 NMEA-0183 报文，彻底消除 GPS 状态测试工具卫星数为 0 的问题。
 */
private fun LocationHooker.startSystemLocationHeartbeat(classLoader: ClassLoader) {
    if (isHeartbeatTimerStarted) return
    synchronized(activeListenerBinders) {
        if (isHeartbeatTimerStarted) return
        isHeartbeatTimerStarted = true
    }

    val timer = Timer("LocationSpoofer-SysHeartbeat", true)
    timer.scheduleAtFixedRate(object : TimerTask() {
        override fun run() {
            try {
                try {
                    tryHookPendingSystemServices(classLoader)
                } catch (_: Throwable) {}

                if (activeListenerBinders.isEmpty() && activeGnssStatusBinders.isEmpty() && activeGnssNmeaBinders.isEmpty()) return
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return

                val motion = getCurrentSpoofedMotion("WGS-84") ?: return
                val altitude = RouteEngine.realisticAltitude(config)
                val accuracy = getJitteredAccuracy()

                // 1. 推送定位坐标给目标应用 ILocationListener
                if (activeListenerBinders.isNotEmpty()) {
                    for ((binder, info) in activeListenerBinders) {
                        if (!binder.isBinderAlive) {
                            activeListenerBinders.remove(binder)
                            continue
                        }
                        val fakeLoc = SystemHookUtils.buildFakeLocation(
                            classLoader,
                            info.provider,
                            motion,
                            altitude,
                            accuracy
                        )
                        if (fakeLoc != null) {
                            val listener = info.listener
                            try {
                                dispatchFakeLocationToListener(listener, fakeLoc)
                            } catch (e: Throwable) {
                                if (!binder.isBinderAlive) {
                                    activeListenerBinders.remove(binder)
                                }
                            }
                        }
                    }
                }

                // 2. 推送 20+ 真实卫星星座给目标应用 IGnssStatusListener (GnssStatus / GpsStatus)
                if (activeGnssStatusBinders.isNotEmpty()) {
                    val satCount = config.optInt("satellite_count", 20)
                    val enableJitter = config.optBoolean("enable_jitter", true)
                    val gnssStatus = GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                        classLoader,
                        satCount,
                        enableJitter,
                        null
                    )
                    for ((binder, info) in activeGnssStatusBinders) {
                        if (!binder.isBinderAlive) {
                            activeGnssStatusBinders.remove(binder)
                            continue
                        }
                        val callback = info.listener
                        try {
                            dispatchGnssStatus(callback, gnssStatus, classLoader)
                        } catch (e: Throwable) {
                            if (!binder.isBinderAlive) {
                                activeGnssStatusBinders.remove(binder)
                            }
                        }
                    }
                }

                // 3. 推送标准 NMEA-0183 报文流给目标应用 IGnssNmeaListener
                if (activeGnssNmeaBinders.isNotEmpty()) {
                    val nmeas = buildMockNmeaSentences(motion, altitude, accuracy, config)
                    val nowMs = System.currentTimeMillis()
                    for ((binder, info) in activeGnssNmeaBinders) {
                        if (!binder.isBinderAlive) {
                            activeGnssNmeaBinders.remove(binder)
                            continue
                        }
                        val callback = info.listener
                        try {
                            for (line in nmeas) {
                                dispatchNmea(callback, nowMs, line)
                            }
                        } catch (e: Throwable) {
                            if (!binder.isBinderAlive) {
                                activeGnssNmeaBinders.remove(binder)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }, 1000L, 1000L)
}

/** 向 IGnssStatusListener 代理对象主动反射调用 onSvStatusChanged，支持 Android 11+ (GnssStatus) 与 Android 7~10 */
private fun LocationHooker.dispatchGnssStatus(listener: Any, gnssStatus: Any?, classLoader: ClassLoader) {
    val clazz = listener.javaClass

    // 确保 GNSS 引擎启动状态已告知客户端
    try {
        clazz.methods.firstOrNull { it.name == "onGnssStarted" && it.parameterCount == 0 }?.invoke(listener)
    } catch (_: Throwable) {}
    try {
        clazz.methods.firstOrNull { it.name == "onFirstFix" && it.parameterCount == 1 }?.invoke(listener, 1000)
    } catch (_: Throwable) {}

    val svMethod = clazz.methods.firstOrNull { it.name == "onSvStatusChanged" }
    if (svMethod != null) {
        try {
            if (svMethod.parameterCount == 1) {
                // Android 11+ 标准：GnssStatus 对象 (Parcelable 跨进程分发)
                val statusObj = gnssStatus ?: GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                    classLoader, 20, true, null
                )
                if (statusObj != null) {
                    svMethod.invoke(listener, statusObj)
                }
            } else if (svMethod.parameterCount == 6) {
                // Android 7~10 兼容：6 数组签名
                val svCount = 20
                val svidWithFlags = IntArray(svCount)
                val cn0DbHz = FloatArray(svCount)
                val elevations = FloatArray(svCount)
                val azimuths = FloatArray(svCount)
                val carrierFrequencies = FloatArray(svCount)
                val rng = java.util.Random()
                for (i in 0 until svCount) {
                    val svid = when {
                        i < 10 -> i + 1
                        i < 16 -> (i - 10) + 201
                        else -> (i - 16) + 65
                    }
                    svidWithFlags[i] = (svid shl 4) or 0x07
                    cn0DbHz[i] = 30f + rng.nextFloat() * 12f
                    elevations[i] = 20f + rng.nextFloat() * 65f
                    azimuths[i] = rng.nextFloat() * 360f
                    carrierFrequencies[i] = 1575420000f
                }
                svMethod.invoke(listener, svCount, svidWithFlags, cn0DbHz, elevations, azimuths, carrierFrequencies)
            }
        } catch (e: Throwable) {
            logLoc("[SysLoc] dispatch onSvStatusChanged error: $e")
        }
    }
}

/** 向 IGnssNmeaListener 反射分发 NMEA 语句 */
private fun dispatchNmea(listener: Any, timestamp: Long, sentence: String) {
    val clazz = listener.javaClass
    val method = clazz.methods.firstOrNull { it.name == "onNmeaReceived" }
        ?: clazz.methods.firstOrNull { it.name == "onNmeaMessage" }
    if (method != null) {
        try {
            if (method.parameterTypes.size == 2) {
                if (method.parameterTypes[0] == Long::class.javaPrimitiveType || method.parameterTypes[0] == Long::class.java) {
                    method.invoke(listener, timestamp, sentence)
                } else {
                    method.invoke(listener, sentence, timestamp)
                }
            } else if (method.parameterTypes.size == 1 && method.parameterTypes[0] == String::class.java) {
                method.invoke(listener, sentence)
            }
        } catch (_: Throwable) {}
    }
}

/** 动态构造符合当前运动轨迹与坐标的标准 NMEA-0183 报文序列 */
private fun LocationHooker.buildMockNmeaSentences(
    motion: SpoofedMotion,
    altitude: Double,
    accuracy: Float,
    config: JSONObject
): List<String> {
    val sentences = mutableListOf<String>()
    val now = System.currentTimeMillis()
    val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    calendar.timeInMillis = now
    val timeUtc = String.format(
        java.util.Locale.US,
        "%02d%02d%02d.00",
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
        calendar.get(java.util.Calendar.SECOND)
    )
    val dateUtc = String.format(
        java.util.Locale.US,
        "%02d%02d%02d",
        calendar.get(java.util.Calendar.DAY_OF_MONTH),
        calendar.get(java.util.Calendar.MONTH) + 1,
        calendar.get(java.util.Calendar.YEAR) % 100
    )

    val lat = motion.lat
    val lng = motion.lng
    val absLat = kotlin.math.abs(lat)
    val latDeg = absLat.toInt()
    val latMin = (absLat - latDeg) * 60.0
    val latStr = String.format(java.util.Locale.US, "%02d%07.4f", latDeg, latMin)
    val latHem = if (lat >= 0) "N" else "S"

    val absLng = kotlin.math.abs(lng)
    val lngDeg = absLng.toInt()
    val lngMin = (absLng - lngDeg) * 60.0
    val lngStr = String.format(java.util.Locale.US, "%03d%07.4f", lngDeg, lngMin)
    val lngHem = if (lng >= 0) "E" else "W"

    val speedKnots = (motion.speed * 1.943844).coerceAtLeast(0.0)
    val speedStr = String.format(java.util.Locale.US, "%.2f", speedKnots)
    val bearingStr = String.format(java.util.Locale.US, "%.1f", motion.bearing)
    val altStr = String.format(java.util.Locale.US, "%.1f", altitude)
    val satCount = config.optInt("satellite_count", 20)

    // $GPRMC
    val rmc = "GPRMC,$timeUtc,A,$latStr,$latHem,$lngStr,$lngHem,$speedStr,$bearingStr,$dateUtc,,,A"
    sentences.add("$$rmc*${calculateNmeaChecksum(rmc)}")

    // $GPGGA
    val gga = "GPGGA,$timeUtc,$latStr,$latHem,$lngStr,$lngHem,1,$satCount,0.8,$altStr,M,0.0,M,,"
    sentences.add("$$gga*${calculateNmeaChecksum(gga)}")

    // $GPGSA
    val gsa = "GPGSA,A,3,01,02,03,04,05,06,07,08,09,10,,,1.2,0.8,0.9"
    sentences.add("$$gsa*${calculateNmeaChecksum(gsa)}")

    // $GPGSV (GPS 10颗)
    val gsv1 = "GPGSV,3,1,10,01,65,045,41,02,55,120,38,03,45,210,36,04,38,090,39"
    sentences.add("$$gsv1*${calculateNmeaChecksum(gsv1)}")
    val gsv2 = "GPGSV,3,2,10,05,32,315,35,06,28,180,37,07,22,270,33,08,18,045,34"
    sentences.add("$$gsv2*${calculateNmeaChecksum(gsv2)}")
    val gsv3 = "GPGSV,3,3,10,09,15,135,32,10,12,300,31"
    sentences.add("$$gsv3*${calculateNmeaChecksum(gsv3)}")

    // $BDGSV (北斗 6颗)
    val bdgsv1 = "BDGSV,2,1,06,201,70,060,42,202,60,150,40,203,52,240,39,204,45,320,38"
    sentences.add("$$bdgsv1*${calculateNmeaChecksum(bdgsv1)}")
    val bdgsv2 = "BDGSV,2,2,06,205,35,110,36,206,25,200,35"
    sentences.add("$$bdgsv2*${calculateNmeaChecksum(bdgsv2)}")

    return sentences
}

/** 向 ILocationListener 代理对象主动反射调用 onLocationChanged */
private fun dispatchFakeLocationToListener(listener: Any, fakeLoc: Any) {
    val clazz = listener.javaClass
    val methods = clazz.methods

    // 优先尝试 onLocationChanged(Location)
    val singleLocMethod = methods.firstOrNull {
        it.name == "onLocationChanged" && it.parameterTypes.size == 1 &&
                LocationHooker.hasTypeByName(it.parameterTypes[0], "android.location.Location")
    }
    if (singleLocMethod != null) {
        singleLocMethod.invoke(listener, fakeLoc)
        return
    }

    // Android 12+ 批量位置接口: onLocationChanged(List<Location>, IRemoteCallback)
    val batchMethod = methods.firstOrNull {
        it.name == "onLocationChanged" && it.parameterTypes.isNotEmpty() &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
    }
    if (batchMethod != null) {
        val args = arrayOfNulls<Any>(batchMethod.parameterTypes.size)
        args[0] = listOf(fakeLoc)
        if (args.size > 1) {
            args[1] = createDummyRemoteCallback()
        }
        try {
            batchMethod.invoke(listener, *args)
        } catch (_: Throwable) {
            if (args.size > 1) {
                args[1] = null
                try { batchMethod.invoke(listener, *args) } catch (_: Throwable) {}
            }
        }
    }
}

private fun createDummyRemoteCallback(): Any {
    return object : android.os.Binder(), android.os.IInterface {
        override fun asBinder(): android.os.IBinder = this
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            reply?.writeNoException()
            return true
        }
    }
}

private fun createDummyCancellationSignal(): Any {
    return object : android.os.Binder(), android.os.IInterface {
        override fun asBinder(): android.os.IBinder = this
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            reply?.writeNoException()
            return true
        }
    }
}

internal fun LocationHooker.hookSystemLocationService(classLoader: ClassLoader) {
    val serviceClazz = VendorRegistry.resolveClass(
        SystemComponent.LOCATION_MANAGER_SERVICE, classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.server.location.LocationManagerService", classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.server.LocationManagerService", classLoader
    )

    if (serviceClazz == null) {
        logLoc("[SysHook] LocationManagerService not found, skip system-level location hook")
        return
    }
    logLoc("[SysHook] hookSystemLocationService invoked on ${serviceClazz.name}")
    if (hookedCallbackClasses.putIfAbsent(serviceClazz, true) != null) {
        return
    }

    startSystemLocationHeartbeat(classLoader)

    // =========================================================================
    // 1. getLastLocation：同步返回，命中目标应用直接替换为伪造 Location
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(serviceClazz, "getLastLocation") { chain, _ ->
            val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
            val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

            if (!isTarget) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val motion = getCurrentSpoofedMotion("WGS-84")
                ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())

            val provider = SystemHookUtils.extractProvider(chain.args)

            val fakeLoc = SystemHookUtils.buildFakeLocation(
                classLoader,
                provider,
                motion,
                RouteEngine.realisticAltitude(config),
                getJitteredAccuracy()
            )

            if (fakeLoc != null) {
                logLoc(
                    "[SysHook] Injected fake location for ${explicitPkg ?: "caller"} (getLastLocation): lat=${motion.lat}, lng=${motion.lng}, provider=$provider"
                )
                return@hookAllMethods fakeLoc
            }

            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        logLoc("[SysHook] LocationManagerService.getLastLocation hooked")
    } catch (e: Throwable) {
        logLoc("[SysHook] hook getLastLocation failed: $e")
    }

    // =========================================================================
    // 2. registerLocationListener (Android 12+), requestLocationUpdates & registerLocationPendingIntent
    // =========================================================================
    val listenerRegisterMethodNames = arrayOf("registerLocationListener", "requestLocationUpdates", "registerLocationPendingIntent")
    for (methodName in listenerRegisterMethodNames) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, methodName) { chain, _ ->
                try {
                    val config = readConfig()
                    if (config != null) {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

                        if (isTarget) {
                            val listener = chain.args.firstOrNull { arg ->
                                arg != null && LocationHooker.hasTypeByName(
                                    arg.javaClass,
                                    "android.location.ILocationListener"
                                )
                            }
                            val provider = SystemHookUtils.extractProvider(chain.args)
                            if (listener != null) {
                                val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                                val pkgName = explicitPkg ?: "unknown"
                                if (binder != null) {
                                    activeListenerBinders[binder] = ListenerRegistrationInfo(
                                        listener = listener,
                                        packageName = pkgName,
                                        provider = provider
                                    )
                                    try {
                                        binder.linkToDeath({
                                            activeListenerBinders.remove(binder)
                                            logLoc("[SysHook] Listener died and removed for $pkgName")
                                        }, 0)
                                    } catch (_: Throwable) {}
                                }
                                spoofedListenerInstances.add(listener)
                                ensureCallbackHooked(listener, "onLocationChanged")

                                logLoc("[SysHook] Registered target location listener for ${explicitPkg ?: "caller"} (total active: ${activeListenerBinders.size})"
                                )

                                // ★ 核心修复: 注册瞬间立即主动同步派发首帧伪造位置，实现 0ms 瞬间定位，
                                // 彻底消除等待 1 秒心跳导致的定位重试与真实位置闪现
                                val motion = getCurrentSpoofedMotion("WGS-84")
                                if (motion != null) {
                                    val fakeLoc = SystemHookUtils.buildFakeLocation(
                                        classLoader,
                                        provider,
                                        motion,
                                        RouteEngine.realisticAltitude(config),
                                        getJitteredAccuracy()
                                    )
                                    if (fakeLoc != null) {
                                        try {
                                            dispatchFakeLocationToListener(listener, fakeLoc)
                                            logLoc("[SysHook] Proactively dispatched instant fake location to listener for ${explicitPkg ?: "caller"} (provider=$provider)"
                                            )
                                        } catch (t: Throwable) {
                                            logLoc("[SysHook] instant proactive dispatch failed: $t")
                                        }
                                    }
                                }
                            }

                            val pendingIntent = chain.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent
                            if (pendingIntent != null) {
                                val pkgName = explicitPkg ?: pendingIntent.creatorPackage ?: "unknown"
                                activePendingIntents[pendingIntent] = pkgName
                                logLoc("[SysHook] Registered target location PendingIntent for $pkgName (total active: ${activePendingIntents.size})")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    logLoc("[SysHook] $methodName pre-hook error: $e")
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logLoc("[SysHook] LocationManagerService.$methodName hooked")
        } catch (e: Throwable) {
            logLoc("[SysHook] hook $methodName failed: $e")
        }
    }

    // =========================================================================
    // 3. unregisterLocationListener (Android 12+) & removeUpdates (Android 8-11)
    // =========================================================================
    val unregisterMethodNames = arrayOf("unregisterLocationListener", "removeUpdates")
    for (methodName in unregisterMethodNames) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, methodName) { chain, _ ->
                try {
                    val listener = chain.args.firstOrNull { arg ->
                        arg != null && LocationHooker.hasTypeByName(
                            arg.javaClass,
                            "android.location.ILocationListener"
                        )
                    }
                    if (listener != null) {
                        val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                        if (binder != null) {
                            activeListenerBinders.remove(binder)
                        }
                        spoofedListenerInstances.remove(listener)
                    }
                    val pendingIntent = chain.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent
                    if (pendingIntent != null) {
                        activePendingIntents.remove(pendingIntent)
                    }
                } catch (_: Throwable) {}
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 4. getCurrentLocation：单次定位回调拦截与主动投递
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(serviceClazz, "getCurrentLocation") { chain, _ ->
            try {
                val config = readConfig()
                if (config != null) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

                    if (isTarget) {
                        val callback = chain.args.firstOrNull { arg ->
                            arg != null && LocationHooker.hasTypeByName(
                                arg.javaClass,
                                "android.location.ILocationCallback"
                            )
                        }
                        if (callback != null) {
                            val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                            val pkgName = explicitPkg ?: "caller"
                            if (binder != null) {
                                activeCallbackBinders[binder] = CallbackRegistrationInfo(
                                    callback = callback,
                                    packageName = pkgName
                                )
                                try {
                                    binder.linkToDeath({
                                        activeCallbackBinders.remove(binder)
                                        logLoc("[SysLoc] Callback binder died: $pkgName")
                                    }, 0)
                                } catch (_: Throwable) {}
                            }

                            spoofedListenerInstances.add(callback)
                            ensureCallbackHooked(callback, "onLocation")
                            logLoc("[SysLoc] Registered getCurrentLocation callback for $pkgName (active callbacks: ${activeCallbackBinders.size})"
                            )

                            // 主动推送单次定位结果，防止在室内真实 GPS 未锁定导致目标 App 持续等待超时
                            val motion = getCurrentSpoofedMotion("WGS-84")
                            if (motion != null) {
                                val provider = SystemHookUtils.extractProvider(chain.args)
                                val fakeLoc = SystemHookUtils.buildFakeLocation(
                                    classLoader,
                                    provider,
                                    motion,
                                    RouteEngine.realisticAltitude(config),
                                    getJitteredAccuracy()
                                )
                                if (fakeLoc != null) {
                                    val onLocationMethod = callback.javaClass.methods.firstOrNull {
                                        it.name == "onLocation" && it.parameterTypes.size == 1
                                    }
                                    if (onLocationMethod != null) {
                                        try {
                                            onLocationMethod.invoke(callback, fakeLoc)
                                            logLoc("[SysLoc] Proactively dispatched fake location to getCurrentLocation callback for $pkgName (lat=${motion.lat}, lng=${motion.lng}, provider=$provider)"
                                            )
                                            return@hookAllMethods createDummyCancellationSignal()
                                        } catch (t: Throwable) {
                                            logLoc("[SysLoc] proactive callback invoke failed: $t")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                logLoc("[SysLoc] getCurrentLocation pre-hook failed: $e")
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        logLoc("[SysLoc] LocationManagerService.getCurrentLocation hooked")
    } catch (e: Throwable) {
        logLoc("[SysLoc] hook getCurrentLocation failed: $e")
    }

    // =========================================================================
    // 5. isProviderEnabled & isProviderEnabledForUser：对目标应用强制汇报 GPS 可用
    // =========================================================================
    val providerEnabledMethods = arrayOf("isProviderEnabled", "isProviderEnabledForUser")
    for (methodName in providerEnabledMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, methodName) { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val providerArg = SystemHookUtils.extractProvider(chain.args)
                    if (providerArg == "gps" || providerArg == "network" || providerArg == "passive" || providerArg == "fused") {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        if (SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)) {
                            return@hookAllMethods true
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 6. GNSS 卫星与 NMEA 拦截与注册 (registerGnssStatusCallback / registerGnssNmeaCallback)
    // =========================================================================
    val registerGnssMethods = arrayOf("registerGnssStatusCallback", "addGpsStatusListener")
    for (mName in registerGnssMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                try {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)
                        if (isTarget) {
                            val callback = chain.args.firstOrNull { arg ->
                                arg != null && (arg is IInterface || arg is IBinder ||
                                        LocationHooker.hasTypeByName(arg.javaClass, "android.location.IGnssStatusListener") ||
                                        LocationHooker.hasTypeByName(arg.javaClass, "android.location.IGnssStatusCallback") ||
                                        LocationHooker.hasTypeByName(arg.javaClass, "android.location.IGpsStatusListener") ||
                                        arg.javaClass.name.contains("Gnss") || arg.javaClass.name.contains("Gps"))
                            }
                            if (callback != null) {
                                val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                                if (binder != null) {
                                    val pkgName = explicitPkg ?: "unknown"
                                    activeGnssStatusBinders[binder] = GnssStatusRegistrationInfo(
                                        listener = callback,
                                        packageName = pkgName
                                    )
                                    try {
                                        binder.linkToDeath({
                                            activeGnssStatusBinders.remove(binder)
                                            logLoc("[SysLoc] GNSS status listener died: $pkgName")
                                        }, 0)
                                    } catch (_: Throwable) {}

                                    // 立即向新注册的监听器派发一次卫星数据，避免等待心跳周期
                                    val gnssStatus = GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                                        classLoader,
                                        config.optInt("satellite_count", 20),
                                        config.optBoolean("enable_jitter", true),
                                        null
                                    )
                                    dispatchGnssStatus(callback, gnssStatus, classLoader)
                                    hookGnssStatusListenerCallback(callback)

                                    logLoc("[SysLoc] Registered & dispatched target GNSS status callback for $pkgName (active: ${activeGnssStatusBinders.size})"
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    logLoc("[SysLoc] $mName pre-hook failed: $e")
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logLoc("[SysLoc] LocationManagerService.$mName hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook $mName failed: $e")
        }
    }

    val unregisterGnssMethods = arrayOf("unregisterGnssStatusCallback", "removeGpsStatusListener")
    for (mName in unregisterGnssMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                val callback = chain.args.firstOrNull { arg ->
                    arg != null && (arg is IInterface || arg is IBinder)
                }
                if (callback != null) {
                    val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                    if (binder != null) {
                        activeGnssStatusBinders.remove(binder)
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    val registerNmeaMethods = arrayOf("registerGnssNmeaCallback", "addGnssBatchingCallback", "addNmeaListener")
    for (mName in registerNmeaMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                try {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)
                        if (isTarget) {
                            val callback = chain.args.firstOrNull { arg ->
                                arg != null && (arg is IInterface || arg is IBinder || arg.javaClass.name.contains("Nmea"))
                            }
                            if (callback != null) {
                                val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                                if (binder != null) {
                                    val pkgName = explicitPkg ?: "unknown"
                                    activeGnssNmeaBinders[binder] = GnssNmeaRegistrationInfo(
                                        listener = callback,
                                        packageName = pkgName
                                    )
                                    try {
                                        binder.linkToDeath({
                                            activeGnssNmeaBinders.remove(binder)
                                            logLoc("[SysLoc] NMEA listener died: $pkgName")
                                        }, 0)
                                    } catch (_: Throwable) {}

                                    val motion = getCurrentSpoofedMotion("WGS-84")
                                    if (motion != null) {
                                        val sentences = buildMockNmeaSentences(motion, RouteEngine.realisticAltitude(config), getJitteredAccuracy(), config)
                                        val nowMs = System.currentTimeMillis()
                                        for (line in sentences) {
                                            dispatchNmea(callback, nowMs, line)
                                        }
                                    }
                                    logLoc("[SysLoc] Registered & dispatched NMEA callback for $pkgName (active: ${activeGnssNmeaBinders.size})")
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    val unregisterNmeaMethods = arrayOf("unregisterGnssNmeaCallback", "removeNmeaListener")
    for (mName in unregisterNmeaMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                val callback = chain.args.firstOrNull { arg ->
                    arg != null && (arg is IInterface || arg is IBinder)
                }
                if (callback != null) {
                    val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                    if (binder != null) {
                        activeGnssNmeaBinders.remove(binder)
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    try {
        XposedHelpers.hookAllMethods(serviceClazz, "getGnssYearOfHardware") { chain, _ ->
            val result = chain.proceed(chain.args.toTypedArray()) as? Int
            if (result == null || result < 2020) {
                return@hookAllMethods 2024
            }
            return@hookAllMethods result
        }
    } catch (_: Throwable) {}

    // 屏蔽目标应用的底层伪距与导航电文回调，防止泄露真实原始测量特征
    val suppressCallbackMethods = arrayOf("registerGnssMeasurementsCallback", "registerGnssNavigationMessageCallback")
    for (mName in suppressCallbackMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    if (SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)) {
                        val cb = chain.args.firstOrNull { arg ->
                            arg != null && arg !is String && arg !is Int
                        }
                        if (cb != null) {
                            suppressCallbackMethods(cb, "onGnssMeasurementsReceived", "onGnssNavigationMessageReceived")
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 7. LocationProviderManager 底层引擎挂载 (Android 12+)
    // 直接在 providerManager 层拦截 getLastLocation 与 getCurrentLocation，
    // 全面覆盖所有原生与定制 provider ("gps", "network", "fused", "passive")
    // =========================================================================
    val providerManagerClazz = VendorRegistry.resolveClass(
        SystemComponent.LOCATION_PROVIDER_MANAGER, classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.server.location.provider.LocationProviderManager", classLoader
    )
    if (providerManagerClazz != null && hookedCallbackClasses.putIfAbsent(providerManagerClazz, true) == null) {
        try {
            val getLastLocationMethods = arrayOf("getLastLocation", "getLastLocationUnsafe")
            for (mName in getLastLocationMethods) {
                XposedHelpers.hookAllMethods(providerManagerClazz, mName) { chain, _ ->
                    val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    val pkgName = SystemHookUtils.extractPackageName(chain.args)
                    val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkgName, config, overrideUid)
                    if (isTarget) {
                        val motion = getCurrentSpoofedMotion("WGS-84")
                        if (motion != null) {
                            val provider = try {
                                XposedHelpers.getObjectField(chain.thisObject, "mName") as? String
                            } catch (_: Throwable) { null } ?: SystemHookUtils.extractProvider(chain.args)
                            val fakeLoc = SystemHookUtils.buildFakeLocation(
                                classLoader,
                                provider,
                                motion,
                                RouteEngine.realisticAltitude(config),
                                getJitteredAccuracy()
                            )
                            if (fakeLoc != null) {
                                logLoc("[SysLoc] LocationProviderManager.$mName intercepted for $pkgName (lat=${motion.lat}, lng=${motion.lng}, provider=$provider)")
                                return@hookAllMethods fakeLoc
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            }
            logLoc("[SysLoc] LocationProviderManager.getLastLocation hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook LocationProviderManager.getLastLocation failed: $e")
        }

        try {
            XposedHelpers.hookAllMethods(providerManagerClazz, "getCurrentLocation") { chain, _ ->
                try {
                    val config = readConfig()
                    if (config != null) {
                        val pkgName = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkgName, config, overrideUid)
                        if (isTarget) {
                            val callback = chain.args.firstOrNull { arg ->
                                arg != null && LocationHooker.hasTypeByName(
                                    arg.javaClass,
                                    "android.location.ILocationCallback"
                                )
                            }
                            if (callback != null) {
                                val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                                val targetPkg = pkgName ?: "caller"
                                if (binder != null) {
                                    activeCallbackBinders[binder] = CallbackRegistrationInfo(
                                        callback = callback,
                                        packageName = targetPkg
                                    )
                                    try {
                                        binder.linkToDeath({ activeCallbackBinders.remove(binder) }, 0)
                                    } catch (_: Throwable) {}
                                }
                                spoofedListenerInstances.add(callback)
                                ensureCallbackHooked(callback, "onLocation")
                                val motion = getCurrentSpoofedMotion("WGS-84")
                                if (motion != null) {
                                    val provider = try {
                                        XposedHelpers.getObjectField(chain.thisObject, "mName") as? String
                                    } catch (_: Throwable) { null } ?: SystemHookUtils.extractProvider(chain.args)
                                    val fakeLoc = SystemHookUtils.buildFakeLocation(
                                        classLoader,
                                        provider,
                                        motion,
                                        RouteEngine.realisticAltitude(config),
                                        getJitteredAccuracy()
                                    )
                                    if (fakeLoc != null) {
                                        val onLocMethod = callback.javaClass.methods.firstOrNull {
                                            it.name == "onLocation" && it.parameterTypes.size == 1
                                        }
                                        onLocMethod?.invoke(callback, fakeLoc)
                                        logLoc("[SysLoc] LocationProviderManager.getCurrentLocation proactive dispatch for $targetPkg (lat=${motion.lat}, lng=${motion.lng}, provider=$provider)")
                                        return@hookAllMethods createDummyCancellationSignal()
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logLoc("[SysLoc] LocationProviderManager.getCurrentLocation hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook LocationProviderManager.getCurrentLocation failed: $e")
        }

        val registerMethodNames = arrayOf("registerLocationRequest", "registerLocationListener", "registerLocationPendingIntent")
        for (mName in registerMethodNames) {
            try {
                XposedHelpers.hookAllMethods(providerManagerClazz, mName) { chain, _ ->
                    val config = readConfig()
                    if (config != null) {
                        val pkgName = SystemHookUtils.extractPackageName(chain.args)
                        val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkgName, config, overrideUid)
                        if (isTarget) {
                            var listener = chain.args.firstOrNull { arg ->
                                arg != null && (LocationHooker.hasTypeByName(arg.javaClass, "android.location.ILocationListener") ||
                                        arg.javaClass.simpleName.contains("Listener"))
                            }
                            if (listener == null) {
                                for (arg in chain.args) {
                                    if (arg != null && arg.javaClass.simpleName.contains("Registration")) {
                                        try {
                                            listener = XposedHelpers.getObjectField(arg, "mListener")
                                            if (listener != null) break
                                        } catch (_: Throwable) {}
                                        try {
                                            listener = XposedHelpers.callMethod(arg, "getListener")
                                            if (listener != null) break
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }
                            val providerName = try {
                                XposedHelpers.getObjectField(chain.thisObject, "mName") as? String
                            } catch (_: Throwable) { null } ?: SystemHookUtils.extractProvider(chain.args)

                            if (listener != null) {
                                val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                                if (binder != null) {
                                    activeListenerBinders[binder] = ListenerRegistrationInfo(
                                        listener = listener,
                                        packageName = pkgName ?: "target",
                                        provider = providerName
                                    )
                                    try { binder.linkToDeath({ activeListenerBinders.remove(binder) }, 0) } catch (_: Throwable) {}
                                }
                                spoofedListenerInstances.add(listener)
                                ensureCallbackHooked(listener, "onLocationChanged")

                                // 立即主动投递首帧伪造位置
                                val motion = getCurrentSpoofedMotion("WGS-84")
                                if (motion != null) {
                                    val fakeLoc = SystemHookUtils.buildFakeLocation(
                                        classLoader,
                                        providerName,
                                        motion,
                                        RouteEngine.realisticAltitude(config),
                                        getJitteredAccuracy()
                                    )
                                    if (fakeLoc != null) {
                                        try {
                                            dispatchFakeLocationToListener(listener, fakeLoc)
                                            logLoc("[SysLoc] LocationProviderManager.$mName proactive dispatch for $pkgName (lat=${motion.lat}, lng=${motion.lng}, provider=$providerName)")
                                        } catch (t: Throwable) {
                                            logLoc("[SysLoc] proactive dispatch error: $t")
                                        }
                                    }
                                }
                                logLoc("[SysLoc] LocationProviderManager.$mName captured for $pkgName (active listeners: ${activeListenerBinders.size})")
                            }

                            val pi = chain.args.firstOrNull { it is android.app.PendingIntent } as? android.app.PendingIntent
                            if (pi != null) {
                                val targetPkg = pkgName ?: pi.creatorPackage ?: "target"
                                activePendingIntents[pi] = targetPkg
                                logLoc("[SysLoc] LocationProviderManager.$mName PendingIntent captured for $targetPkg")
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                logLoc("[SysLoc] LocationProviderManager.$mName hooked")
            } catch (e: Throwable) {
                logLoc("[SysLoc] hook LocationProviderManager.$mName failed: $e")
            }
        }

        val unregisterMethods = arrayOf("unregisterLocationRequest", "unregisterLocationListener")
        for (mName in unregisterMethods) {
            try {
                XposedHelpers.hookAllMethods(providerManagerClazz, mName) { chain, _ ->
                    try {
                        for (arg in chain.args) {
                            if (arg != null) {
                                val binder = (arg as? IInterface)?.asBinder() ?: (arg as? IBinder)
                                if (binder != null) {
                                    activeListenerBinders.remove(binder)
                                }
                                spoofedListenerInstances.remove(arg)
                            }
                        }
                    } catch (_: Throwable) {}
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }

        // 核心修复: 拦截底层 Provider 上报真实位置 (onReportLocation)
        // 在全局模拟模式下，直接就地改写 LocationResult，防止 MetokNLP/GPS 真实数据污染系统内部缓存
        try {
            XposedHelpers.hookAllMethods(providerManagerClazz, "onReportLocation") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val isGlobal = config.optBoolean("system_hook_global_mode", false)
                    if (isGlobal) {
                        val motion = getCurrentSpoofedMotion("WGS-84")
                        if (motion != null) {
                            rewriteLocationArgs(chain.args, motion, RouteEngine.realisticAltitude(config), getJitteredAccuracy())
                            logLoc("[SysLoc] LocationProviderManager.onReportLocation rewrote location globally")
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logLoc("[SysLoc] LocationProviderManager.onReportLocation hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook LocationProviderManager.onReportLocation failed: $e")
        }

        // 核心修复: 拦截向目标注册客户端 (Registration) 分发真实位置的关键节点 acceptLocationChange
        // 彻底解决抖音/淘宝两边跳：当 MetokNLP 或真实 GPS 产生定位推向目标 App 时，在 Registration 处直接改写为模拟坐标
        val regClasses = listOfNotNull(
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$Registration", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationRegistration", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationListenerRegistration", classLoader)
        )
        for (regClazz in regClasses) {
            if (hookedCallbackClasses.putIfAbsent(regClazz, true) != null) continue
            try {
                XposedHelpers.hookAllMethods(regClazz, "acceptLocationChange") { chain, _ ->
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val identity = try {
                            XposedHelpers.getObjectField(chain.thisObject, "mIdentity")
                        } catch (_: Throwable) {
                            try { XposedHelpers.callMethod(chain.thisObject, "getIdentity") } catch (_: Throwable) { null }
                        }
                        val pkg = identity?.let {
                            try { XposedHelpers.callMethod(it, "getPackageName") as? String } catch (_: Throwable) {
                                try { XposedHelpers.getObjectField(it, "mPackageName") as? String } catch (_: Throwable) { null }
                            }
                        }
                        val uid = identity?.let {
                            try { XposedHelpers.callMethod(it, "getUid") as? Int } catch (_: Throwable) {
                                try { XposedHelpers.getIntField(it, "mUid") } catch (_: Throwable) { null }
                            }
                        }
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config, uid)
                        if (isTarget) {
                            val motion = getCurrentSpoofedMotion("WGS-84")
                            if (motion != null) {
                                rewriteLocationArgs(chain.args, motion, RouteEngine.realisticAltitude(config), getJitteredAccuracy())
                                logLoc("[SysLoc] Registration.acceptLocationChange rewrote location for $pkg (lat=${motion.lat}, lng=${motion.lng})")
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                logLoc("[SysLoc] ${regClazz.name}.acceptLocationChange hooked")
            } catch (e: Throwable) {
                logLoc("[SysLoc] hook ${regClazz.name}.acceptLocationChange failed: $e")
            }
        }

        // 核心修复: 拦截向目标 Binder 派发位置的 ProviderTransport
        val transportClasses = listOfNotNull(
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationListenerTransport", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$LocationPendingIntentTransport", classLoader),
            XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager\$GetCurrentLocationTransport", classLoader)
        )
        for (tClazz in transportClasses) {
            if (hookedCallbackClasses.putIfAbsent(tClazz, true) != null) continue
            try {
                XposedHelpers.hookAllMethods(tClazz, "deliverOnLocationChanged") { chain, _ ->
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val isGlobal = config.optBoolean("system_hook_global_mode", false)
                        var isTarget = isGlobal
                        if (!isTarget) {
                            val listener = try { XposedHelpers.getObjectField(chain.thisObject, "mListener") } catch (_: Throwable) { null }
                            val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                            if (binder != null && activeListenerBinders.containsKey(binder)) {
                                isTarget = true
                            } else if (listener != null && spoofedListenerInstances.contains(listener)) {
                                isTarget = true
                            }
                            if (!isTarget) {
                                val pi = try { XposedHelpers.getObjectField(chain.thisObject, "mPendingIntent") as? android.app.PendingIntent } catch (_: Throwable) { null }
                                if (pi != null && activePendingIntents.containsKey(pi)) {
                                    isTarget = true
                                }
                            }
                            if (!isTarget) {
                                val cb = try { XposedHelpers.getObjectField(chain.thisObject, "mCallback") } catch (_: Throwable) { null }
                                val cbBinder = (cb as? IInterface)?.asBinder() ?: (cb as? IBinder)
                                if (cbBinder != null && activeCallbackBinders.containsKey(cbBinder)) {
                                    isTarget = true
                                } else if (cb != null && spoofedListenerInstances.contains(cb)) {
                                    isTarget = true
                                }
                            }
                        }
                        if (isTarget) {
                            val motion = getCurrentSpoofedMotion("WGS-84")
                            if (motion != null) {
                                rewriteLocationArgs(chain.args, motion, RouteEngine.realisticAltitude(config), getJitteredAccuracy())
                                logLoc("[SysLoc] ${tClazz.simpleName}.deliverOnLocationChanged rewrote location (lat=${motion.lat}, lng=${motion.lng})")
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                logLoc("[SysLoc] ${tClazz.name}.deliverOnLocationChanged hooked")
            } catch (e: Throwable) {
                logLoc("[SysLoc] hook ${tClazz.name}.deliverOnLocationChanged failed: $e")
            }
        }
    }

    // =========================================================================
    // 8. 逆地理编码 Geocoder 拦截 (reverseGeocode / getFromLocation)
    // 防止系统 Geocoder（如小米 MetokGeocodeService / MetokNLP）返回真实住址
    // =========================================================================
    val geocodeMethods = arrayOf("reverseGeocode", "forwardGeocode", "getFromLocation")
    for (mName in geocodeMethods) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, mName) { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)
                    if (isTarget) {
                        val motion = getCurrentSpoofedMotion("GCJ-02")
                        if (motion != null) {
                            try {
                                val addressClass = XposedHelpers.findClass("android.location.Address", classLoader)
                                val addressObj = XposedHelpers.newInstance(addressClass, java.util.Locale.getDefault())
                                XposedHelpers.callMethod(addressObj, "setLatitude", motion.lat)
                                XposedHelpers.callMethod(addressObj, "setLongitude", motion.lng)
                                val province = cachedProvince.ifEmpty { "北京市" }
                                val city = cachedCity.ifEmpty { "北京市" }
                                val district = cachedDistrict.ifEmpty { "东城区" }
                                val street = cachedStreet.ifEmpty { "南长街" }
                                val fullAddr = cachedAddress.ifEmpty { "$province$city$district$street" }
                                XposedHelpers.callMethod(addressObj, "setAdminArea", province)
                                XposedHelpers.callMethod(addressObj, "setLocality", city)
                                XposedHelpers.callMethod(addressObj, "setSubLocality", district)
                                XposedHelpers.callMethod(addressObj, "setThoroughfare", street)
                                XposedHelpers.callMethod(addressObj, "setAddressLine", 0, fullAddr)

                                // 1. 回调式接口 (Android 13+ IGeocodeListener)
                                val listener = chain.args.firstOrNull { arg ->
                                    arg != null && (arg is IInterface || arg is IBinder ||
                                            arg.javaClass.simpleName.contains("Listener") ||
                                            arg.javaClass.simpleName.contains("Callback"))
                                }
                                if (listener != null) {
                                    val onResultsMethod = listener.javaClass.methods.firstOrNull {
                                        it.name == "onResults" || it.name == "onGeocode" || it.name == "onLocation"
                                    }
                                    if (onResultsMethod != null) {
                                        val params = arrayOfNulls<Any>(onResultsMethod.parameterTypes.size)
                                        for (i in params.indices) {
                                            val pType = onResultsMethod.parameterTypes[i]
                                            if (List::class.java.isAssignableFrom(pType)) {
                                                params[i] = listOf(addressObj)
                                            } else if (pType == addressClass) {
                                                params[i] = addressObj
                                            }
                                        }
                                        onResultsMethod.invoke(listener, *params)
                                        logLoc("[SysLoc] Intercepted $mName (callback) for ${explicitPkg ?: "caller"}, returned: $fullAddr")
                                        return@hookAllMethods null
                                    }
                                }

                                // 2. 入参集合修改 (Android 11-12 inout List<Address>)
                                @Suppress("UNCHECKED_CAST")
                                val addrs = chain.args.firstOrNull { it is List<*> } as? MutableList<Any>
                                if (addrs != null) {
                                    addrs.clear()
                                    addrs.add(addressObj)
                                    logLoc("[SysLoc] Intercepted $mName (inout list) for ${explicitPkg ?: "caller"}, returned: $fullAddr")
                                    return@hookAllMethods null
                                }

                                logLoc("[SysLoc] Intercepted $mName for ${explicitPkg ?: "caller"}, returned: $fullAddr")
                                return@hookAllMethods listOf(addressObj)
                            } catch (e: Throwable) {
                                logLoc("[SysLoc] mock Geocoder Address failed: $e")
                            }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logLoc("[SysLoc] LocationManagerService.$mName hooked")
        } catch (e: Throwable) {
            logLoc("[SysLoc] hook $mName failed: $e")
        }
    }

    hookPendingIntentDelivery(classLoader)
}

/** 拦截 PendingIntent.send(...) 改写通过 PendingIntent 投递给目标应用的 Location 广播与服务 Intent */
private fun LocationHooker.hookPendingIntentDelivery(classLoader: ClassLoader) {
    val piClass = XposedHelpers.findClassIfExists("android.app.PendingIntent", classLoader) ?: return
    if (hookedCallbackClasses.putIfAbsent(piClass, true) != null) return

    XposedHelpers.hookAllMethods(piClass, "send") { chain, _ ->
        val config = readConfig()
        if (config != null && config.optBoolean("active", false)) {
            val pi = chain.thisObject as? android.app.PendingIntent
            val isTarget = if (pi != null && activePendingIntents.containsKey(pi)) {
                true
            } else {
                val pkg = pi?.creatorPackage
                pkg != null && SystemHookUtils.isTargetCaller(chain.thisObject, pkg, config)
            }
            if (isTarget) {
                val motion = getCurrentSpoofedMotion("WGS-84")
                if (motion != null) {
                    val altitude = RouteEngine.realisticAltitude(config)
                    val accuracy = getJitteredAccuracy()
                    for (arg in chain.args) {
                        if (arg is android.content.Intent) {
                            @Suppress("DEPRECATION")
                            val loc = arg.getParcelableExtra<android.location.Location>(android.location.LocationManager.KEY_LOCATION_CHANGED)
                                ?: arg.getParcelableExtra<android.location.Location>("location")
                            if (loc != null) {
                                SystemHookUtils.applyFakeLocationFields(loc, motion, altitude, accuracy)
                            }
                            try {
                                @Suppress("DEPRECATION")
                                val locList = arg.getParcelableArrayListExtra<android.location.Location>("locations")
                                locList?.forEach { SystemHookUtils.applyFakeLocationFields(it, motion, altitude, accuracy) }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
    }
    logLoc("[SysLoc] PendingIntent.send hooked for location intent rewrite")
}

/** Hook IGnssStatusListener 实例的 onSvStatusChanged 回调，注入 20+ 真实卫星矩阵 */
private fun LocationHooker.hookGnssStatusListenerCallback(listener: Any) {
    val clazz = listener.javaClass
    if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return

    try {
        XposedHelpers.hookAllMethods(clazz, "onSvStatusChanged") { chain, _ ->
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val args = chain.args
                val satCount = config.optInt("satellite_count", 20)
                val enableJitter = config.optBoolean("enable_jitter", true)

                if (args.size == 1) {
                    // Android 11+ 标准：GnssStatus 单参数
                    val fakeStatus = GnssFastMockEngine.getOrCreateSpoofedGnssStatus(
                        clazz.classLoader ?: ClassLoader.getSystemClassLoader(),
                        satCount,
                        enableJitter,
                        args[0]
                    )
                    if (fakeStatus != null) {
                        return@hookAllMethods chain.proceed(arrayOf(fakeStatus))
                    }
                } else if (args.size >= 6) {
                    // Android 7~10 兼容：6 个数组参数 (svCount, svidWithFlags, cn0DbHz, elevations, azimuths, carrierFrequencies)
                    val svCount = satCount.coerceIn(4, 32)
                    val svidWithFlags = IntArray(svCount)
                    val cn0DbHz = FloatArray(svCount)
                    val elevations = FloatArray(svCount)
                    val azimuths = FloatArray(svCount)
                    val carrierFrequencies = FloatArray(svCount)

                    val rng = java.util.Random()
                    for (i in 0 until svCount) {
                        val svid = when {
                            i < 10 -> i + 1
                            i < 16 -> (i - 10) + 201
                            else -> (i - 16) + 65
                        }
                        svidWithFlags[i] = (svid shl 4) or 0x07
                        cn0DbHz[i] = 30f + rng.nextFloat() * 12f
                        elevations[i] = 20f + rng.nextFloat() * 65f
                        azimuths[i] = rng.nextFloat() * 360f
                        carrierFrequencies[i] = 1575420000f
                    }

                    val newArgs = args.toMutableList()
                    newArgs[0] = svCount
                    newArgs[1] = svidWithFlags
                    newArgs[2] = cn0DbHz
                    newArgs[3] = elevations
                    newArgs[4] = azimuths
                    newArgs[5] = carrierFrequencies
                    return@hookAllMethods chain.proceed(newArgs.toTypedArray())
                }
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
    } catch (_: Throwable) {}
}

/** 吞掉真实 GNSS 测量回调 */
private fun suppressCallbackMethods(callback: Any, vararg methodNames: String) {
    val clazz = callback.javaClass
    for (mName in methodNames) {
        try {
            XposedHelpers.hookAllMethods(clazz, mName) { innerChain, _ ->
                return@hookAllMethods null
            }
        } catch (_: Throwable) {}
    }
}
