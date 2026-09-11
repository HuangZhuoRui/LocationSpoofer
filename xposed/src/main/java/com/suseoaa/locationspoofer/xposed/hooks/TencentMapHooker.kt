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
 * 腾讯地图定位 SDK (TencentLocation) 专项拦截
 */
internal fun LocationHooker.hookTencentSDK(classLoader: ClassLoader) {
    // 腾讯SDK已知的实现类名(按优先级排列)
    val implCandidates = listOf(
        "com.tencent.map.geolocation.internal.TencentLocationImpl",
        "com.tencent.map.geolocation.TencentLocationImpl",
        "com.tencent.tencentmap.mapsdk.map.model.TencentLocationImpl"
    )

    // 阶段1: 尝试直接Hook已知实现类
    var hooked = false
    for (implClass in implCandidates) {
        val clazz = XposedHelpers.findClassIfExists(implClass, classLoader)
        if (clazz != null) {
            if (hookedCallbackClasses.putIfAbsent(clazz, true) == null) {
                hookTencentLocationClass(clazz, classLoader)
            }
            hooked = true
            XposedBridge.log("[LocationSpoofer] TencentLocation impl found: $implClass")
            break
        }
    }

    // 阶段2: 若已知类名均不存在,尝试通过接口反向查找
    if (!hooked) {
        val interfaceClazz = XposedHelpers.findClassIfExists(
            "com.tencent.map.geolocation.TencentLocation", classLoader
        )
        if (interfaceClazz != null && interfaceClazz.isInterface) {
            if (hookedCallbackClasses.putIfAbsent(interfaceClazz, true) == null) {
                hookTencentLocationCallback(classLoader)
            }
            hooked = true
        } else if (interfaceClazz != null) {
            if (hookedCallbackClasses.putIfAbsent(interfaceClazz, true) == null) {
                hookTencentLocationClass(interfaceClazz, classLoader)
            }
            hooked = true
        }
    }

    if (!hooked) {
        XposedBridge.log("[LocationSpoofer] TencentLocation SDK not found, skipped")
    }

    // 捕获 Listener 实例以便后续主动推送
    val tencentManagerClass = XposedHelpers.findClassIfExists(
        "com.tencent.map.geolocation.TencentLocationManager", classLoader
    )
    if (tencentManagerClass != null && hookedCallbackClasses.putIfAbsent(tencentManagerClass, true) == null) {
        try {
            XposedHelpers.hookAllMethods(
                tencentManagerClass,
                "requestLocationUpdates"
            ) { chain, method ->
                // 监听器通常是第二个参数，但我们会查找任何实现了监听器接口的参数
                for (arg in chain.args) {
                    if (arg != null) {
                        try {
                            if (LocationHooker.hasTypeByName(
                                    arg.javaClass,
                                    "com.tencent.map.geolocation.TencentLocationListener"
                                )
                            ) {
                                capturedTencentListeners.addIfAbsent(arg)
                            }
                        } catch (e: Throwable) {
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) {
        }
    }
}

/**
 * 对TencentLocation的具体实现类进行方法Hook
 */
internal fun LocationHooker.hookTencentLocationClass(clazz: Class<*>, classLoader: ClassLoader) {
    try {
        // hookAllMethods: 不管方法签名如何变化,只要方法名匹配就Hook
        XposedHelpers.hookAllMethods(clazz, "getLatitude") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val motion = getCurrentSpoofedMotion("GCJ-02")
            if (motion != null) {
                result = motion.lat
            }
            return@hookAllMethods result
        }
        XposedHelpers.hookAllMethods(clazz, "getLongitude") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val motion = getCurrentSpoofedMotion("GCJ-02")
            if (motion != null) {
                result = motion.lng
            }
            return@hookAllMethods result
        }
        XposedHelpers.hookAllMethods(clazz, "getSpeed") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val motion = RouteEngine.calculateCurrentPosition(config)
                result = motion.speed
            }
            return@hookAllMethods result
        }
        XposedHelpers.hookAllMethods(clazz, "getBearing") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val motion = RouteEngine.calculateCurrentPosition(config)
                result = motion.bearing
            }
            return@hookAllMethods result
        }
        XposedHelpers.hookAllMethods(clazz, "getTime") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = System.currentTimeMillis()
            }
            return@hookAllMethods result
        }
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer] TencentLocation class hook failed: $e")
        return
    }

    // 动态保留网络定位提供者标识，避免室内强行返回GPS引发风控检测
    try {
        XposedHelpers.hookAllMethods(clazz, "getProvider") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val originalProvider = result as? String ?: "gps"
                // 腾讯地图SDK的定位提供者通常也是"gps"或者"network"
                if (originalProvider == "network" || originalProvider.contains(
                        "wifi",
                        ignoreCase = true
                    )
                ) {
                    result = originalProvider
                } else {
                    result = "gps" // 默认强制修改为GPS定位
                }
            }
            return@hookAllMethods result
        }
    } catch (e: Throwable) { /* 忽略 */
    }

    try {
        XposedHelpers.hookAllMethods(clazz, "getAccuracy") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = getJitteredAccuracy()
            }
            return@hookAllMethods result
        }
    } catch (e: Throwable) { /* 忽略 */
    }

    try {
        XposedHelpers.hookAllMethods(clazz, "isMockGps") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                result = 0
            }
            return@hookAllMethods result
        }
    } catch (e: Throwable) { /* 忽略 */
    }

    // 注入模拟的地址字符串，防止目标 App 抛出定位失败等空指针异常
    hookAddressFields(clazz, classLoader)

    XposedBridge.log("[LocationSpoofer] TencentLocation hooks installed on ${clazz.name}")
}

/**
 * 通过拦截TencentLocationListener回调来修改坐标
 *
 * 当无法直接Hook TencentLocation实现类时的降级方案:
 * Hook TencentLocationListener.onLocationChanged(TencentLocation, int, String)回调,
 * 在回调触发时通过反射修改TencentLocation实例的内部字段。
 */
internal fun LocationHooker.hookTencentLocationCallback(classLoader: ClassLoader) {
    val listenerClass = XposedHelpers.findClassIfExists(
        "com.tencent.map.geolocation.TencentLocationListener", classLoader
    ) ?: return

    try {
        // hookAllMethods可以Hook接口的所有实现类中的方法
        XposedHelpers.hookAllMethods(listenerClass, "onLocationChanged") { chain, method ->
            val config = readConfig()
            if (config == null || !config.optBoolean("active", false)) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            val tencentLoc =
                chain.args.getOrNull(0) ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            val motion = getCurrentSpoofedMotion("GCJ-02") ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())

            // 通过反射直接写入TencentLocation实现类的经纬度字段
            try {
                XposedHelpers.callMethod(tencentLoc, "setLatitude", motion.lat)
            } catch (e: Throwable) {
                try {
                    XposedHelpers.setDoubleField(tencentLoc, "latitude", motion.lat)
                } catch (e2: Throwable) {
                    try {
                        XposedHelpers.setDoubleField(
                            tencentLoc,
                            "mLatitude",
                            motion.lat
                        )
                    } catch (e3: Throwable) {
                        try {
                            XposedHelpers.setDoubleField(
                                tencentLoc,
                                "a",
                                motion.lat
                            )
                        } catch (e4: Throwable) {
                        }
                    }
                }
            }
            try {
                XposedHelpers.callMethod(tencentLoc, "setLongitude", motion.lng)
            } catch (e: Throwable) {
                try {
                    XposedHelpers.setDoubleField(
                        tencentLoc,
                        "longitude",
                        motion.lng
                    )
                } catch (e2: Throwable) {
                    try {
                        XposedHelpers.setDoubleField(
                            tencentLoc,
                            "mLongitude",
                            motion.lng
                        )
                    } catch (e3: Throwable) {
                        try {
                            XposedHelpers.setDoubleField(
                                tencentLoc,
                                "b",
                                motion.lng
                            )
                        } catch (e4: Throwable) {
                        }
                    }
                }
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        XposedBridge.log("[LocationSpoofer] TencentLocationListener callback hook installed")
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer] TencentLocationListener hook failed: $e")
    }
}
