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
 * 地图 SDK 专项拦截模块 (Map SDK Hooker)
 * 
 * 上下文:
 * 虽然底层 LocationManager 被劫持了，但国内的地图服务商 (高德 AMap, 百度 BDLocation, 腾讯 TencentLocation)
 * 自己封装了庞大且复杂的定位 SDK。它们不仅读取底层 GPS，还会缓存最后一次位置、读取基站和 WiFi、
 * 并自行进行坐标系偏转 (WGS84 -> GCJ02 -> BD09) 以及逆地理编码 (把坐标变成 "XX省XX市" 的文字描述)。
 * 
 * 作用:
 * 专门针对高德、百度、腾讯地图 SDK 的类和回调接口进行 Hook。
 * 关键部分解释:
 * 1. 逆地理编码文本替换 (hookAddressFields): App 请求反查地址时，如果我们在配置里填了 "XX省XX市"，
 *    这个方法就会拦截 `getCity()`, `getProvince()` 等方法，直接用我们的数据覆盖 SDK 查出的真实文本。
 * 2. 坐标系适配: 各种 SDK 期望的输入和输出不同。例如百度地图强行需要 BD-09 坐标系，
 *    如果底层返回了 GCJ-02，这里必须拦截并保证传给百度的正是它想要的格式，否则会在地图上出现数百米的偏移。
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

/**
 * 百度定位SDK深度Hook
 *
 * 百度定位SDK的核心定位回调对象为com.baidu.location.BDLocation。
 * 百度地图使用BD-09坐标系,这是在GCJ-02基础上施加二次偏移的专有坐标系。
 *
 * 关键区别:
 * - 高德/腾讯: 使用GCJ-02,直接返回config中的lat/lng
 * - 百度: 使用BD-09,必须调用gcj02ToBd09()转换后再返回
 *
 * 双重保险策略:
 * 1. 直接Hook BDLocation.getLatitude/getLongitude(方法级拦截)
 * 2. Hook BDAbstractLocationListener.onReceiveLocation回调(回调级拦截)
 * 两者互为补充,确保无论百度SDK内部架构如何变化,BD-09坐标都能正确注入。
 */
internal fun LocationHooker.hookBaiduSDK(classLoader: ClassLoader) {
    val baiduLocClass = "com.baidu.location.BDLocation"

    // 安全探测: 当前进程是否加载了百度定位SDK
    val baiduClazz = XposedHelpers.findClassIfExists(baiduLocClass, classLoader)
    if (baiduClazz != null && hookedCallbackClasses.putIfAbsent(baiduClazz, true) == null) {
        try {
            // 1. 构造函数级拦截：确保任何实例一经创建即填充正确的 BD-09 坐标，防止未初始化或失败默认值 (0.0, 0.0) 泄露
            try {
                XposedHelpers.hookAllConstructors(baiduClazz) { chain, constructor ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val bdLoc = chain.thisObject
                    if (bdLoc != null) {
                        val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val motion = getCurrentSpoofedMotion("BD-09")
                        if (motion != null) {
                            try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "latitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "longitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.setObjectField(bdLoc, "mCoorType", "bd09ll") } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(bdLoc, "mLocType", 61) } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(bdLoc, "locType", 61) } catch (_: Throwable) {}
                        }
                    }
                }
                return@hookAllConstructors result
            }
        } catch (_: Throwable) {}

        // 2. Getter 方法级拦截：BDLocation.getLatitude() / getLongitude() 强制返回标准度数
        XposedHelpers.hookAllMethods(baiduClazz, "getLatitude") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val coorType = try {
                XposedHelpers.callMethod(chain.thisObject!!, "getCoorType") as? String
            } catch (e: Throwable) {
                null
            }
            val defaultSys = when (coorType?.lowercase()) {
                "wgs84" -> "WGS-84"
                "gcj02" -> "GCJ-02"
                else -> "BD-09"
            }
            val motion = getCurrentSpoofedMotion(defaultSys)
            if (motion != null) {
                result = motion.lat
            }
            return@hookAllMethods result
        }

        XposedHelpers.hookAllMethods(baiduClazz, "getLongitude") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val coorType = try {
                XposedHelpers.callMethod(chain.thisObject!!, "getCoorType") as? String
            } catch (e: Throwable) {
                null
            }
            val defaultSys = when (coorType?.lowercase()) {
                "wgs84" -> "WGS-84"
                "gcj02" -> "GCJ-02"
                else -> "BD-09"
            }
            val motion = getCurrentSpoofedMotion(defaultSys)
            if (motion != null) {
                result = motion.lng
            }
            return@hookAllMethods result
        }

        // getCoorType -> 确保返回有效的度数坐标系标识 (bd09ll / gcj02 / wgs84)
        try {
            XposedHelpers.hookAllMethods(baiduClazz, "getCoorType") { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val currentCoor = result as? String
                    if (currentCoor.isNullOrEmpty() || currentCoor == "null" || currentCoor.equals("bd09mc", ignoreCase = true)) {
                        result = "bd09ll"
                    }
                }
                return@hookAllMethods result
            }
        } catch (_: Throwable) {
        }

        // getLocType -> 确保永远返回定位成功(GPS=61或网络=161)，避免百度地图触发兜底回拉
        XposedHelpers.hookAllMethods(baiduClazz, "getLocType") { chain, method ->
            var result = chain.proceed(chain.args.toTypedArray())
            val config = readConfig()
            if (config != null && config.optBoolean("active", false)) {
                val originalLocationType = result as? Int ?: 61
                if (originalLocationType == 161 || originalLocationType == 601) {
                    result = originalLocationType
                } else {
                    result = 61 // 默认强制修改为GPS定位成功（61）
                }
            }
            return@hookAllMethods result
        }

        // getRadius(精度) -> 与全局抖动精度同步 (1.5m - 3.5m 满格绿色信号)
        try {
            XposedHelpers.hookAllMethods(baiduClazz, "getRadius") { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = getJitteredAccuracy()
                }
                return@hookAllMethods result
            }
        } catch (e: Throwable) { /* 忽略 */
        }

        // getSpeed (km/h) & hasSpeed
        try {
            XposedHelpers.hookAllMethods(baiduClazz, "getSpeed") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val motion = RouteEngine.calculateCurrentPosition(config)
                    motion.speed * 3.6f
                } else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(baiduClazz, "hasSpeed") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(baiduClazz, "getDirection") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val motion = RouteEngine.calculateCurrentPosition(config)
                    motion.bearing
                } else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(baiduClazz, "getGpsCheckStatus") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) 1 else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(baiduClazz, "getGpsAccuracyStatus") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) 1 else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(baiduClazz, "hasAddr") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(baiduClazz, "hasAddress") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(baiduClazz, "getTime") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                } else chain.proceed(chain.args.toTypedArray())
            }
        } catch (e: Throwable) { /* 忽略 */
        }

        // getMockGps -> 0(非模拟)
        try {
            XposedHelpers.hookAllMethods(baiduClazz, "getMockGps") { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = 0
                }
                return@hookAllMethods result
            }
        } catch (e: Throwable) { /* 忽略 */
        }

        // getSatelliteNumber -> 18-24颗
        try {
            XposedHelpers.hookAllMethods(baiduClazz, "getSatelliteNumber") { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = config.optInt("satellite_count", 20)
                }
                return@hookAllMethods result
            }
        } catch (e: Throwable) { /* 忽略 */
        }

        // 注入模拟的地址字符串，防止目标 App 抛出定位失败等空指针异常
        hookAddressFields(baiduClazz, classLoader)

        XposedBridge.log("[LocationSpoofer] BDLocation method hooks installed")
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer] BDLocation method hook failed: $e")
    }
    }

    // 方案2(补充): Hook百度定位回调,在App接收BDLocation前修改其内部字段
    // BDAbstractLocationListener是百度SDK 7.0+推荐的回调基类
    val listenerCandidates = listOf(
        "com.baidu.location.BDAbstractLocationListener",
        "com.baidu.location.BDLocationListener"
    )
    for (listenerClassName in listenerCandidates) {
        val listenerClazz =
            XposedHelpers.findClassIfExists(listenerClassName, classLoader) ?: continue
        try {
            XposedHelpers.hookAllMethods(listenerClazz, "onReceiveLocation") { chain, method ->
                val config =
                    readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                if (!config.optBoolean(
                        "active",
                        false
                    )
                ) return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                if (chain.args.isEmpty()) return@hookAllMethods chain.proceed(chain.args.toTypedArray())

                val bdLoc =
                    chain.args[0] ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())

                val coorType = try {
                    XposedHelpers.callMethod(bdLoc, "getCoorType") as? String
                } catch (e: Throwable) {
                    null
                }

                val defaultSys = when (coorType?.lowercase()) {
                    "wgs84" -> "WGS-84"
                    "gcj02" -> "GCJ-02"
                    else -> "BD-09"
                }
                val motion = getCurrentSpoofedMotion(defaultSys) ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())

                // 1. 经纬度
                try { XposedHelpers.callMethod(bdLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "latitude", motion.lat) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "longitude", motion.lng) } catch (_: Throwable) {}

                // 2. 状态码与坐标系
                try { XposedHelpers.callMethod(bdLoc, "setLocType", 61) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(bdLoc, "mLocType", 61) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(bdLoc, "locType", 61) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setCoorType", "bd09ll") } catch (_: Throwable) {}
                try { XposedHelpers.setObjectField(bdLoc, "mCoorType", "bd09ll") } catch (_: Throwable) {}

                // 3. 卫星、精度与时间
                try { XposedHelpers.callMethod(bdLoc, "setRadius", getJitteredAccuracy()) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setSpeed", motion.speed * 3.6f) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setDirection", motion.bearing) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setSatelliteNumber", 20) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setGpsCheckStatus", 1) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setMockGps", 0) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setTime", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())) } catch (_: Throwable) {}

                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            XposedBridge.log("[LocationSpoofer] $listenerClassName callback hook installed")
        } catch (e: Throwable) { /* 忽略 */
        }
    }

    // 3. 捕获 Listener 实例以便后续主动推送
    val locationClientClass = XposedHelpers.findClassIfExists(
        "com.baidu.location.LocationClient", classLoader
    )
    if (locationClientClass != null) {
        try {
            XposedHelpers.hookAllMethods(
                locationClientClass,
                "registerLocationListener"
            ) { chain, method ->
                val listener = chain.args[0]
                if (listener != null) {
                    try {
                        val listenerClazz = listener.javaClass
                        if (hookedCallbackClasses.putIfAbsent(listenerClazz, true) == null) {
                            try {
                                XposedHelpers.hookAllMethods(listenerClazz, "onReceiveLocation") { innerChain, innerMethod ->
                                    val config = readConfig() ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    if (!config.optBoolean("active", false) || innerChain.args.isEmpty()) return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    val bdLoc = innerChain.args[0] ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    val coorType = try { XposedHelpers.callMethod(bdLoc, "getCoorType") as? String } catch (_: Throwable) { null }
                                    val defaultSys = when (coorType?.lowercase()) {
                                        "wgs84" -> "WGS-84"
                                        "gcj02" -> "GCJ-02"
                                        else -> "BD-09"
                                    }
                                    val motion = getCurrentSpoofedMotion(defaultSys) ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    try { XposedHelpers.callMethod(bdLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setLocType", 61) } catch (_: Throwable) {}
                                    try { XposedHelpers.setIntField(bdLoc, "mLocType", 61) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setCoorType", "bd09ll") } catch (_: Throwable) {}
                                    try { XposedHelpers.setObjectField(bdLoc, "mCoorType", "bd09ll") } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setRadius", getJitteredAccuracy()) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setSpeed", motion.speed * 3.6f) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setDirection", motion.bearing) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setSatelliteNumber", 20) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setGpsCheckStatus", 1) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setMockGps", 0) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setTime", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())) } catch (_: Throwable) {}
                                    return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                }
                            } catch (_: Throwable) {}
                        }
                        if (LocationHooker.hasTypeByName(
                                listener.javaClass,
                                "com.baidu.location.BDAbstractLocationListener"
                            ) || LocationHooker.hasTypeByName(
                                listener.javaClass,
                                "com.baidu.location.BDLocationListener"
                            )
                        ) {
                            capturedBaiduListeners.addIfAbsent(listener)
                        }
                    } catch (e: Throwable) {
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(
                locationClientClass,
                "unRegisterLocationListener"
            ) { chain, method ->
                val listener = chain.args[0]
                if (listener != null) {
                    capturedBaiduListeners.remove(listener)
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(
                locationClientClass,
                "getLastKnownLocation"
            ) { chain, method ->
                val result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val motion = getCurrentSpoofedMotion("BD-09")
                    if (motion != null) {
                        val bdLoc = result ?: try {
                            val bdLocClass = XposedHelpers.findClass("com.baidu.location.BDLocation", classLoader)
                            bdLocClass.getConstructor().newInstance()
                        } catch (_: Throwable) { null }
                        if (bdLoc != null) {
                            try { XposedHelpers.callMethod(bdLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setLocType", 61) } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(bdLoc, "mLocType", 61) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setCoorType", "bd09ll") } catch (_: Throwable) {}
                            try { XposedHelpers.setObjectField(bdLoc, "mCoorType", "bd09ll") } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setRadius", getJitteredAccuracy()) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setSatelliteNumber", 20) } catch (_: Throwable) {}
                            return@hookAllMethods bdLoc
                        }
                    }
                }
                return@hookAllMethods result
            }
        } catch (e: Throwable) {
        }
    }

    // 4. Hook com.baidu.mapapi.map.MyLocationData 与 BaiduMap.setMyLocationData (百度地图视图层直接绘制)
    val myLocationDataClass = XposedHelpers.findClassIfExists("com.baidu.mapapi.map.MyLocationData", classLoader)
    if (myLocationDataClass != null && hookedCallbackClasses.putIfAbsent(myLocationDataClass, true) == null) {
        try {
            XposedHelpers.hookAllConstructors(myLocationDataClass) { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                val obj = chain.thisObject
                if (obj != null) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val motion = getCurrentSpoofedMotion("BD-09")
                        if (motion != null) {
                            try { XposedHelpers.setDoubleField(obj, "latitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(obj, "longitude", motion.lng) } catch (_: Throwable) {}
                        }
                    }
                }
                return@hookAllConstructors result
            }
        } catch (_: Throwable) {}
    }

    val baiduMapClass = XposedHelpers.findClassIfExists("com.baidu.mapapi.map.BaiduMap", classLoader)
    if (baiduMapClass != null && hookedCallbackClasses.putIfAbsent(baiduMapClass, true) == null) {
        try {
            XposedHelpers.hookAllMethods(baiduMapClass, "setMyLocationData") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && chain.args.isNotEmpty()) {
                    val data = chain.args[0]
                    if (data != null) {
                        val motion = getCurrentSpoofedMotion("BD-09")
                        if (motion != null) {
                            try { XposedHelpers.setDoubleField(data, "latitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(data, "longitude", motion.lng) } catch (_: Throwable) {}
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
}

/**
 * 高德地图定位 SDK 深度拦截模块 (AMap Location SDK Hooker)
 *
 * 核心逻辑:
 * 1. AMapLocation 继承自 android.location.Location，其默认设计即为 GCJ-02 坐标系。
 * 2. 构造函数拦截：初始化即填充 GCJ-02 坐标与有效状态，防止初始 (0.0, 0.0) 泄露。
 * 3. 方法级拦截：getLatitude/getLongitude 强制返回 getCurrentSpoofedMotion("GCJ-02")。
 * 4. 回调拦截：对 AMapLocationClient.setLocationListener 注册的 AMapLocationListener 实例的
 *    onLocationChanged(AMapLocation) 进行拦截并注入最新 GCJ-02 坐标。
 * 5. 反作弊拦截：AMapLocationQualityReport 报告清零与 setMockEnable(true) 欺骗。
 */
internal fun LocationHooker.hookAMapSDK(classLoader: ClassLoader) {
    val amapLocClass = "com.amap.api.location.AMapLocation"
    val amapLocClazz = XposedHelpers.findClassIfExists(amapLocClass, classLoader)

    if (amapLocClazz != null && hookedCallbackClasses.putIfAbsent(amapLocClazz, true) == null) {
        try {
            // 1. 构造函数级拦截：确保任何实例创建时即具备 GCJ-02 经纬度
            try {
                XposedHelpers.hookAllConstructors(amapLocClazz) { chain, _ ->
                    val result = chain.proceed(chain.args.toTypedArray())
                    val amapLoc = chain.thisObject
                    if (amapLoc != null) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val motion = getCurrentSpoofedMotion("GCJ-02")
                            if (motion != null) {
                                try { XposedHelpers.callMethod(amapLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                                try { XposedHelpers.callMethod(amapLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                                try { XposedHelpers.setDoubleField(amapLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                                try { XposedHelpers.setDoubleField(amapLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                                try { XposedHelpers.callMethod(amapLoc, "setLocationType", 1) } catch (_: Throwable) {}
                                try { XposedHelpers.callMethod(amapLoc, "setGpsAccuracyStatus", 1) } catch (_: Throwable) {}
                                try { XposedHelpers.callMethod(amapLoc, "setSatellites", config.optInt("satellite_count", 20)) } catch (_: Throwable) {}
                            }
                        }
                    }
                    return@hookAllConstructors result
                }
            } catch (_: Throwable) {}

            // 2. Getter 方法级拦截
            XposedHelpers.hookAllMethods(amapLocClazz, "getLatitude") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                if (currentPackageName.substringBefore(":") == "com.suseoaa.locationspoofer") return@hookAllMethods result
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.lat
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getLongitude") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                if (currentPackageName.substringBefore(":") == "com.suseoaa.locationspoofer") return@hookAllMethods result
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.lng
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getAccuracy") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                if (currentPackageName.substringBefore(":") == "com.suseoaa.locationspoofer") return@hookAllMethods result
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = getJitteredAccuracy()
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getSpeed") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                if (currentPackageName.substringBefore(":") == "com.suseoaa.locationspoofer") return@hookAllMethods result
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.speed
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getBearing") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                if (currentPackageName.substringBefore(":") == "com.suseoaa.locationspoofer") return@hookAllMethods result
                val motion = getCurrentSpoofedMotion("GCJ-02")
                if (motion != null) {
                    result = motion.bearing
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getSatellites") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = config.optInt("satellite_count", 20)
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getGpsAccuracyStatus") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = 1
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getLocationType") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = 1 // 1: GPS 定位结果
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getAltitude") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = config.optDouble("altitude", 25.0)
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getTime") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = System.currentTimeMillis()
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getErrorCode") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = 0 // 0: 定位成功
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "isMock") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = false
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "isFixLastLocation") { chain, _ ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    result = false
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(amapLocClazz, "getMockData") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    return@hookAllMethods null
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            hookAddressFields(amapLocClazz, classLoader)
            XposedBridge.log("[LocationSpoofer] AMapLocation hooks installed on ${amapLocClazz.name}")
        } catch (e: Throwable) {
            XposedBridge.log("[LocationSpoofer] AMapLocation hook failed: $e")
        }
    }

    // 3. Hook AMapLocationClient 注册回调与质量报告
    val clientClazz = XposedHelpers.findClassIfExists(
        "com.amap.api.location.AMapLocationClient", classLoader
    )
    if (clientClazz != null) {
        try {
            XposedHelpers.hookAllMethods(clientClazz, "setLocationListener") { chain, _ ->
                val listener = chain.args.getOrNull(0)
                if (listener != null) {
                    try {
                        val listenerClazz = listener.javaClass
                        if (hookedCallbackClasses.putIfAbsent(listenerClazz, true) == null) {
                            try {
                                XposedHelpers.hookAllMethods(listenerClazz, "onLocationChanged") { innerChain, _ ->
                                    val config = readConfig() ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    if (!config.optBoolean("active", false) || innerChain.args.isEmpty()) return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    val amapLoc = innerChain.args[0] ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    val motion = getCurrentSpoofedMotion("GCJ-02") ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    try { XposedHelpers.callMethod(amapLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(amapLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(amapLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setAccuracy", getJitteredAccuracy()) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setSpeed", motion.speed) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setBearing", motion.bearing) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setAltitude", config.optDouble("altitude", 25.0)) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setTime", System.currentTimeMillis()) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setSatellites", config.optInt("satellite_count", 20)) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setGpsAccuracyStatus", 1) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(amapLoc, "setLocationType", 1) } catch (_: Throwable) {}
                                    return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                }
                            } catch (_: Throwable) {}
                        }
                        if (LocationHooker.hasTypeByName(listener.javaClass, "com.amap.api.location.AMapLocationListener")) {
                            capturedAMapListeners.addIfAbsent(listener)
                        }
                    } catch (_: Throwable) {}
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(clientClazz, "unRegisterLocationListener") { chain, _ ->
                val listener = chain.args.getOrNull(0)
                if (listener != null) {
                    capturedAMapListeners.remove(listener)
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            XposedHelpers.hookAllMethods(clientClazz, "getLastKnownLocation") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val motion = getCurrentSpoofedMotion("GCJ-02")
                    if (motion != null) {
                        val amapLoc = result ?: try {
                            val locCls = XposedHelpers.findClass("com.amap.api.location.AMapLocation", classLoader)
                            locCls.getConstructor(String::class.java).newInstance("gps")
                        } catch (_: Throwable) { null }
                        if (amapLoc != null) {
                            try { XposedHelpers.callMethod(amapLoc, "setLatitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setLongitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(amapLoc, "mLatitude", motion.lat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(amapLoc, "mLongitude", motion.lng) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setLocationType", 1) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setGpsAccuracyStatus", 1) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setSatellites", config.optInt("satellite_count", 20)) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(amapLoc, "setRadius", getJitteredAccuracy()) } catch (_: Throwable) {}
                            return@hookAllMethods amapLoc
                        }
                    }
                }
                return@hookAllMethods result
            }

            XposedHelpers.hookAllMethods(clientClazz, "setMockEnable") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && chain.args.isNotEmpty()) {
                    chain.args[0] = true
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // 4. AMapLocationQualityReport 质量报告清零
    val reportClazz = XposedHelpers.findClassIfExists(
        "com.amap.api.location.AMapLocationQualityReport", classLoader
    )
    if (reportClazz != null) {
        try {
            XposedHelpers.hookAllMethods(reportClazz, "isWifiAble") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) true else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(reportClazz, "isInstalledHighDangerMockApp") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) false else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(reportClazz, "getGPSSatellites") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) config.optInt("satellite_count", 20) else chain.proceed(chain.args.toTypedArray())
            }
            XposedHelpers.hookAllMethods(reportClazz, "getGPSStatus") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) 0 else chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
}

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
