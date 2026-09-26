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

package com.vincenthzr.locationspoofer.xposed

import com.vincenthzr.locationspoofer.xposed.utils.*
import com.vincenthzr.locationspoofer.xposed.hooks.*
import com.vincenthzr.locationspoofer.xposed.hooks.network.*
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.VendorRegistry
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemProcess
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus

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
    internal val bleScanTimers = ConcurrentHashMap<Any, java.util.Timer>()
    internal val hookedCallbackClasses = ConcurrentHashMap<Class<*>, Boolean>()

    // 标记本进程内是否已经安装过一次环境级 Hook（WiFi/基站/连接层/蓝牙/位置/反检测等）。
    // 这些 Hook 针对的是 ConnectivityManager/TelephonyManager/WifiManager/Location 等系统共享类，
    // 同一个类在整个进程里只有一份，不需要也不能按 classloader 重复 Hook——
    // WebView/MultiDex 等场景会让 handleLoadPackage 在同一进程内被多次调用（不同的"包名"），
    // 如果重复安装，会在同一个方法上叠加两层拦截链，libxposed 在处理带基本类型参数的方法
    // (如 ConnectivityManager.getNetworkInfo(int)) 时，叠加后的拦截链会抛出
    // IllegalArgumentException: argument N has type int, got java.lang.Integer，导致目标 App 崩溃。
    @Volatile
    internal var environmentHooksInstalled = false

    @Volatile
    internal var systemHooksInstalled = false

    @Volatile
    internal var isSystemServerProcess = false

    @Volatile
    internal var isPhoneProcessInstance = false

    @Volatile
    internal var isBluetoothProcessInstance = false

    // 用于跟踪活动的 Android LocationListener，实现动态主动欺骗
    // 使用 CopyOnWriteArrayList 和强引用，防止 GC 移除监听器
    internal val capturedLocationListeners = CopyOnWriteArrayList<Any>()
    internal val capturedAMapListeners = CopyOnWriteArrayList<Any>()
    internal val capturedBaiduListeners = CopyOnWriteArrayList<Any>()
    internal val capturedTencentListeners = CopyOnWriteArrayList<Any>()
    internal val capturedFusedLocationCallbacks = CopyOnWriteArrayList<Any>()

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
        android.util.Log.e(
            "LocationSpoofer",
            "[SysHook] onSystemServerStarting invoked! system_server classLoader=${param.classLoader}"
        )
        handleLoadPackage("android", param.classLoader)
    }

    // LibXposed API 102: 热重载请求前置确认与资源清理
    internal val vendorExtraHooks = mutableListOf<AutoCloseable>()

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        vendorExtraHooks.asReversed().forEach { runCatching { it.close() } }
        vendorExtraHooks.clear()
        nmeaTimers.values.forEach { it.cancel() }
        nmeaTimers.clear()
        bleScanTimers.values.forEach { it.cancel() }
        bleScanTimers.clear()
        hookedCallbackClasses.clear()
        environmentHooksInstalled = false
        systemHooksInstalled = false
        capturedLocationListeners.clear()
        capturedAMapListeners.clear()
        capturedBaiduListeners.clear()
        capturedTencentListeners.clear()
        capturedFusedLocationCallbacks.clear()
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

        val handledAsSystemProcess = if (BuildConfig.GLOBAL_SCHEME) {
            handleSystemProcessGlobal(pkg, processName, classLoader)
        } else {
            handleSystemProcessScoped(pkg, processName, classLoader)
        }
        if (handledAsSystemProcess) return

        if (environmentHooksInstalled) {
            // 同一进程内 handleLoadPackage 被再次触发（例如宿主 App 内嵌的 WebView 会作为独立的
            // "包" 单独回调一次），但 ConnectivityManager/TelephonyManager/WifiManager/Location
            // 这些系统类在整个进程里只有一份，不需要重复 Hook，见上面 environmentHooksInstalled 的注释。
            readConfig()
            return
        }
        environmentHooksInstalled = true

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
                XposedBridge.log("[LocationSpoofer] attachBaseContext fired, appClassLoader=$appCl sameAsInitial=${appCl === classLoader}")
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
                XposedBridge.log("[LocationSpoofer] Application.onCreate fired, appClassLoader=$appCl sameAsInitial=${appCl === classLoader}")
                if (appCl != null) {
                    hookAllMapSdks(appCl)
                }
                return@hookMethod result
            }
        } catch (_: Throwable) {}

        // 能走到这里说明不是系统进程（系统进程已在上面的 handleSystemProcessXxx 中提前返回）
        hookWifiEnvironment(classLoader)
        hookCellEnvironment(classLoader)
        hookConnectivityLayer(classLoader)
        hookBluetoothLE(classLoader)
        SensorStepHooker.hookSensorStepSimulation(classLoader)

        readConfig()
    }

    internal fun hookAllMapSdks(cl: ClassLoader) {
        try { hookAMapSDK(cl) } catch (_: Throwable) {}
        try { hookTencentSDK(cl) } catch (_: Throwable) {}
        try { hookBaiduSDK(cl) } catch (_: Throwable) {}
        try { hookGoogleFusedLocation(cl) } catch (_: Throwable) {}
    }

    /**
     * 非全局（scoped）方案下对系统进程的处理：只装基础定位与 GNSS Hook，
     * 绝对不 Hook 系统的 Wi-Fi、基站、网络状态与蓝牙底层状态机。
     * @return true 表示该进程已处理完毕（或应当跳过），调用方不再继续安装 App 内 Hook。
     */
    private fun handleSystemProcessScoped(pkg: String, processName: String, classLoader: ClassLoader): Boolean {
        // 防止注入到 SystemUI 或 com.android.bluetooth 导致崩溃或 SELinux 违规
        if (pkg == "com.android.systemui" || pkg == "com.android.bluetooth" || processName.contains("com.android.bluetooth")) {
            return true
        }

        val isSystemServer =
            (processName == "system_server") || (processName == "system")

        // 核心系统进程：system_server, com.android.phone 等
        val isCoreSystemProcess = isSystemServer ||
                processName == "com.android.phone" ||
                processName == "com.android.systemui"

        if (isCoreSystemProcess) {
            // 避免破坏系统 internal 状态机引发 NullPointerException 导致 system_server 崩溃进入安全模式
            XposedBridge.log("[LocationSpoofer] Core system process ($pkg / $processName) detected, skipping environment hooks to prevent system crash.")
            hookLocationAPIs(classLoader, pkg)
            hookGnssStatus(classLoader)
            readConfig()
            return true
        }
        return false
    }

    /**
     * 全局（global）方案下对系统进程的处理：在 system_server / com.android.phone / com.android.bluetooth
     * 里安装系统服务级 Hook，一次性对全设备生效。
     * @return true 表示该进程已处理完毕（或应当跳过），调用方不再继续安装 App 内 Hook。
     */
    private fun handleSystemProcessGlobal(pkg: String, processName: String, classLoader: ClassLoader): Boolean {
        // 防止注入到 SystemUI 导致崩溃或无意义占用
        if (pkg == "com.android.systemui" || processName == "com.android.systemui") {
            return true
        }

        val isSystemServer =
            (processName == "system_server") || (processName == "system")
        if (!isSystemServer && (pkg == "android" || pkg == "system")) return true
        val isPhoneProcess =
            (pkg == "com.android.phone") || (processName == "com.android.phone")
        val isBluetoothProcess =
            (pkg == "com.android.bluetooth") || (processName.startsWith("com.android.bluetooth"))

        if (isSystemServer) isSystemServerProcess = true
        if (isPhoneProcess) isPhoneProcessInstance = true
        if (isBluetoothProcess) isBluetoothProcessInstance = true

        val isCoreSystemProcess = isSystemServer || isPhoneProcess || isBluetoothProcess
        if (!isCoreSystemProcess) return false

        android.util.Log.e(
            "LocationSpoofer",
            "[SysHook] Core system process detected: pkg=$pkg, process=$processName, myUid=${android.os.Process.myUid()}"
        )
        if (systemHooksInstalled) {
            readConfig()
            return true
        }
        systemHooksInstalled = true
        XposedBridge.log("[SysHook] Deploying system framework hooks in $processName...")

        // 厂商适配方案的手动覆盖（VendorRegistry.applyManualOverride）必须在下面任何一个
        // hookSystemXxxService 之前生效——它们会通过 SystemClassLocator 查询 VendorRegistry，从而触发
        // VendorRegistry.active 这个 by lazy 属性首次求值，晚了就再也覆盖不了。这里提前读一次
        // 配置：readConfig() 可重复调用，后续调用只返回内存里已缓存的 lastConfig，不会重复走磁盘 IO。
        val earlyCfg = readConfig()
        VendorRegistry.applyManualOverride(earlyCfg?.optString("vendor_override", "auto"))

        // Hook 运行状态报告（App 的"系统适配 → Hook 运行状态"页读取），每个系统进程各写一份
        HookStatus.begin(
            when {
                isSystemServer -> SystemProcess.SYSTEM_SERVER
                isPhoneProcess -> SystemProcess.PHONE
                else -> SystemProcess.BLUETOOTH
            }
        )
        fun deploy(name: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                XposedBridge.log("[SysHook] $name error: $t")
                HookStatus.error(name, t)
            }
        }

        if (isSystemServer) {
            // 只读侦察：把各组件候选类的方法 / 字段列表打进日志，适配新系统时才需要，由配置里的调试开关控制
            if (earlyCfg?.optBoolean("debug_dump_system_services", false) == true) {
                deploy("dumpSystemServiceInternals") { dumpSystemServiceInternals(classLoader) }
            }
            deploy("hookSystemLocationService") { hookSystemLocationService(classLoader) }
            deploy("hookSystemWifiService") { hookSystemWifiService(classLoader) }
            deploy("hookSystemConnectivityService") { hookSystemConnectivityService(classLoader) }
            deploy("hookSystemTelephonyRegistry") { hookSystemTelephonyRegistry(classLoader) }
            deploy("hookSystemAppOpsService") { hookSystemAppOpsService(classLoader) }
        }

        if (isPhoneProcess) {
            deploy("hookSystemTelephonyService") { hookSystemTelephonyService(classLoader) }
        }

        if (isBluetoothProcess) {
            deploy("hookSystemBluetoothService") { hookSystemBluetoothService(classLoader) }
        }

        // 机型/系统适配层：先打印一次命中的适配器画像，再执行该机型专属的额外 Hook（默认空实现，
        // 各厂商在 vendor/profiles/ 下按需覆写）。基线 Hook 已在上面装完，这里是纯追加，不影响任何机型。
        VendorRegistry.logSelectionOnce()
        deploy("installExtraHooks") { VendorRegistry.installExtraHooks(this, classLoader) }

        val cfg = readConfig()
        android.util.Log.e(
            "LocationSpoofer",
            "[SysHook] System framework hooks deployed in $processName. Config active=${cfg?.optBoolean("active")}, globalMode=${cfg?.optBoolean("system_hook_global_mode")}, targetPkgs=${cfg?.optJSONArray("system_hook_packages")?.length() ?: 0}"
        )
        return true
    }

    /**
     * 只读侦察，不装任何会改变行为的 Hook：探索"直接改 system_server 内部服务实现，
     * 一次性对全设备生效"这个方向时，第一步必须先摸清楚这台设备/这个安卓版本实际的
     * 内部类结构长什么样——LocationManagerService/WifiServiceImpl 这些类不是公开 API，
     * 字段和方法名在不同安卓版本、不同 OEM 定制 ROM 之间差异很大，且没有公开文档，
     * 瞎猜字段名直接上 Hook 是 system_server 崩溃、设备进重启循环最常见的原因。
     * 这里只用反射枚举候选类的方法名/字段名打进日志，不拦截、不修改任何返回值，
     * 单个类枚举失败也只是记一条日志，不会影响 system_server 本身的运行。
     */
    internal fun dumpSystemServiceInternals(classLoader: ClassLoader) {
        // 各组件的候选类名（含当前适配器给出的定制候选），再加上两个只用于排查的 GNSS 内部类
        val candidateClassNames = SystemComponent.entries
            .filter { it.process == SystemProcess.SYSTEM_SERVER }
            .flatMap { VendorRegistry.classCandidates(it) } + listOf(
            "com.android.server.location.gnss.GnssLocationProvider",
            "com.android.server.location.gnss.GnssManagerService",
        )
        for (className in candidateClassNames) {
            try {
                val clazz = classLoader.loadClass(className)
                val methodNames = clazz.declaredMethods.map { it.name }.distinct().sorted()
                val fieldInfo = clazz.declaredFields.map { "${it.type.simpleName} ${it.name}" }.sorted()
                XposedBridge.log(
                    "[LocationSpoofer][SysDump] FOUND $className (superclass=${clazz.superclass?.name})\n" +
                        "  methods(${methodNames.size})=${methodNames.joinToString(",")}\n" +
                        "  fields(${fieldInfo.size})=${fieldInfo.joinToString(",")}"
                )
            } catch (e: ClassNotFoundException) {
                XposedBridge.log("[LocationSpoofer][SysDump] not found: $className")
            } catch (e: Throwable) {
                XposedBridge.log("[LocationSpoofer][SysDump] error dumping $className: $e")
            }
        }
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
    internal val phoneConfigPath = "/data/user_de/0/com.android.phone/files/locationspoofer_config.json"
    internal val bluetoothConfigPath = "/data/user_de/0/com.android.bluetooth/files/locationspoofer_config.json"
    internal val appDataConfigPath = "/data/data/com.vincenthzr.locationspoofer/files/locationspoofer_config.json"
    internal val sdcardConfigPath = "/sdcard/Download/locationspoofer_config.json"

    internal fun logOpenCellConfigLoaded(source: String, config: JSONObject) {
        val cellArray = config.optJSONArray("cell_json")
        val cellCount = cellArray?.length() ?: 0
        val btArray = config.optJSONArray("bluetooth_json")
        val btCount = btArray?.length() ?: 0
        val active = config.optBoolean("active", false)
        val mockBt = config.optBoolean("mock_bluetooth", true)
        val lat = config.optDouble("lat", 0.0)
        val lng = config.optDouble("lng", 0.0)
        val isGlobal = config.optBoolean("system_hook_global_mode", false)
        val sysPkgs = config.optJSONArray("system_hook_packages")?.length() ?: 0
        val logKey = "$active|$mockBt|$lat|$lng|$cellCount|$btCount|$isGlobal|$sysPkgs"
        if (logKey != lastOpenCellConfigLogKey) {
            lastOpenCellConfigLogKey = logKey
            val msg = "[SysHook] 配置已加载[$source]: active=$active, globalMode=$isGlobal, 目标应用数=$sysPkgs, lat=$lat, lng=$lng, 蓝牙数=$btCount, 基站数=$cellCount"
            android.util.Log.i("LocationSpoofer", msg)
            XposedBridge.log(msg)
        }
    }

    internal fun configReadPaths(): Array<String> {
        if (!BuildConfig.GLOBAL_SCHEME) {
            // 非全局方案：配置文件统一打项目自定义 SELinux 标签（见 ConfigManager），按优先级依次尝试
            return if (android.os.Process.myUid() == 1000) {
                arrayOf(systemConfigPath, localConfigPath, appDataConfigPath, sdcardConfigPath)
            } else {
                arrayOf(localConfigPath, systemConfigPath, appDataConfigPath, sdcardConfigPath)
            }
        }
        val uid = android.os.Process.myUid()
        // 电话 / 蓝牙进程的专属副本由 App 用 root 写进它们自己的数据目录，但部分 root 方案的 su 域没有
        // radio_data_file / bluetooth_data_file 的写权限，副本会一直停在旧内容（HyperOS 4 + KernelSU 实测）。
        // 所以同时列出 RootManager 已授权这两个域读取的 /data/local/tmp 副本，由 loadConfigFromDisk 取最新的一份。
        if (isPhoneProcessInstance || uid == 1001) {
            return arrayOf(phoneConfigPath, localConfigPath)
        }
        if (isBluetoothProcessInstance || uid == 1002) {
            return arrayOf(bluetoothConfigPath, localConfigPath)
        }
        return if (uid == 1000) {
            arrayOf(systemConfigPath)
        } else {
            arrayOf(localConfigPath, systemConfigPath, appDataConfigPath)
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

    /**
     * 电话 / 蓝牙进程读到比自己专属副本更新的配置时，由进程自己把它写回专属副本（自己的数据目录可写）。
     * 重启后 App 重新下发 SELinux 规则之前，这两个进程只能读到专属副本，这样至少是最近一次的配置而不是很久以前的。
     */
    private fun refreshOwnConfigCopy(readPath: String, text: String) {
        if (!BuildConfig.GLOBAL_SCHEME) return
        val own = when {
            isPhoneProcessInstance -> phoneConfigPath
            isBluetoothProcessInstance -> bluetoothConfigPath
            else -> return
        }
        if (readPath == own) return
        try {
            val target = File(own)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(text)
            tmp.setReadable(true, false)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true) // 644：只有本进程可写
            if (tmp.renameTo(target)) lastConfigModifiedMap[own] = target.lastModified()
        } catch (t: Throwable) {
            XposedBridge.log("[SysHook] refresh own config copy failed: $t")
        }
    }

    internal fun loadConfigFromDisk(source: String): JSONObject? {
        val errors = ArrayList<String>()
        // 全局方案的多份副本内容相同、只是写入是否成功不同：按修改时间从新到旧尝试，避免读到某份写入失败而停留在旧内容的副本
        // （无权访问的文件 lastModified 为 0，自然排在最后）
        val paths = configReadPaths().let { candidates ->
            if (BuildConfig.GLOBAL_SCHEME) candidates.sortedByDescending { File(it).lastModified() } else candidates.toList()
        }
        for (path in paths) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    errors.add("$path missing")
                    continue
                }
                val lastModified = file.lastModified()
                val cachedMod = lastConfigModifiedMap[path]
                if (lastConfig != null && cachedMod != null && cachedMod == lastModified) {
                    HookStatus.configLoaded(path, lastModified)
                    return lastConfig
                }
                val text = file.readText()
                val config = normalizeConfig(JSONObject(text))
                lastConfig = config
                lastConfigModifiedMap[path] = lastModified
                HookStatus.configLoaded(path, lastModified)
                refreshOwnConfigCopy(path, text)
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
            val msg = "[SysHook] readConfig[$source] no readable config (${
                errors.joinToString(
                    " | "
                )
            })"
            android.util.Log.e("LocationSpoofer", msg)
            XposedBridge.logOpenCellId(msg)
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
                            try {
                                Thread.sleep(configPollIntervalMs)
                                val newConfig = loadConfigFromDisk("poll")

                                // 若系统核心服务在初次加载时尚未初始化完成，在后台轮询线程中重试挂载，直至成功
                                // isXxxProcess 标记只在全局方案的系统进程分支里被置位，非全局方案下这三个条件恒为 false
                                if (isSystemServerProcess && !isWifiServiceHooked && currentClassLoader != null) {
                                    try { hookSystemWifiService(currentClassLoader!!) } catch (t: Throwable) { XposedBridge.log("[SysWifi] Poller hookSystemWifiService error: $t") }
                                }
                                if (isPhoneProcessInstance && !isTelephonyServiceHooked && currentClassLoader != null) {
                                    try { hookSystemTelephonyService(currentClassLoader!!) } catch (t: Throwable) { XposedBridge.log("[SysCell] Poller hookSystemTelephonyService error: $t") }
                                }
                                if (isBluetoothProcessInstance && !isBluetoothServiceHooked && currentClassLoader != null) {
                                    try { hookSystemBluetoothService(currentClassLoader!!) } catch (t: Throwable) { XposedBridge.log("[SysBle] Poller hookSystemBluetoothService error: $t") }
                                }

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
                                    if (basePkg == "com.vincenthzr.locationspoofer") {
                                        continue
                                    }

                                    val cl = currentClassLoader
                                    val nCount = capturedLocationListeners.size
                                    val aCount = capturedAMapListeners.size
                                    val bCount = capturedBaiduListeners.size
                                    val tCount = capturedTencentListeners.size
                                    val fCount = capturedFusedLocationCallbacks.size

                                    val isStationary = motion.speed <= 0.05
                                    // 标准 android.location.Location 按规范是 WGS-84，高德等 App 会自行转 GCJ-02；
                                    // 这里若直接给 GCJ-02 会被二次加密、偏移数百米（issue #62）
                                    val (targetLat, targetLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "WGS-84")
                                    val pushLat = if (isStationary) targetLat else getJitteredLocation(targetLat, targetLng).first
                                    val pushLng = if (isStationary) targetLng else getJitteredLocation(targetLat, targetLng).second
                                    val pushSpeed = motion.speed
                                    val pushBearing = motion.bearing
                                    val pushAccuracy = if (isStationary) 2.5f else getJitteredAccuracy()
                                    val pushAltitude = RouteEngine.realisticAltitude(newConfig)

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

                                        // 1.5 Google FusedLocationProviderClient 的 LocationCallback
                                        if (fCount > 0 && cl != null) {
                                            val callbacksToNotify = capturedFusedLocationCallbacks.toList()
                                            for (callback in callbacksToNotify) {
                                                try {
                                                    val callbackCl = callback.javaClass.classLoader ?: cl
                                                    val locationClass = Class.forName(
                                                        "android.location.Location",
                                                        false,
                                                        callbackCl
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
                                                        XposedHelpers.callMethod(
                                                            mockLoc,
                                                            "setIsFromMockProvider",
                                                            false
                                                        )
                                                    } catch (_: Throwable) {}

                                                    val locationResultClass = Class.forName(
                                                        "com.google.android.gms.location.LocationResult",
                                                        false,
                                                        callbackCl
                                                    )
                                                    val locationsList = java.util.Collections.singletonList(mockLoc)
                                                    val result = XposedHelpers.callStaticMethod(
                                                        locationResultClass,
                                                        "create",
                                                        locationsList
                                                    )
                                                    XposedHelpers.callMethod(callback, "onLocationResult", result)
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }

                                        // 2. AMapLocationListener
                                        if (aCount > 0 && cl != null) {
                                            val (aMapLat, aMapLng) = getAppTargetCoordinate(motion.lat, motion.lng, newConfig, "GCJ-02")
                                            val aMapPushLat = if (isStationary) aMapLat else getJitteredLocation(aMapLat, aMapLng).first
                                            val aMapPushLng = if (isStationary) aMapLng else getJitteredLocation(aMapLat, aMapLng).second
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
                                                        aMapPushLat
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockAMapLoc,
                                                        "setLongitude",
                                                        aMapPushLng
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
                                                    try {
                                                        val extras = android.os.Bundle().apply {
                                                            val satCount = newConfig.optInt("satellite_count", 20)
                                                            putInt("satellites", satCount)
                                                            putInt("satellites_in_view", satCount)
                                                            putInt("satellites_used_in_fix", satCount.coerceAtLeast(12))
                                                            putInt("satellites_visible", satCount)
                                                            putBoolean("mockLocation", false)
                                                        }
                                                        XposedHelpers.callMethod(mockAMapLoc, "setExtras", extras)
                                                    } catch (_: Throwable) {}
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            mockAMapLoc,
                                                            "setElapsedRealtimeNanos",
                                                            elapsedNanos
                                                        )
                                                    } catch (_: Throwable) {
                                                    }
                                                    try {
                                                        XposedHelpers.callMethod(
                                                            mockAMapLoc,
                                                            "setIsFromMockProvider",
                                                            false
                                                        )
                                                    } catch (_: Throwable) {
                                                    }
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
                                            val baiduPushLat = if (isStationary) baiduLat else getJitteredLocation(baiduLat, baiduLng).first
                                            val baiduPushLng = if (isStationary) baiduLng else getJitteredLocation(baiduLat, baiduLng).second
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
                                                        baiduPushLat
                                                    )
                                                    XposedHelpers.callMethod(
                                                        mockBDLoc,
                                                        "setLongitude",
                                                        baiduPushLng
                                                    )
                                                    try { XposedHelpers.setDoubleField(mockBDLoc, "mLatitude", baiduPushLat) } catch (_: Throwable) {}
                                                    try { XposedHelpers.setDoubleField(mockBDLoc, "mLongitude", baiduPushLng) } catch (_: Throwable) {}
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
                                                val tencentPushLat = if (isStationary) tencentLat else getJitteredLocation(tencentLat, tencentLng).first
                                                val tencentPushLng = if (isStationary) tencentLng else getJitteredLocation(tencentLat, tencentLng).second
                                                val tencentLocInterface = Class.forName(
                                                    "com.tencent.map.geolocation.TencentLocation",
                                                    false,
                                                    cl
                                                )
                                                val proxyLoc = Proxy.newProxyInstance(
                                                    cl, arrayOf(tencentLocInterface)
                                                ) { _, method, _ ->
                                                    when (method.name) {
                                                        "getLatitude" -> tencentPushLat
                                                        "getLongitude" -> tencentPushLng
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
                            } catch (t: Throwable) {
                                XposedBridge.log("ConfigPoller error: " + t.javaClass.simpleName + ": " + t.message)
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
