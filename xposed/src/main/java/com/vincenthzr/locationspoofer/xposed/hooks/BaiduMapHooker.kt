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
/**
 * 根据 BDLocation 原本的 coorType 决定：用哪个坐标系计算模拟位置(defaultSys)，
 * 以及是否需要把算出来的 BD-09LL(角度) 进一步转换成 BD-09MC(墨卡托米制)再注入。
 *
 * 部分 App 不走百度自带 MapView、自己画地图，会把定位 SDK 配置成 bd09mc 拿投影米制坐标做像素定位。
 * 如果这里不区分、始终按角度量级的数字注入（比如纬度 39.9），这些 App 会把它当成
 * "距投影原点北偏 39.9 米"，反算回真实经纬度正好落在赤道与本初子午线交点——
 * 也就是"模拟定位被拉到几内亚湾"这个历史遗留 bug的根因。
 */
private fun resolveBaiduCoordSystem(coorType: String?): Pair<String, Boolean> {
    // 排查"被拉到几内亚湾"用：按 coorType 的取值限流打印，正常运行时每种取值最多 10 秒打一条，
    // 用来确认目标 App 的 BDLocation 实例实际报的 coorType 到底是什么，而不是靠猜。
    XposedBridge.logOpenCellIdEvery(
        "baidu_coorType_${coorType ?: "null"}",
        "resolveBaiduCoordSystem coorType=$coorType"
    )
    return when (coorType?.lowercase()) {
        "wgs84" -> "WGS-84" to false
        "gcj02" -> "GCJ-02" to false
        "bd09mc" -> "BD-09" to true
        else -> "BD-09" to false
    }
}

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
                        // 拷贝构造函数等场景下，真实构造逻辑可能已经把 coorType 从源对象带过来了；
                        // 在覆盖前先读一次，而不是无脑假设都是 bd09ll。
                        val existingCoorType = try {
                            XposedHelpers.callMethod(bdLoc, "getCoorType") as? String
                        } catch (_: Throwable) { null }
                        val (defaultSys, isMercator) = resolveBaiduCoordSystem(existingCoorType)
                        val motion = getCurrentSpoofedMotion(defaultSys)
                        if (motion != null) {
                            val (injLat, injLng) = if (isMercator) {
                                bd09llToBd09mc(motion.lat, motion.lng)
                            } else {
                                motion.lat to motion.lng
                            }
                            try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", injLat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", injLng) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "latitude", injLat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "longitude", injLng) } catch (_: Throwable) {}
                            try { XposedHelpers.setObjectField(bdLoc, "mCoorType", if (isMercator) "bd09mc" else "bd09ll") } catch (_: Throwable) {}
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
            val (defaultSys, isMercator) = resolveBaiduCoordSystem(coorType)
            val motion = getCurrentSpoofedMotion(defaultSys)
            if (motion != null) {
                result = if (isMercator) bd09llToBd09mc(motion.lat, motion.lng).first else motion.lat
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
            val (defaultSys, isMercator) = resolveBaiduCoordSystem(coorType)
            val motion = getCurrentSpoofedMotion(defaultSys)
            if (motion != null) {
                result = if (isMercator) bd09llToBd09mc(motion.lat, motion.lng).second else motion.lng
            }
            return@hookAllMethods result
        }

        // getCoorType -> 只兜底缺失/无效值为 bd09ll，不再把合法的 bd09mc(墨卡托米制) 强行改写成
        // bd09ll(角度)——那样会让 getLatitude/getLongitude 注入的角度量级数字被误当成米制坐标，
        // 导致模拟定位被"拉到"投影原点附近，即真实经纬度 (0,0) 几内亚湾。
        try {
            XposedHelpers.hookAllMethods(baiduClazz, "getCoorType") { chain, method ->
                var result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val currentCoor = result as? String
                    if (currentCoor.isNullOrEmpty() || currentCoor == "null") {
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

                val (defaultSys, isMercator) = resolveBaiduCoordSystem(coorType)
                val motion = getCurrentSpoofedMotion(defaultSys) ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                val (injLat, injLng) = if (isMercator) bd09llToBd09mc(motion.lat, motion.lng) else motion.lat to motion.lng
                val injCoorType = if (isMercator) "bd09mc" else "bd09ll"
                XposedBridge.logOpenCellIdEvery(
                    "baidu_onReceiveLocation",
                    "onReceiveLocation coorType=$coorType isMercator=$isMercator inject=($injLat,$injLng,$injCoorType)"
                )

                // 1. 经纬度
                try { XposedHelpers.callMethod(bdLoc, "setLatitude", injLat) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setLongitude", injLng) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", injLat) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", injLng) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "latitude", injLat) } catch (_: Throwable) {}
                try { XposedHelpers.setDoubleField(bdLoc, "longitude", injLng) } catch (_: Throwable) {}

                // 2. 状态码与坐标系
                try { XposedHelpers.callMethod(bdLoc, "setLocType", 61) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(bdLoc, "mLocType", 61) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(bdLoc, "locType", 61) } catch (_: Throwable) {}
                try { XposedHelpers.callMethod(bdLoc, "setCoorType", injCoorType) } catch (_: Throwable) {}
                try { XposedHelpers.setObjectField(bdLoc, "mCoorType", injCoorType) } catch (_: Throwable) {}

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
                                    val (defaultSys, isMercator) = resolveBaiduCoordSystem(coorType)
                                    val motion = getCurrentSpoofedMotion(defaultSys) ?: return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                                    val (injLat, injLng) = if (isMercator) bd09llToBd09mc(motion.lat, motion.lng) else motion.lat to motion.lng
                                    val injCoorType = if (isMercator) "bd09mc" else "bd09ll"
                                    XposedBridge.logOpenCellIdEvery(
                                        "baidu_registerListener_onReceiveLocation",
                                        "registerLocationListener onReceiveLocation coorType=$coorType isMercator=$isMercator inject=($injLat,$injLng,$injCoorType)"
                                    )
                                    try { XposedHelpers.callMethod(bdLoc, "setLatitude", injLat) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setLongitude", injLng) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", injLat) } catch (_: Throwable) {}
                                    try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", injLng) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setLocType", 61) } catch (_: Throwable) {}
                                    try { XposedHelpers.setIntField(bdLoc, "mLocType", 61) } catch (_: Throwable) {}
                                    try { XposedHelpers.callMethod(bdLoc, "setCoorType", injCoorType) } catch (_: Throwable) {}
                                    try { XposedHelpers.setObjectField(bdLoc, "mCoorType", injCoorType) } catch (_: Throwable) {}
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
                    // result 非空时是已存在的真实对象，先读它原本的 coorType 而不是无脑假设 bd09ll
                    val existingCoorType = if (result != null) {
                        try { XposedHelpers.callMethod(result, "getCoorType") as? String } catch (_: Throwable) { null }
                    } else null
                    val (defaultSys, isMercator) = resolveBaiduCoordSystem(existingCoorType)
                    val motion = getCurrentSpoofedMotion(defaultSys)
                    if (motion != null) {
                        val (injLat, injLng) = if (isMercator) bd09llToBd09mc(motion.lat, motion.lng) else motion.lat to motion.lng
                        val injCoorType = if (isMercator) "bd09mc" else "bd09ll"
                        val bdLoc = result ?: try {
                            val bdLocClass = XposedHelpers.findClass("com.baidu.location.BDLocation", classLoader)
                            bdLocClass.getConstructor().newInstance()
                        } catch (_: Throwable) { null }
                        if (bdLoc != null) {
                            try { XposedHelpers.callMethod(bdLoc, "setLatitude", injLat) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setLongitude", injLng) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "mLatitude", injLat) } catch (_: Throwable) {}
                            try { XposedHelpers.setDoubleField(bdLoc, "mLongitude", injLng) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setLocType", 61) } catch (_: Throwable) {}
                            try { XposedHelpers.setIntField(bdLoc, "mLocType", 61) } catch (_: Throwable) {}
                            try { XposedHelpers.callMethod(bdLoc, "setCoorType", injCoorType) } catch (_: Throwable) {}
                            try { XposedHelpers.setObjectField(bdLoc, "mCoorType", injCoorType) } catch (_: Throwable) {}
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
    // hookBaiduSDK 每个进程内可能被调用多次(handleLoadPackage 一次 + Application.attachBaseContext/onCreate
    // 各再触发一次去拿更完整的动态 classLoader，见 LocationHooker.kt 里的注释)。这里故意不限流，
    // 带上 classLoader 的身份，方便区分是哪一次调用找到/没找到，而不是被限流吞掉后续调用的日志。
    val myLocationDataClass = XposedHelpers.findClassIfExists("com.baidu.mapapi.map.MyLocationData", classLoader)
    XposedBridge.log(
        "[LocationSpoofer] MyLocationData class found=${myLocationDataClass != null} classLoader=$classLoader"
    )
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
                            XposedBridge.logOpenCellIdEvery(
                                "baidu_myLocationData_ctor",
                                "MyLocationData constructor inject lat=${motion.lat} lng=${motion.lng}"
                            )
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
    XposedBridge.log(
        "[LocationSpoofer] BaiduMap class found=${baiduMapClass != null} classLoader=$classLoader"
    )
    if (baiduMapClass != null && hookedCallbackClasses.putIfAbsent(baiduMapClass, true) == null) {
        try {
            XposedHelpers.hookAllMethods(baiduMapClass, "setMyLocationData") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && chain.args.isNotEmpty()) {
                    val data = chain.args[0]
                    if (data != null) {
                        val motion = getCurrentSpoofedMotion("BD-09")
                        if (motion != null) {
                            val beforeLat = try { XposedHelpers.getObjectField(data, "latitude") } catch (_: Throwable) { null }
                            val beforeLng = try { XposedHelpers.getObjectField(data, "longitude") } catch (_: Throwable) { null }
                            XposedBridge.logOpenCellIdEvery(
                                "baidu_setMyLocationData",
                                "setMyLocationData before=($beforeLat,$beforeLng) inject=(${motion.lat},${motion.lng})"
                            )
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
