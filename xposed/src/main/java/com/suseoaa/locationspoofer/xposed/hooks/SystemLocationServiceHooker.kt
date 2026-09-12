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

import android.os.IBinder
import android.os.IInterface
import android.util.Log
import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.*
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

// 存储被标记为目标应用的 ILocationListener / ILocationCallback 实例
private val spoofedListenerInstances: MutableSet<Any> =
    Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

// 存储当前处于活跃状态的目标应用 ILocationListener 代理对象及其 Binder 映射
private data class ListenerRegistrationInfo(
    val listenerRef: WeakReference<Any>,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis()
)

private val activeListenerBinders = ConcurrentHashMap<IBinder, ListenerRegistrationInfo>()

@Volatile
private var isHeartbeatTimerStarted = false

/** 在 args 里递归找出所有 android.location.Location（单个或 List 批量形态）并改写 */
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
                val isTracked = (binder != null && activeListenerBinders.containsKey(binder)) ||
                        spoofedListenerInstances.contains(innerChain.thisObject)
                if (isTracked) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val motion = getCurrentSpoofedMotion("WGS-84")
                        if (motion != null) {
                            rewriteLocationArgs(
                                innerChain.args,
                                motion,
                                config.optDouble("altitude", 25.0),
                                getJitteredAccuracy()
                            )
                            XposedBridge.logOpenCellIdEvery(
                                "sys_callback_rewrite_${innerChain.thisObject.javaClass.simpleName}",
                                "[SysHook] Rewrote location args in callback $methodName"
                            )
                        }
                    }
                }
                return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
}

/**
 * 启动 system_server 内部的主动心跳推送定时器
 * 当目标应用注册了 requestLocationUpdates，但真实 GPS 在室内或关闭导致没有原生更新时，
 * 此定时器主动向客户端 Binder 投递平滑的伪造坐标，避免 App 持续挂起。
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
                if (activeListenerBinders.isEmpty()) return
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return

                val motion = getCurrentSpoofedMotion("WGS-84") ?: return
                val altitude = config.optDouble("altitude", 25.0)
                val accuracy = getJitteredAccuracy()

                val fakeLoc = SystemHookUtils.buildFakeLocation(
                    classLoader,
                    android.location.LocationManager.GPS_PROVIDER,
                    motion,
                    altitude,
                    accuracy
                ) ?: return

                for ((binder, info) in activeListenerBinders) {
                    if (!binder.isBinderAlive) {
                        activeListenerBinders.remove(binder)
                        continue
                    }
                    val listener = info.listenerRef.get()
                    if (listener != null) {
                        try {
                            dispatchFakeLocationToListener(listener, fakeLoc)
                        } catch (e: Throwable) {
                            activeListenerBinders.remove(binder)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
    }, 1000L, 1000L)
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
        batchMethod.invoke(listener, *args)
    }
}

internal fun LocationHooker.hookSystemLocationService(classLoader: ClassLoader) {
    val serviceClazz = XposedHelpers.findClassIfExists(
        "com.android.server.location.LocationManagerService", classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.server.LocationManagerService", classLoader
    )

    if (serviceClazz == null) {
        Log.e("LocationSpoofer", "[SysHook] LocationManagerService not found, skip system-level location hook")
        return
    }
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
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

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
                config.optDouble("altitude", 25.0),
                getJitteredAccuracy()
            )

            if (fakeLoc != null) {
                Log.i(
                    "LocationSpoofer",
                    "[SysHook] Injected fake location for ${explicitPkg ?: "caller"} (getLastLocation): lat=${motion.lat}, lng=${motion.lng}, provider=$provider"
                )
                return@hookAllMethods fakeLoc
            }

            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        Log.i("LocationSpoofer", "[SysHook] LocationManagerService.getLastLocation hooked")
    } catch (e: Throwable) {
        Log.e("LocationSpoofer", "[SysHook] hook getLastLocation failed: $e")
    }

    // =========================================================================
    // 2. registerLocationListener (Android 12+) & requestLocationUpdates (Android 8-11)
    // =========================================================================
    val listenerRegisterMethodNames = arrayOf("registerLocationListener", "requestLocationUpdates")
    for (methodName in listenerRegisterMethodNames) {
        try {
            XposedHelpers.hookAllMethods(serviceClazz, methodName) { chain, _ ->
                try {
                    val config = readConfig()
                    if (config != null) {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

                        if (isTarget) {
                            val listener = chain.args.firstOrNull { arg ->
                                arg != null && LocationHooker.hasTypeByName(
                                    arg.javaClass,
                                    "android.location.ILocationListener"
                                )
                            }
                            if (listener != null) {
                                val binder = (listener as? IInterface)?.asBinder() ?: (listener as? IBinder)
                                if (binder != null) {
                                    val pkgName = explicitPkg ?: "unknown"
                                    activeListenerBinders[binder] = ListenerRegistrationInfo(
                                        listenerRef = WeakReference(listener),
                                        packageName = pkgName
                                    )
                                    try {
                                        binder.linkToDeath({
                                            activeListenerBinders.remove(binder)
                                            Log.i("LocationSpoofer", "[SysHook] Listener died and removed for $pkgName")
                                        }, 0)
                                    } catch (_: Throwable) {}
                                }
                                spoofedListenerInstances.add(listener)
                                ensureCallbackHooked(listener, "onLocationChanged")

                                Log.i(
                                    "LocationSpoofer",
                                    "[SysHook] Registered target location listener for ${explicitPkg ?: "caller"} (total active: ${activeListenerBinders.size})"
                                )
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("LocationSpoofer", "[SysHook] $methodName pre-hook error: $e")
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            Log.i("LocationSpoofer", "[SysHook] LocationManagerService.$methodName hooked")
        } catch (e: Throwable) {
            Log.e("LocationSpoofer", "[SysHook] hook $methodName failed: $e")
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
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

                    if (isTarget) {
                        val callback = chain.args.firstOrNull { arg ->
                            arg != null && LocationHooker.hasTypeByName(
                                arg.javaClass,
                                "android.location.ILocationCallback"
                            )
                        }
                        if (callback != null) {
                            spoofedListenerInstances.add(callback)
                            ensureCallbackHooked(callback, "onLocation")
                            Log.i(
                                "LocationSpoofer",
                                "[SysHook] Flagged getCurrentLocation callback for ${explicitPkg ?: "caller"}"
                            )

                            // 主动推送单次定位结果，防止在室内真实 GPS 未锁定导致目标 App 持续等待超时
                            val motion = getCurrentSpoofedMotion("WGS-84")
                            if (motion != null) {
                                val provider = SystemHookUtils.extractProvider(chain.args)
                                val fakeLoc = SystemHookUtils.buildFakeLocation(
                                    classLoader,
                                    provider,
                                    motion,
                                    config.optDouble("altitude", 25.0),
                                    getJitteredAccuracy()
                                )
                                if (fakeLoc != null) {
                                    val onLocationMethod = callback.javaClass.methods.firstOrNull {
                                        it.name == "onLocation" && it.parameterTypes.size == 1
                                    }
                                    if (onLocationMethod != null) {
                                        try {
                                            onLocationMethod.invoke(callback, fakeLoc)
                                            Log.i(
                                                "LocationSpoofer",
                                                "[SysHook] Proactively dispatched fake location to getCurrentLocation callback for ${explicitPkg ?: "caller"}"
                                            )
                                        } catch (t: Throwable) {
                                            Log.e("LocationSpoofer", "[SysHook] proactive callback invoke failed: $t")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e("LocationSpoofer", "[SysHook] getCurrentLocation pre-hook failed: $e")
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        Log.i("LocationSpoofer", "[SysHook] LocationManagerService.getCurrentLocation hooked")
    } catch (e: Throwable) {
        Log.e("LocationSpoofer", "[SysHook] hook getCurrentLocation failed: $e")
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
                    if (providerArg == "gps") {
                        val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                        if (SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)) {
                            return@hookAllMethods true
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 6. GNSS 卫星与 NMEA 拦截 (registerGnssStatusCallback & addNmeaListener)
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(serviceClazz, "registerGnssStatusCallback") { chain, _ ->
            try {
                val config = readConfig()
                if (config != null) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    if (SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)) {
                        val callback = chain.args.firstOrNull { arg ->
                            arg != null && (LocationHooker.hasTypeByName(
                                arg.javaClass,
                                "android.location.IGnssStatusListener"
                            ) || LocationHooker.hasTypeByName(
                                arg.javaClass,
                                "android.location.IGnssStatusCallback"
                            ))
                        }
                        if (callback != null) {
                            hookGnssStatusListenerCallback(callback)
                            Log.i("LocationSpoofer", "[SysHook] Registered GNSS status listener for ${explicitPkg ?: "caller"}")
                        }
                    }
                }
            } catch (_: Throwable) {}
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
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
}

/** Hook IGnssStatusListener 实例的 onSvStatusChanged 回调，注入 20+ 真实卫星矩阵 */
private fun LocationHooker.hookGnssStatusListenerCallback(listener: Any) {
    val clazz = listener.javaClass
    if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return

    try {
        XposedHelpers.hookAllMethods(clazz, "onSvStatusChanged") { chain, _ ->
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                // 伪造 20 颗卫星（GPS 10颗 + 北斗 6颗 + GLONASS 4颗）
                val svCount = 20
                val svidWithFlags = IntArray(svCount)
                val cn0DbHz = FloatArray(svCount)
                val elevations = FloatArray(svCount)
                val azimuths = FloatArray(svCount)
                val carrierFrequencies = FloatArray(svCount)

                val rng = java.util.Random()
                for (i in 0 until svCount) {
                    val svid = when {
                        i < 10 -> i + 1 // GPS: 1-10
                        i < 16 -> (i - 10) + 201 // BDS: 201-206
                        else -> (i - 16) + 65 // GLONASS: 65-68
                    }
                    // 状态标志位: hasEphemeris(1) | hasAlmanac(2) | usedInFix(4) | carrierFrequency(8)
                    svidWithFlags[i] = (svid shl 4) or 0x07
                    cn0DbHz[i] = 25f + rng.nextFloat() * 15f // 25-40 dB-Hz
                    elevations[i] = 15f + rng.nextFloat() * 70f
                    azimuths[i] = rng.nextFloat() * 360f
                    carrierFrequencies[i] = 1575420000f // L1
                }

                // 替换参数列表
                val args = chain.args
                if (args.isNotEmpty()) {
                    val newArgs = args.toMutableList()
                    newArgs[0] = svCount
                    if (newArgs.size > 1) newArgs[1] = svidWithFlags
                    if (newArgs.size > 2) newArgs[2] = cn0DbHz
                    if (newArgs.size > 3) newArgs[3] = elevations
                    if (newArgs.size > 4) newArgs[4] = azimuths
                    if (newArgs.size > 5) newArgs[5] = carrierFrequencies
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
