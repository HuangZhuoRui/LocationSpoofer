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

package com.suseoaa.locationspoofer.xposed

import com.suseoaa.locationspoofer.xposed.utils.*
import com.suseoaa.locationspoofer.xposed.hooks.*
import com.suseoaa.locationspoofer.xposed.hooks.network.*

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.lang.reflect.*
import java.util.concurrent.CopyOnWriteArrayList

class LocationHooker : XposedModule() {
    init {
        XposedHelpers.module = this
    }

    internal val nmeaTimers = ConcurrentHashMap<Any, java.util.Timer>()
    internal val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()

    // 用于跟踪活动的 Android LocationListener，实现动态主动欺骗
    // 使用 CopyOnWriteArrayList 和强引用，防止 GC 移除监听器
    internal val capturedLocationListeners = CopyOnWriteArrayList<Any>()
    internal val capturedAMapListeners = CopyOnWriteArrayList<Any>()
    internal val capturedBaiduListeners = CopyOnWriteArrayList<Any>()
    internal val capturedTencentListeners = CopyOnWriteArrayList<Any>()

    @Volatile
    internal var currentPackageName: String = ""

    @Volatile
    internal var currentClassLoader: ClassLoader? = null

    @Volatile
    internal var lastSpoofedLat = 0.0

    @Volatile
    internal var lastSpoofedLng = 0.0

    @Volatile
    internal var cachedProvince = ""

    @Volatile
    internal var cachedCity = ""

    @Volatile
    internal var cachedDistrict = ""

    @Volatile
    internal var cachedStreet = ""

    @Volatile
    internal var cachedStreetNum = ""

    @Volatile
    internal var cachedAddress = ""

    @Volatile
    internal var cachedCountry = ""

    @Volatile
    internal var cachedPoiName = ""

    @Volatile
    internal var lastGeocodedLat = -999.0

    @Volatile
    internal var lastGeocodedLng = -999.0

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 目前这里没有内容
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        val classLoader = param.classLoader
        handleLoadPackage(pkg, classLoader)
    }

    // LibXposed API 101/102: 系统服务专属入口
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        handleLoadPackage("android", param.classLoader)
    }

    // LibXposed API 102: 热重载请求前置确认与资源清理
    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        nmeaTimers.values.forEach { it.cancel() }
        nmeaTimers.clear()
        hookedCallbackClasses.clear()
        capturedLocationListeners.clear()
        capturedAMapListeners.clear()
        capturedBaiduListeners.clear()
        capturedTencentListeners.clear()
        return true
    }

    // LibXposed API 102: 热重载完成后重新部署 Hook
    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        super.onHotReloaded(param)
        val pkg = currentPackageName
        val classLoader = currentClassLoader
        if (pkg.isNotEmpty() && classLoader != null) {
            handleLoadPackage(pkg, classLoader)
        }
    }


    companion object {

        // 系统进程同样需要覆盖（android进程持有LocationManagerService）
        val SYSTEM_PACKAGES = setOf("android", "system", "com.android.phone")
        internal const val VERBOSE_CELL_BUILD_LOGS = false

        fun hasTypeByName(clazz: Class<*>?, typeName: String): Boolean {
            if (clazz == null) return false
            if (clazz.name == typeName) return true
            for (iface in clazz.interfaces) {
                if (hasTypeByName(iface, typeName)) return true
            }
            return hasTypeByName(clazz.superclass, typeName)
        }
    }

    fun handleLoadPackage(pkg: String, classLoader: ClassLoader) {
        val processName = try {
            File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
        } catch (e: Exception) {
            pkg
        }
        val actualHostPkg = processName.substringBefore(":")
        if (actualHostPkg.isNotBlank() && !actualHostPkg.startsWith("android") && actualHostPkg != "system_server" && actualHostPkg != "system") {
            currentPackageName = actualHostPkg
        } else if (currentPackageName.isEmpty()) {
            currentPackageName = pkg
        }
        if (currentClassLoader == null || (pkg == currentPackageName)) {
            currentClassLoader = classLoader
        }

        // 防止注入到 SystemUI 导致崩溃
        if (pkg == "com.android.systemui") {
            return
        }

        val isSystemServer =
            (pkg == "android") || (processName == "android") || (processName == "system_server")

        // 可选：过滤掉容易引发安全模式的几个核心进程，但不影响设置或网络定位进程
        val isCoreSystemProcess = isSystemServer ||
                processName == "com.android.phone" ||
                processName == "com.android.systemui"

        // 系统进程：允许执行所有的环境数据Hook，实现系统原生界面的完美覆盖
        // if (SYSTEM_PACKAGES.contains(pkg)) {
        //     hookLocationAPIs(classLoader, pkg)
        //     return
        // }


        XposedBridge.log("[LocationSpoofer] Hooking package: $pkg")
        android.util.Log.e(
            "LocationSpoofer",
            "[INJECTED] Hooking package: $pkg process=$processName"
        )
        XposedBridge.logOpenCellId("handleLoadPackage pkg=$pkg classLoader=$classLoader")

        // 反检测: 必须在其他Hook之前安装,隐藏Xposed环境
        hookAntiDetection(classLoader)

        hookLocationAPIs(classLoader, pkg)
        hookGnssStatus(classLoader)

        // 兼容 MultiDex 与二次动态 DexClassLoader (例如百度地图 classes16.dex)
        try {
            XposedHelpers.hookMethod(
                "android.app.Application",
                classLoader,
                "attachBaseContext",
                android.content.Context::class.java
            ) { chain, method ->
                val result = chain.proceed(chain.args.toTypedArray())
                val app = chain.thisObject as? android.app.Application
                val appCl = app?.classLoader
                if (appCl != null) {
                    hookAllMapSdks(appCl)
                }
                return@hookMethod result
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookMethod(
                "android.app.Application",
                classLoader,
                "onCreate"
            ) { chain, method ->
                val result = chain.proceed(chain.args.toTypedArray())
                val app = chain.thisObject as? android.app.Application
                val appCl = app?.classLoader
                if (appCl != null) {
                    hookAllMapSdks(appCl)
                }
                return@hookMethod result
            }
        } catch (_: Throwable) {}

        hookWifiEnvironment(classLoader, isCoreSystemProcess)
        hookCellEnvironment(classLoader, isCoreSystemProcess)
        hookConnectivityLayer(classLoader, isCoreSystemProcess)
        hookBluetoothLE(classLoader, isCoreSystemProcess)
        if (!isCoreSystemProcess) {
            SensorStepHooker.hookSensorStepSimulation(classLoader)
        }

        // ★ 关键: 在注入完成后立即预启动 ConfigPoller 守护线程
        // 之前只在 readConfig() 被 hook 调用时才启动，但如果目标 App 从不调用被 hook 的方法
        // (如高德地图自身不调用 AMapLocationClient.getLatitude())，ConfigPoller 就永远不会启动
        // 现在改为在注入时就立即启动，确保每个被注入的进程都有 ConfigPoller 在运行
        readConfig()
    }

    internal fun hookAllMapSdks(cl: ClassLoader) {
        try { hookAMapSDK(cl) } catch (_: Throwable) {}
        try { hookTencentSDK(cl) } catch (_: Throwable) {}
        try { hookBaiduSDK(cl) } catch (_: Throwable) {}
    }

    /**
     * ★ 反检测: 隐藏Xposed环境,防止反作弊SDK检测到Hook
     *
     * 设计原则:
     * 1. 只使用精确匹配,绝不使用宽泛的contains/startsWith,避免误杀正常类
     * 2. 不Hook ClassLoader.loadClass的宽泛模式(会导致App卡死)
     * 3. 不Hook BufferedReader.readLine(开销巨大)
     * 4. 不Hook File.exists/Runtime.exec(干扰正常功能)
     */
    internal var startTimestamp = System.currentTimeMillis()

    // GCJ-02 转 WGS-84 转换常量（Xposed 模块运行在目标 App 进程中）
    internal val GCJ_A = 6378245.0
    internal val GCJ_EE = 0.00669342162296594

    internal val BD_PI = Math.PI * 3000.0 / 180.0

    /**
     * GCJ-02坐标转BD-09坐标
     *
     * @param gcjLat GCJ-02纬度(高德/腾讯坐标系)
     * @param gcjLng GCJ-02经度
     * @return Pair(BD-09纬度, BD-09经度)
     */
    internal val rng = Random()
    internal var hookDriftLat = 0.0
    internal var hookDriftLng = 0.0
    internal var hookAccuracyDrift = 0.0
    internal var hookLastCallTime = 0L

    /**
     * 拦截GnssStatus回调,注入伪造的卫星星座数据
     *
     * 反作弊SDK通过registerGnssStatusCallback获取卫星可见数和信噪比(C/N0),
     * 若Location坐标正常但卫星数为0或信噪比全为0,则判定为模拟位置。
     *
     * 伪造策略:
     * - 可见卫星数: 12-18颗(真实室外环境的典型值)
     * - 信噪比(C/N0): 15-40 dB-Hz(真实GPS信号的典型范围)
     * - 卫星类型: GPS(1) + GLONASS(3) + BDS(5)混合星座
     */
    internal var lastConfig: JSONObject? = null

    @Volatile
    internal var lastOpenCellConfigLogKey: String? = null

    @Volatile
    internal var lastOpenCellConfigReadFailureLogTime = 0L

    @Volatile
    internal var isConfigPollingStarted = false

    @Volatile
    internal var configPollIntervalMs = 1_000L
    internal val pollingLock = Any()
    internal val localConfigPath = "/data/local/tmp/locationspoofer_config.json"
    internal val systemConfigPath = "/data/system/locationspoofer_config.json"

    internal fun logOpenCellConfigLoaded(source: String, config: JSONObject) {
        val cellArray = config.optJSONArray("cell_json")
        val cellCount = cellArray?.length() ?: 0
        val firstCell =
            if (cellArray != null && cellArray.length() > 0) cellArray.optJSONObject(0) else null
        val firstSummary = if (firstCell != null) {
            val type =
                normalizeCellType(firstCell.optString("type", firstCell.optString("radio", "LTE")))
            val mcc = positiveJsonInt(firstCell, "mcc", default = 460)
            val mnc = positiveJsonInt(firstCell, "mnc", "net", default = 0)
            val area = cellAreaCode(firstCell, 0)
            val identity = cellIdentityCode(firstCell, 0)
            "$type/$mcc-$mnc area=$area identity=$identity"
        } else {
            "none"
        }
        val logKey = "${config.optBoolean("active", false)}|${
            config.optBoolean(
                "mock_cell",
                true
            )
        }|${config.optDouble("lat", 0.0)}|${config.optDouble("lng", 0.0)}|$cellCount|$firstSummary"
        if (logKey != lastOpenCellConfigLogKey) {
            lastOpenCellConfigLogKey = logKey
            XposedBridge.logOpenCellId(
                "readConfig[$source] active=${
                    config.optBoolean(
                        "active",
                        false
                    )
                } mockCell=${config.optBoolean("mock_cell", true)} lat=${
                    config.optDouble(
                        "lat",
                        0.0
                    )
                } lng=${
                    config.optDouble(
                        "lng",
                        0.0
                    )
                } cellJsonCount=$cellCount firstCell=$firstSummary"
            )
        }
    }

    internal fun configReadPaths(): Array<String> {
        return if (android.os.Process.myUid() == 1000) {
            arrayOf(systemConfigPath, localConfigPath)
        } else {
            arrayOf(localConfigPath, systemConfigPath)
        }
    }

    internal fun normalizeConfig(config: JSONObject): JSONObject {
        if (!config.has("wifi_json")) config.put("wifi_json", org.json.JSONArray())
        // UI 使用高德地图(AMapSDK)，cameraPosition.target 返回的是 GCJ-02 坐标系。
        // 因此 config["lat"/"lng"] 已经是 GCJ-02，不需要再做 BD-09 → GCJ-02 转换。
        val gcj02Lat = config.optDouble("lat", 0.0)
        val gcj02Lng = config.optDouble("lng", 0.0)

        // 派生出 BD-09（百度定位 SDK 需要）
        val bd09 = gcj02ToBd09(gcj02Lat, gcj02Lng)
        config.put("bd09_lat", bd09.first)
        config.put("bd09_lng", bd09.second)

        // 派生出 WGS-84（标准 Android Location 对象的理论坐标系）
        val wgs84 = gcj02ToWgs84(gcj02Lat, gcj02Lng)
        config.put("wgs84_lat", wgs84.first)
        config.put("wgs84_lng", wgs84.second)

        // lat/lng 保持 GCJ-02 不变，作为默认坐标（高德、腾讯等直接使用）
        return config
    }

    private val lastConfigModifiedMap = ConcurrentHashMap<String, Long>()

    internal fun loadConfigFromDisk(source: String): JSONObject? {
        val errors = ArrayList<String>()
        for (path in configReadPaths()) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    errors.add("$path missing")
                    continue
                }
                val lastModified = file.lastModified()
                val cachedMod = lastConfigModifiedMap[path]
                if (lastConfig != null && cachedMod != null && cachedMod == lastModified) {
                    return lastConfig
                }
                val text = file.readText()
                val config = normalizeConfig(JSONObject(text))
                lastConfig = config
                lastConfigModifiedMap[path] = lastModified
                configPollIntervalMs = 1_000L
                logOpenCellConfigLoaded("$source:$path", config)
                return config
            } catch (e: Throwable) {
                errors.add("$path ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        val now = System.currentTimeMillis()
        val isPermissionDenied =
            errors.any { it.contains("EACCES") || it.contains("Permission denied") }
        val shouldBackoff = currentPackageName == "com.android.phone" && isPermissionDenied
        val logIntervalMs = if (isPermissionDenied) 60_000L else 10_000L
        if (shouldBackoff) {
            configPollIntervalMs = 60_000L
        }
        if (now - lastOpenCellConfigReadFailureLogTime > logIntervalMs) {
            lastOpenCellConfigReadFailureLogTime = now
            XposedBridge.logOpenCellId(
                "readConfig[$source] no readable config (${
                    errors.joinToString(
                        " | "
                    )
                })"
            )
        }
        return null
    }

    /**
     * 从本地文件读取模拟配置(纯文件方案,无ContentProvider跨进程调用)
     *
     * 架构优化:
     *    由于此方法会被各种 Hook 在主线程极其高频地调用（例如每秒数百次），
     *    任何在主线程进行的文件 IO（哪怕是偶尔一次）都会导致严重的丢帧卡顿（Stutter）。
     *    因此重构为：在首次调用时启动一个后台守护线程（Daemon Thread），
     *    每隔 1000ms 在后台异步读取文件并更新 Volatile 的 lastConfig。
     *    主线程的 readConfig() 永远只返回内存中的 lastConfig，实现真正的 0 IO 延迟。
     */
    internal fun readConfig(): JSONObject? {
        if (!isConfigPollingStarted) {
            synchronized(pollingLock) {
                if (!isConfigPollingStarted) {
                    isConfigPollingStarted = true

                    // 首次调用时同步读取一次，确保立即有数据可用
                    loadConfigFromDisk("initial")

                    // 启动后台轮询守护线程
                    Thread {
                        while (true) {
                            Thread.sleep(configPollIntervalMs)
                            val newConfig = loadConfigFromDisk("poll")

                            if (newConfig != null && newConfig.optBoolean("active", false)) {
                                val currentLat = newConfig.optDouble("lat", 0.0)
                                val currentLng = newConfig.optDouble("lng", 0.0)
                                lastSpoofedLat = currentLat
                                lastSpoofedLng = currentLng

                                // 直接从前端控制台下发的 JSON 配置中读取逆地理编码信息，0网络延迟，0硬编码
                                cachedProvince = newConfig.optString("province", "")
                                cachedCity = newConfig.optString("city", "")
                                cachedDistrict = newConfig.optString("district", "")
                                cachedStreet = newConfig.optString("street", "")
                                cachedStreetNum = newConfig.optString("streetNum", "")
                                cachedAddress = newConfig.optString("address", "")
                                cachedCountry = newConfig.optString("country", "")
                                cachedPoiName = newConfig.optString("poiName", "")

                                val timeNow = System.currentTimeMillis()
                                val elapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                                val motion = RouteEngine.calculateCurrentPosition(newConfig, timeNow)
                                lastSpoofedLat = motion.lat
                                lastSpoofedLng = motion.lng

                                val basePkg = currentPackageName.substringBefore(":")
                                if (basePkg == "com.suseoaa.locationspoofer") {
                                    continue
                                }

                                val cl = currentClassLoader
                                val nCount = capturedLocationListeners.size
                                val aCount = capturedAMapListeners.size
                                val bCount = capturedBaiduListeners.size
                                val tCount = capturedTencentListeners.size

                                val (targetLat, targetLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "WGS-84")
                                val jittered = getJitteredLocation(targetLat, targetLng)
                                val pushLat = jittered.first
                                val pushLng = jittered.second
                                val pushSpeed = motion.speed
                                val pushBearing = motion.bearing
                                val pushAccuracy = getJitteredAccuracy()
                                val pushAltitude = newConfig.optDouble("altitude", 25.0)

                                val mainHandler = try {
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                } catch (_: Throwable) {
                                    null
                                }

                                val dispatchBlock = Runnable {
                                    // 1. Android Native LocationListener & Consumer
                                    if (nCount > 0 && cl != null) {
                                        val listenersToNotify = capturedLocationListeners.toList()
                                        for (listener in listenersToNotify) {
                                            try {
                                                val listenerCl = listener.javaClass.classLoader ?: cl
                                                val locationClass = Class.forName(
                                                    "android.location.Location",
                                                    false,
                                                    listenerCl
                                                )
                                                val mockLoc =
                                                    locationClass.getConstructor(String::class.java)
                                                        .newInstance(android.location.LocationManager.GPS_PROVIDER)
                                                XposedHelpers.callMethod(mockLoc, "setLatitude", pushLat)
                                                XposedHelpers.callMethod(mockLoc, "setLongitude", pushLng)
                                                XposedHelpers.callMethod(mockLoc, "setAccuracy", pushAccuracy)
                                                XposedHelpers.callMethod(mockLoc, "setSpeed", pushSpeed)
                                                XposedHelpers.callMethod(mockLoc, "setBearing", pushBearing)
                                                XposedHelpers.callMethod(mockLoc, "setAltitude", pushAltitude)
                                                XposedHelpers.callMethod(mockLoc, "setTime", timeNow)
                                                XposedHelpers.callMethod(
                                                    mockLoc,
                                                    "setElapsedRealtimeNanos",
                                                    elapsedNanos
                                                )
                                                try {
                                                    val extras = android.os.Bundle().apply {
                                                        val satCount = newConfig.optInt("satellite_count", 20)
                                                        putInt("satellites", satCount)
                                                        putInt("satellites_in_view", satCount)
                                                        putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                                                        putInt("satellites_visible", satCount)
                                                        putBoolean("mockLocation", false)
                                                    }
                                                    XposedHelpers.callMethod(mockLoc, "setExtras", extras)
                                                } catch (_: Throwable) {}
                                                try {
                                                    XposedHelpers.callMethod(
                                                        mockLoc,
                                                        "setIsFromMockProvider",
                                                        false
                                                    )
                                                } catch (_: Throwable) {
                                                }
                                                var called = false
                                                try {
                                                    XposedHelpers.callMethod(
                                                        listener,
                                                        "onLocationChanged",
                                                        mockLoc
                                                    )
                                                    called = true
                                                } catch (_: Throwable) {}
                                                if (!called) {
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            listener,
                                                            "accept",
                                                            mockLoc
                                                        )
                                                        called = true
                                                    } catch (_: Throwable) {}
                                                }
                                                if (!called) {
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            listener,
                                                            "onLocationChanged",
                                                            listOf(mockLoc)
                                                        )
                                                        called = true
                                                    } catch (_: Throwable) {}
                                                }
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    }

                                    // 2. AMapLocationListener
                                    if (aCount > 0 && cl != null) {
                                        val (aMapLat, aMapLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "GCJ-02")
                                        val aMapJittered = getJitteredLocation(aMapLat, aMapLng)
                                        val listenersToNotify = capturedAMapListeners.toList()
                                        for (listener in listenersToNotify) {
                                            try {
                                                val listenerCl = listener.javaClass.classLoader ?: cl
                                                val amapLocationClass = Class.forName(
                                                    "com.amap.api.location.AMapLocation",
                                                    false,
                                                    listenerCl
                                                )
                                                val mockAMapLoc =
                                                    amapLocationClass.getConstructor(String::class.java)
                                                        .newInstance("gps")
                                                XposedHelpers.callMethod(
                                                    mockAMapLoc,
                                                    "setLatitude",
                                                    aMapJittered.first
                                                )
                                                XposedHelpers.callMethod(
                                                    mockAMapLoc,
                                                    "setLongitude",
                                                    aMapJittered.second
                                                )
                                                XposedHelpers.callMethod(
                                                    mockAMapLoc,
                                                    "setAccuracy",
                                                    pushAccuracy
                                                )
                                                XposedHelpers.callMethod(
                                                    mockAMapLoc,
                                                    "setSpeed",
                                                    pushSpeed
                                                )
                                                XposedHelpers.callMethod(
                                                    mockAMapLoc,
                                                    "setBearing",
                                                    pushBearing
                                                )
                                                XposedHelpers.callMethod(
                                                    mockAMapLoc,
                                                    "setAltitude",
                                                    pushAltitude
                                                )
                                                XposedHelpers.callMethod(
                                                    mockAMapLoc,
                                                    "setTime",
                                                    timeNow
                                                )
                                                try {
                                                    XposedHelpers.callMethod(mockAMapLoc, "setSatellites", newConfig.optInt("satellite_count", 20))
                                                    XposedHelpers.callMethod(mockAMapLoc, "setGpsAccuracyStatus", 1)
                                                    XposedHelpers.callMethod(mockAMapLoc, "setLocationType", 1)
                                                } catch (_: Throwable) {}
                                                XposedHelpers.callMethod(
                                                    listener,
                                                    "onLocationChanged",
                                                    mockAMapLoc
                                                )
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    }

                                    // 3. BDLocationListener / BDAbstractLocationListener
                                    if (bCount > 0 && cl != null) {
                                        val (baiduLat, baiduLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "BD-09")
                                        val baiduJittered = getJitteredLocation(baiduLat, baiduLng)
                                        val listenersToNotify = capturedBaiduListeners.toList()
                                        for (listener in listenersToNotify) {
                                            try {
                                                val listenerCl = listener.javaClass.classLoader ?: cl
                                                val bdLocationClass = Class.forName(
                                                    "com.baidu.location.BDLocation",
                                                    false,
                                                    listenerCl
                                                )
                                                val mockBDLoc =
                                                    bdLocationClass.getConstructor().newInstance()
                                                XposedHelpers.callMethod(
                                                    mockBDLoc,
                                                    "setLatitude",
                                                    baiduJittered.first
                                                )
                                                XposedHelpers.callMethod(
                                                    mockBDLoc,
                                                    "setLongitude",
                                                    baiduJittered.second
                                                )
                                                try { XposedHelpers.setDoubleField(mockBDLoc, "mLatitude", baiduJittered.first) } catch (_: Throwable) {}
                                                try { XposedHelpers.setDoubleField(mockBDLoc, "mLongitude", baiduJittered.second) } catch (_: Throwable) {}
                                                try { XposedHelpers.callMethod(mockBDLoc, "setCoorType", "bd09ll") } catch (_: Throwable) {}
                                                try { XposedHelpers.setObjectField(mockBDLoc, "mCoorType", "bd09ll") } catch (_: Throwable) {}
                                                XposedHelpers.callMethod(mockBDLoc, "setRadius", pushAccuracy)
                                                XposedHelpers.callMethod(mockBDLoc, "setSpeed", pushSpeed * 3.6f)
                                                XposedHelpers.callMethod(mockBDLoc, "setDirection", pushBearing)
                                                XposedHelpers.callMethod(mockBDLoc, "setLocType", 61)
                                                XposedHelpers.callMethod(mockBDLoc, "setSatelliteNumber", 20)
                                                XposedHelpers.callMethod(mockBDLoc, "setGpsCheckStatus", 1)
                                                try { XposedHelpers.callMethod(mockBDLoc, "setMockGps", 0) } catch (_: Throwable) {}
                                                try { XposedHelpers.callMethod(mockBDLoc, "setTime", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())) } catch (_: Throwable) {}
                                                XposedHelpers.callMethod(
                                                    listener,
                                                    "onReceiveLocation",
                                                    mockBDLoc
                                                )
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    }

                                    // 4. TencentLocationListener
                                    if (tCount > 0 && cl != null) {
                                        try {
                                            val (tencentLat, tencentLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "GCJ-02")
                                            val tencentJittered = getJitteredLocation(tencentLat, tencentLng)
                                            val tencentLocInterface = Class.forName(
                                                "com.tencent.map.geolocation.TencentLocation",
                                                false,
                                                cl
                                            )
                                            val proxyLoc = Proxy.newProxyInstance(
                                                cl, arrayOf(tencentLocInterface)
                                            ) { _, method, _ ->
                                                when (method.name) {
                                                    "getLatitude" -> tencentJittered.first
                                                    "getLongitude" -> tencentJittered.second
                                                    "getProvider" -> "gps"
                                                    "getAccuracy" -> pushAccuracy
                                                    "getSpeed" -> pushSpeed
                                                    "getBearing" -> pushBearing
                                                    "getTime" -> timeNow
                                                    else -> null
                                                }
                                            }
                                            val listenersToNotify = capturedTencentListeners.toList()
                                            for (listener in listenersToNotify) {
                                                try {
                                                    XposedHelpers.callMethod(
                                                        listener,
                                                        "onLocationChanged",
                                                        proxyLoc,
                                                        0,
                                                        "ok"
                                                    )
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    }

                                    // 5. Sensor Step Simulation
                                    if (cl != null) {
                                        SensorStepHooker.dispatchStepEvents(newConfig, cl)
                                    }
                                }

                                if (mainHandler != null) {
                                    mainHandler.post(dispatchBlock)
                                } else {
                                    dispatchBlock.run()
                                }
                            }
                        }
                    }.apply {
                        isDaemon = true
                        name = "LocationSpoofer_ConfigPoller"
                        start()
                    }

                }
            }
        }
        return lastConfig
    }

    internal var cachedGpsSatellitesList: Iterable<Any>? = null
    internal var lastGpsSatellitesUpdate = 0L
}
