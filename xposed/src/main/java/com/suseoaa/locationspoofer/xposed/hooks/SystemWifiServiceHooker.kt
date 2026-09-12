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

import android.util.Log
import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.XposedBridge
import com.suseoaa.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.util.Random

/**
 * system_server 级 Wi-Fi 服务（WifiServiceImpl）拦截与伪造模块
 *
 * 核心原理：
 * 全设备所有应用调用 WifiManager.getScanResults() 与 WifiManager.getConnectionInfo()
 * 都会通过 IWifiManager 远程 IPC 调用至 system_server 中的 WifiServiceImpl。
 *
 * 在 Android 12+ / HyperOS 中，WifiServiceImpl 被移动至 /apex/com.android.wifi/javalib/service-wifi.jar。
 * 本模块通过 ServiceManager.getService("wifi")、addService 拦截以及 APEX 动态类加载等多重机制
 * 确保稳定捕获 WifiServiceImpl 并完成挂载。
 */

@Volatile
internal var isWifiServiceHooked = false

private fun logWifi(msg: String) {
    XposedBridge.log(msg)
}

private fun findWifiServiceClass(classLoader: ClassLoader): Class<*>? {
    // 1. 尝试直接从当前 classLoader 加载 (Android 11 及部分 ROM)
    XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", classLoader)?.let {
        logWifi("[SysWifi] Found WifiServiceImpl from default classLoader")
        return it
    }
    XposedHelpers.findClassIfExists("com.android.server.WifiService", classLoader)?.let {
        logWifi("[SysWifi] Found WifiService from default classLoader")
        return it
    }

    // 2. 扫描 system_server 中所有活跃线程的 contextClassLoader (如 WifiHandlerThread, WifiScanningService)
    try {
        val threads = Thread.getAllStackTraces().keys
        for (t in threads) {
            val name = t.name
            if (name.contains("Wifi", ignoreCase = true) || name.contains("Scan", ignoreCase = true) || name.contains("wlan", ignoreCase = true)) {
                val cl = t.contextClassLoader ?: continue
                XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", cl)?.let {
                    logWifi("[SysWifi] Found WifiServiceImpl from thread: $name")
                    return it
                }
            }
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] Scan threads error: $t")
    }

    // 3. 扫描 LocalServices.sLocalServiceObjects
    try {
        val localServicesClass = XposedHelpers.findClassIfExists("com.android.server.LocalServices", classLoader)
        if (localServicesClass != null) {
            val sLocalServiceObjects = XposedHelpers.getStaticObjectField(localServicesClass, "sLocalServiceObjects") as? Map<*, *>
            if (sLocalServiceObjects != null) {
                for (entry in sLocalServiceObjects.entries) {
                    val keyClass = entry.key as? Class<*>
                    if (keyClass != null) {
                        XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", keyClass.classLoader)?.let {
                            logWifi("[SysWifi] Found WifiServiceImpl from LocalServices key: ${keyClass.name}")
                            return it
                        }
                    }
                    val service = entry.value ?: continue
                    val cl = service.javaClass.classLoader ?: continue
                    XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", cl)?.let {
                        logWifi("[SysWifi] Found WifiServiceImpl from LocalServices value: ${service.javaClass.name}")
                        return it
                    }
                }
            }
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] LocalServices scan error: $t")
    }

    // 4. 扫描 ServiceManager.sCache
    try {
        val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
        if (smClass != null) {
            val sCache = XposedHelpers.getStaticObjectField(smClass, "sCache") as? Map<*, *>
            if (sCache != null) {
                for ((k, v) in sCache) {
                    if (k == "wifi" || k == "wifiscanner") {
                        val cl = v?.javaClass?.classLoader
                        if (cl != null) {
                            XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", cl)?.let {
                                logWifi("[SysWifi] Found WifiServiceImpl from ServiceManager.sCache[$k]: ${v.javaClass.name}")
                                return it
                            }
                        }
                    }
                }
            }
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] ServiceManager.sCache scan error: $t")
    }

    // 5. 扫描 ApplicationLoaders.getDefault()
    try {
        val appLoadersClass = Class.forName("android.app.ApplicationLoaders")
        val getDefaultMethod = appLoadersClass.getMethod("getDefault")
        val instance = getDefaultMethod.invoke(null)
        for (field in appLoadersClass.declaredFields) {
            if (Map::class.java.isAssignableFrom(field.type)) {
                field.isAccessible = true
                val map = field.get(instance) as? Map<*, *> ?: continue
                for (value in map.values) {
                    val cl = value as? ClassLoader ?: continue
                    XposedHelpers.findClassIfExists("com.android.server.wifi.WifiServiceImpl", cl)?.let {
                        logWifi("[SysWifi] Found WifiServiceImpl from ApplicationLoaders.${field.name}")
                        return it
                    }
                }
            }
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] ApplicationLoaders scan error: $t")
    }

    return null
}

internal fun LocationHooker.tryHookPendingSystemServices(classLoader: ClassLoader) {
    if (!isWifiServiceHooked) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
            val wifiBinder = smClass?.let { XposedHelpers.callStaticMethod(it, "getService", "wifi") }
            if (wifiBinder != null && !wifiBinder.javaClass.name.contains("BinderProxy")) {
                val realClass = wifiBinder.javaClass
                val realCl = realClass.classLoader ?: classLoader
                logWifi("[SysWifi] Discovered REAL WifiServiceImpl via service poll: ${realClass.name}")
                installWifiHooks(realClass, realCl)
                isWifiServiceHooked = true
            }
        } catch (_: Throwable) {}
    }
    if (!isConnectivityServiceHooked) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
            val connBinder = smClass?.let { XposedHelpers.callStaticMethod(it, "getService", "connectivity") }
            if (connBinder != null && !connBinder.javaClass.name.contains("BinderProxy")) {
                val realClass = connBinder.javaClass
                val realCl = realClass.classLoader ?: classLoader
                logWifi("[SysWifi] Discovered REAL ConnectivityService via service poll: ${realClass.name}")
                installConnectivityHooks(realClass, realCl)
                isConnectivityServiceHooked = true
            }
        } catch (_: Throwable) {}
    }
}

internal fun LocationHooker.hookSystemWifiService(classLoader: ClassLoader) {
    logWifi("[SysWifi] hookSystemWifiService invoked, searching for WifiServiceImpl...")

    val wifiClass = findWifiServiceClass(classLoader)
    if (wifiClass != null) {
        installWifiHooks(wifiClass, wifiClass.classLoader ?: classLoader)
        isWifiServiceHooked = true
        logWifi("[SysWifi] Successfully hooked WifiServiceImpl on ${wifiClass.name}")
    }

    try {
        val systemServiceClass = XposedHelpers.findClassIfExists("com.android.server.SystemService", classLoader)
        if (systemServiceClass != null) {
            XposedHelpers.hookAllMethods(systemServiceClass, "publishBinderService") { chain, _ ->
                val name = chain.args.firstOrNull { it is String } as? String
                val service = chain.args.getOrNull(1)
                if (service != null && !service.javaClass.name.contains("BinderProxy")) {
                    if (name == "wifi" || name == "wifiscanner") {
                        val realClass = service.javaClass
                        val realCl = realClass.classLoader ?: classLoader
                        logWifi("[SysWifi] Captured $name from publishBinderService: ${realClass.name}")
                        installWifiHooks(realClass, realCl)
                        isWifiServiceHooked = true
                    } else if (name == "connectivity") {
                        val realClass = service.javaClass
                        val realCl = realClass.classLoader ?: classLoader
                        logWifi("[SysWifi] Captured $name from publishBinderService: ${realClass.name}")
                        installConnectivityHooks(realClass, realCl)
                        isConnectivityServiceHooked = true
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            logWifi("[SysWifi] SystemService.publishBinderService hooked")
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] Hook publishBinderService failed: $t")
    }

    try {
        val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
        if (smClass != null) {
            // 直接尝试 getService("wifi")
            try {
                val service = XposedHelpers.callStaticMethod(smClass, "getService", "wifi")
                if (service != null && !service.javaClass.name.contains("BinderProxy")) {
                    installWifiHooks(service.javaClass, service.javaClass.classLoader ?: classLoader)
                    isWifiServiceHooked = true
                    logWifi("[SysWifi] Hooked WifiServiceImpl directly from ServiceManager.getService(wifi): ${service.javaClass.name}")
                    return
                }
            } catch (_: Throwable) {}

            XposedHelpers.hookAllMethods(smClass, "addService") { chain, _ ->
                val name = chain.args.firstOrNull { it is String } as? String
                if ((name == "wifi" || name == "wifiscanner") && chain.args.size > 1) {
                    val service = chain.args[1]
                    if (service != null && !service.javaClass.name.contains("BinderProxy")) {
                        logWifi("[SysWifi] Captured $name from ServiceManager.addService: ${service.javaClass.name}")
                        installWifiHooks(service.javaClass, service.javaClass.classLoader ?: classLoader)
                        isWifiServiceHooked = true
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] Hook ServiceManager.addService for wifi failed: $t")
    }
}

internal fun LocationHooker.installWifiHooks(wifiServiceClass: Class<*>, classLoader: ClassLoader) {
    if (hookedCallbackClasses.putIfAbsent(wifiServiceClass, true) != null) {
        return
    }

    val realCapabilities = listOf(
        "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
        "[WPA2-PSK-CCMP+TKIP][RSN-PSK-CCMP+TKIP][ESS]",
        "[WPA2-PSK-CCMP][ESS][WPS]",
        "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]",
        "[RSN-PSK-CCMP][ESS]",
        "[WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]",
        "[ESS]",
        "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS][WPS]",
        "[WPA2-SAE-CCMP][RSN-SAE-CCMP][ESS]",
        "[WPA2-PSK+SAE-CCMP][RSN-PSK+SAE-CCMP][ESS]"
    )

    // =========================================================================
    // 1. getScanResults：向目标应用派发伪造周边 Wi-Fi 列表
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(wifiServiceClass, "getScanResults") { chain, executable ->
            val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean("active", false)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
            val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

            if (!isTarget) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val mockWifi = config.optBoolean("mock_wifi", true)
            if (!mockWifi) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val fakeList = java.util.ArrayList<Any>()
            val wifiObj = config.optJSONObject("wifi_json")

            try {
                val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()
                val rng = Random()

                fun addFakeScanResult(wifi: JSONObject) {
                    val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                    val ssidVal = wifi.optString("ssid", "")
                    val bssidVal = wifi.optString("bssid", "")
                    val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") {
                        "WIFI_${bssidVal.takeLast(5).replace(":", "")}"
                    } else {
                        ssidVal
                    }
                    XposedHelpers.setObjectField(fakeScanResult, "SSID", finalSsid)
                    XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssidVal)
                    val level = wifi.optInt("level", -65)
                    XposedHelpers.setIntField(fakeScanResult, "level", level)
                    XposedHelpers.setIntField(fakeScanResult, "frequency", wifi.optInt("frequency", 2412))
                    XposedHelpers.setObjectField(
                        fakeScanResult,
                        "capabilities",
                        wifi.optString("capabilities", realCapabilities[rng.nextInt(realCapabilities.size)])
                    )

                    // 适配 Android 10+ (API 29+): 设置 wifiSsid 对象，防止高德/微信等 SDK 读不到 SSID
                    try {
                        val wifiSsidClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiSsid", classLoader)
                        if (wifiSsidClass != null) {
                            val wifiSsidObj = XposedHelpers.callStaticMethod(
                                wifiSsidClass,
                                "fromBytes",
                                finalSsid.toByteArray(Charsets.UTF_8)
                            )
                            if (wifiSsidObj != null) {
                                try { XposedHelpers.setObjectField(fakeScanResult, "wifiSsid", wifiSsidObj) } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}

                    // 适配 ColorOS / OxygenOS / HyperOS: 必须初始化 informationElements 和 radioChainInfos，
                    // 否则系统服务中的统计上报组件抛出 NPE
                    try {
                        val ieClass = XposedHelpers.findClassIfExists("android.net.wifi.ScanResult\$InformationElement", classLoader)
                        if (ieClass != null) {
                            val emptyIeArray = ReflectArray.newInstance(ieClass, 0)
                            XposedHelpers.setObjectField(fakeScanResult, "informationElements", emptyIeArray)
                        }
                    } catch (_: Throwable) {}

                    try {
                        val rciClass = XposedHelpers.findClassIfExists("android.net.wifi.ScanResult\$RadioChainInfo", classLoader)
                        if (rciClass != null) {
                            val emptyRciArray = ReflectArray.newInstance(rciClass, 0)
                            XposedHelpers.setObjectField(fakeScanResult, "radioChainInfos", emptyRciArray)
                        }
                    } catch (_: Throwable) {}

                    try {
                        val offsetNanos = (rng.nextInt(200_000) * 1000L)
                        XposedHelpers.setLongField(fakeScanResult, "timestamp", (baseTimestamp - offsetNanos) / 1000)
                    } catch (_: Throwable) {}

                    fakeList.add(fakeScanResult)
                }

                if (wifiObj != null) {
                    val isConnected = wifiObj.optBoolean("isConnected", false)
                    val connectedWifi = if (isConnected) wifiObj.optJSONObject("connectedWifi") else null
                    if (connectedWifi != null) {
                        addFakeScanResult(connectedWifi)
                    }

                    val nearbyArray = wifiObj.optJSONArray("nearbyWifi")
                    if (nearbyArray != null) {
                        for (i in 0 until nearbyArray.length()) {
                            val wifi = nearbyArray.getJSONObject(i)
                            addFakeScanResult(wifi)
                        }
                    }
                }

                // 若没有采集或设置 Wi-Fi 列表，按坐标 Hash 稳定生成 5 个虚拟热点
                if (fakeList.isEmpty()) {
                    val lat = config.optDouble("lat", 0.0)
                    val lng = config.optDouble("lng", 0.0)
                    val seed = ((lat * 100000).toLong() xor (lng * 100000).toLong())
                    val random = Random(seed)
                    for (i in 0 until 5) {
                        val fakeWifi = JSONObject()
                        fakeWifi.put("ssid", "WIFI_${random.nextInt(9000) + 1000}")
                        val bssid = String.format(
                            "%02x:%02x:%02x:%02x:%02x:%02x",
                            random.nextInt(256), random.nextInt(256), random.nextInt(256),
                            random.nextInt(256), random.nextInt(256), random.nextInt(256)
                        )
                        fakeWifi.put("bssid", bssid)
                        fakeWifi.put("level", -40 - random.nextInt(50))
                        fakeWifi.put("frequency", if (random.nextBoolean()) 2412 else 5180)
                        fakeWifi.put("capabilities", "[WPA2-PSK-CCMP][ESS]")
                        addFakeScanResult(fakeWifi)
                    }
                }

                logWifi("[SysWifi] Dispatched ${fakeList.size} fake scan results to ${explicitPkg ?: "caller"}")

                // 核心修复: Android 8~15 中 WifiServiceImpl.getScanResults 返回类型通常是 ParceledListSlice<ScanResult>
                // 在 APEX 模块中为 com.android.wifi.x.com.android.modules.utils.ParceledListSlice，必须用原方法 returnType 实例化
                val returnType = (executable as? java.lang.reflect.Method)?.returnType
                if (returnType != null && returnType.name.contains("ParceledListSlice")) {
                    try {
                        val slice = XposedHelpers.newInstance(returnType, fakeList)
                        if (slice != null && returnType.isInstance(slice)) {
                            return@hookAllMethods slice
                        }
                    } catch (t: Throwable) {
                        logWifi("[SysWifi] Instantiate returnType ParceledListSlice failed: $t")
                    }
                }

                val realResult = try { chain.proceed(chain.args.toTypedArray()) } catch (_: Throwable) { null }
                if (realResult != null && realResult.javaClass.name.contains("ParceledListSlice")) {
                    try {
                        val slice = XposedHelpers.newInstance(realResult.javaClass, fakeList)
                        if (slice != null && (returnType == null || returnType.isInstance(slice))) {
                            return@hookAllMethods slice
                        }
                    } catch (t: Throwable) {
                        logWifi("[SysWifi] Instantiate realResult ParceledListSlice failed: $t")
                    }
                }

                val sliceClass = XposedHelpers.findClassIfExists("android.content.pm.ParceledListSlice", classLoader)
                    ?: XposedHelpers.findClassIfExists("android.content.pm.ParceledListSlice", scanResultClass.classLoader)
                if (sliceClass != null && (returnType == null || returnType.isAssignableFrom(sliceClass))) {
                    try {
                        val slice = XposedHelpers.newInstance(sliceClass, fakeList)
                        if (slice != null) return@hookAllMethods slice
                    } catch (_: Throwable) {}
                }

                if (returnType == null || returnType.isAssignableFrom(java.util.ArrayList::class.java) || returnType.name.contains("List")) {
                    return@hookAllMethods fakeList
                }
                return@hookAllMethods realResult
            } catch (e: Throwable) {
                logWifi("[SysWifi] build fake scan results failed: $e")
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        }
        logWifi("[SysWifi] WifiServiceImpl.getScanResults hooked")
    } catch (e: Throwable) {
        logWifi("[SysWifi] hook getScanResults failed: $e")
    }

    // =========================================================================
    // 2. getConnectionInfo：向目标应用派发伪造已连接 Wi-Fi 属性
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(wifiServiceClass, "getConnectionInfo") { chain, _ ->
            val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean("active", false)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
            val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)

            if (!isTarget) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val mockWifi = config.optBoolean("mock_wifi", true)
            if (!mockWifi) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val wifiObj = config.optJSONObject("wifi_json")
            val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
            val connectedWifi = if (isConnected) wifiObj!!.optJSONObject("connectedWifi") else null

            val ssidVal: String
            val bssidVal: String
            val freqVal: Int
            val macAddressVal: String
            val linkSpeedVal: Int
            val levelVal: Int
            val networkIdVal: Int

            if (isConnected && connectedWifi != null) {
                val rawSsid = connectedWifi.optString("ssid", "")
                ssidVal = if (rawSsid.isEmpty() || rawSsid == "<unknown ssid>") "HOME_WIFI" else rawSsid
                bssidVal = connectedWifi.optString("bssid", "02:00:00:00:00:00")
                freqVal = connectedWifi.optInt("frequency", 2412)
                macAddressVal = connectedWifi.optString("macAddress", bssidVal)
                linkSpeedVal = connectedWifi.optInt("linkSpeed", 65)
                levelVal = connectedWifi.optInt("level", -65)
                networkIdVal = connectedWifi.optInt("networkId", 1)
            } else {
                // 目标应用处于连入状态但用户未配置虚拟连入热点：坚决不能将用户家里的真实 BSSID 泄露给目标应用！
                val lat = config.optDouble("lat", 0.0)
                val lng = config.optDouble("lng", 0.0)
                val seed = ((lat * 100000).toLong() xor (lng * 100000).toLong())
                val random = Random(seed)
                bssidVal = String.format(
                    "02:%02x:%02x:%02x:%02x:%02x",
                    random.nextInt(256), random.nextInt(256),
                    random.nextInt(256), random.nextInt(256), random.nextInt(256)
                )
                ssidVal = "WIFI_${random.nextInt(9000) + 1000}"
                freqVal = if (random.nextBoolean()) 2412 else 5180
                macAddressVal = bssidVal
                linkSpeedVal = 144
                levelVal = -50 - random.nextInt(20)
                networkIdVal = 1
            }

            // 优先尝试 Android 12+ (API 30+) 的 WifiInfo.Builder 构建完全干净的隔离对象
            try {
                val builderClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiInfo\$Builder", classLoader)
                if (builderClass != null) {
                    val builder = XposedHelpers.newInstance(builderClass)
                    XposedHelpers.callMethod(builder, "setSsid", ssidVal.toByteArray(Charsets.UTF_8))
                    XposedHelpers.callMethod(builder, "setBssid", bssidVal)
                    XposedHelpers.callMethod(builder, "setRssi", levelVal)
                    XposedHelpers.callMethod(builder, "setNetworkId", networkIdVal)
                    val cleanWifiInfo = XposedHelpers.callMethod(builder, "build")
                    if (cleanWifiInfo != null) {
                        try { XposedHelpers.setObjectField(cleanWifiInfo, "mMacAddress", macAddressVal) } catch (_: Throwable) {}
                        try { XposedHelpers.setIntField(cleanWifiInfo, "mLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
                        try { XposedHelpers.setIntField(cleanWifiInfo, "mFrequency", freqVal) } catch (_: Throwable) {}
                        logWifi("[SysWifi] Dispatched clean synthetic WifiInfo for ${explicitPkg ?: "caller"} (SSID=$ssidVal, BSSID=$bssidVal)")
                        return@hookAllMethods cleanWifiInfo
                    }
                }
            } catch (_: Throwable) {}

            // 降级使用原有对象就地修改
            val currentResult = chain.proceed(chain.args.toTypedArray())
            if (currentResult != null) {
                try {
                    // Android 10+ (API 29+) 核心：通过 WifiSsid.fromBytes 改写 mWifiSsid，杜绝真实 SSID 泄露！
                    try {
                        val wifiSsidClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiSsid", classLoader)
                        if (wifiSsidClass != null) {
                            val wifiSsidObj = XposedHelpers.callStaticMethod(
                                wifiSsidClass,
                                "fromBytes",
                                ssidVal.toByteArray(Charsets.UTF_8)
                            )
                            if (wifiSsidObj != null) {
                                try { XposedHelpers.setObjectField(currentResult, "mWifiSsid", wifiSsidObj) } catch (_: Throwable) {}
                                try { XposedHelpers.callMethod(currentResult, "setSSID", wifiSsidObj) } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: Throwable) {}

                    try { XposedHelpers.setObjectField(currentResult, "mSSID", "\"$ssidVal\"") } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(currentResult, "mBSSID", bssidVal) } catch (_: Throwable) {}
                    try { XposedHelpers.callMethod(currentResult, "setBSSID", bssidVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(currentResult, "mMacAddress", macAddressVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mRssi", levelVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mFrequency", freqVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mNetworkId", networkIdVal) } catch (_: Throwable) {}

                    logWifi("[SysWifi] Injected fake connection info for ${explicitPkg ?: "caller"} (SSID=$ssidVal, BSSID=$bssidVal, maskedRealBssid=true)")
                } catch (e: Throwable) {
                    logWifi("[SysWifi] modify WifiInfo in-place failed: $e")
                }
            }
            return@hookAllMethods currentResult
        }
        logWifi("[SysWifi] WifiServiceImpl.getConnectionInfo hooked")
    } catch (e: Throwable) {
        logWifi("[SysWifi] hook getConnectionInfo failed: $e")
    }

    // =========================================================================
    // 2.1 WifiScanningServiceImpl：拦截通过 WifiScanner.getSingleScanResults 获取热点的系统定位/反作弊 SDK
    // =========================================================================
    try {
        val scannerClass = XposedHelpers.findClassIfExists(
            "com.android.server.wifi.scanner.WifiScanningServiceImpl",
            wifiServiceClass.classLoader ?: classLoader
        )
        if (scannerClass != null && hookedCallbackClasses.putIfAbsent(scannerClass, true) == null) {
            val singleScanMethods = arrayOf("getSingleScanResults")
            for (mName in singleScanMethods) {
                XposedHelpers.hookAllMethods(scannerClass, mName) { chain, _ ->
                    val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    if (!config.optBoolean("active", false)) {
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)
                    if (!isTarget) {
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                    val mockWifi = config.optBoolean("mock_wifi", true)
                    if (!mockWifi) {
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                    val fakeList = java.util.ArrayList<Any>()
                    val scanResultClass = XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                    val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()
                    val rng = Random()
                    val lat = config.optDouble("lat", 0.0)
                    val lng = config.optDouble("lng", 0.0)
                    val seed = ((lat * 100000).toLong() xor (lng * 100000).toLong())
                    val random = Random(seed)
                    for (i in 0 until 5) {
                        val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                        XposedHelpers.setObjectField(fakeScanResult, "SSID", "WIFI_${random.nextInt(9000) + 1000}")
                        val bssid = String.format(
                            "%02x:%02x:%02x:%02x:%02x:%02x",
                            random.nextInt(256), random.nextInt(256), random.nextInt(256),
                            random.nextInt(256), random.nextInt(256), random.nextInt(256)
                        )
                        XposedHelpers.setObjectField(fakeScanResult, "BSSID", bssid)
                        XposedHelpers.setObjectField(fakeScanResult, "capabilities", "[WPA2-PSK-CCMP][ESS]")
                        XposedHelpers.setIntField(fakeScanResult, "level", -40 - random.nextInt(50))
                        XposedHelpers.setIntField(fakeScanResult, "frequency", if (random.nextBoolean()) 2412 else 5180)
                        XposedHelpers.setLongField(fakeScanResult, "timestamp", baseTimestamp - (rng.nextInt(500) * 1000L))
                        fakeList.add(fakeScanResult)
                    }
                    logWifi("[SysWifi] WifiScanningServiceImpl.getSingleScanResults intercepted for ${explicitPkg ?: "caller"}")
                    return@hookAllMethods fakeList
                }
            }
            logWifi("[SysWifi] WifiScanningServiceImpl.getSingleScanResults hooked")
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] hook WifiScanningServiceImpl failed: $t")
    }
}

// =========================================================================
// 3. ConnectivityService：拦截现代应用通过 ConnectivityManager.getNetworkCapabilities 读取真实 Wi-Fi BSSID
// =========================================================================

@Volatile
internal var isConnectivityServiceHooked = false

private fun findConnectivityServiceClass(classLoader: ClassLoader): Class<*>? {
    XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", classLoader)?.let { return it }
    XposedHelpers.findClassIfExists("com.android.server.connectivity.ConnectivityService", classLoader)?.let { return it }

    try {
        val threads = Thread.getAllStackTraces().keys
        for (t in threads) {
            val name = t.name
            if (name.contains("Connect", ignoreCase = true) || name.contains("Tether", ignoreCase = true) || name.contains("Net", ignoreCase = true)) {
                val cl = t.contextClassLoader ?: continue
                XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", cl)?.let { return it }
                XposedHelpers.findClassIfExists("com.android.server.connectivity.ConnectivityService", cl)?.let { return it }
            }
        }
    } catch (_: Throwable) {}

    try {
        val localServicesClass = XposedHelpers.findClassIfExists("com.android.server.LocalServices", classLoader)
        if (localServicesClass != null) {
            val sLocalServiceObjects = XposedHelpers.getStaticObjectField(localServicesClass, "sLocalServiceObjects") as? Map<*, *>
            if (sLocalServiceObjects != null) {
                for (service in sLocalServiceObjects.values) {
                    if (service == null) continue
                    val cl = service.javaClass.classLoader ?: continue
                    XposedHelpers.findClassIfExists("com.android.server.ConnectivityService", cl)?.let { return it }
                    XposedHelpers.findClassIfExists("com.android.server.connectivity.ConnectivityService", cl)?.let { return it }
                }
            }
        }
    } catch (_: Throwable) {}

    return null
}

internal fun LocationHooker.hookSystemConnectivityService(classLoader: ClassLoader) {
    if (isConnectivityServiceHooked) return

    val connClass = findConnectivityServiceClass(classLoader)
    if (connClass != null) {
        installConnectivityHooks(connClass, connClass.classLoader ?: classLoader)
        isConnectivityServiceHooked = true
        logWifi("[SysWifi] Successfully hooked ConnectivityService on ${connClass.name}")
    }

    try {
        val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
        if (smClass != null) {
            XposedHelpers.hookAllMethods(smClass, "addService") { chain, _ ->
                val name = chain.args.firstOrNull { it is String } as? String
                if (name == "connectivity" && chain.args.size > 1) {
                    val service = chain.args[1]
                    if (service != null && !service.javaClass.name.contains("BinderProxy")) {
                        installConnectivityHooks(service.javaClass, service.javaClass.classLoader ?: classLoader)
                        isConnectivityServiceHooked = true
                        logWifi("[SysWifi] Captured $name from ServiceManager.addService: ${service.javaClass.name}")
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        }
    } catch (t: Throwable) {
        logWifi("[SysWifi] Hook ServiceManager for connectivity failed: $t")
    }
}

internal fun LocationHooker.installConnectivityHooks(connClazz: Class<*>, classLoader: ClassLoader) {
    if (hookedCallbackClasses.putIfAbsent(connClazz, true) != null) {
        return
    }
    isConnectivityServiceHooked = true

    val targetMethods = arrayOf("getNetworkCapabilities", "getDefaultNetworkCapabilitiesForUser", "getRedactedNetworkCapabilitiesForPackage")
    for (methodName in targetMethods) {
        try {
            XposedHelpers.hookAllMethods(connClazz, methodName) { chain, _ ->
                val currentResult = chain.proceed(chain.args.toTypedArray()) ?: return@hookAllMethods null
                val config = readConfig() ?: return@hookAllMethods currentResult
                if (!config.optBoolean("active", false)) return@hookAllMethods currentResult
                if (!config.optBoolean("mock_wifi", true)) return@hookAllMethods currentResult

                val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                val overrideUid = SystemHookUtils.extractCallerUid(chain.args)
                val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config, overrideUid)
                if (!isTarget) return@hookAllMethods currentResult

                try {
                    sanitizeNetworkCapabilities(currentResult, config, classLoader, explicitPkg)
                } catch (e: Throwable) {
                    logWifi("[SysWifi] sanitizeNetworkCapabilities failed: $e")
                }
                return@hookAllMethods currentResult
            }
            logWifi("[SysWifi] ConnectivityService.$methodName hooked on ${connClazz.name}")
        } catch (e: Throwable) {
            logWifi("[SysWifi] hook ConnectivityService.$methodName failed: $e")
        }
    }
}

private fun LocationHooker.sanitizeNetworkCapabilities(
    resultObj: Any,
    config: JSONObject,
    classLoader: ClassLoader,
    explicitPkg: String?
) {
    if (resultObj is Array<*>) {
        for (item in resultObj) {
            if (item != null) sanitizeSingleNetworkCapabilities(item, config, classLoader, explicitPkg)
        }
    } else {
        sanitizeSingleNetworkCapabilities(resultObj, config, classLoader, explicitPkg)
    }
}

private fun LocationHooker.sanitizeSingleNetworkCapabilities(
    nc: Any,
    config: JSONObject,
    classLoader: ClassLoader,
    explicitPkg: String?
) {
    val transportInfo = try {
        XposedHelpers.getObjectField(nc, "mTransportInfo")
    } catch (_: Throwable) {
        try { XposedHelpers.callMethod(nc, "getTransportInfo") } catch (_: Throwable) { null }
    } ?: return

    val isWifiInfo = transportInfo.javaClass.name.contains("WifiInfo") ||
            LocationHooker.hasTypeByName(transportInfo.javaClass, "android.net.wifi.WifiInfo")

    if (!isWifiInfo) return

    val wifiObj = config.optJSONObject("wifi_json")
    val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
    val connectedWifi = if (isConnected) wifiObj!!.optJSONObject("connectedWifi") else null

    val ssidVal: String
    val bssidVal: String
    val freqVal: Int
    val macAddressVal: String
    val linkSpeedVal: Int
    val levelVal: Int
    val networkIdVal: Int

    if (isConnected && connectedWifi != null) {
        val rawSsid = connectedWifi.optString("ssid", "")
        ssidVal = if (rawSsid.isEmpty() || rawSsid == "<unknown ssid>") "HOME_WIFI" else rawSsid
        bssidVal = connectedWifi.optString("bssid", "02:00:00:00:00:00")
        freqVal = connectedWifi.optInt("frequency", 2412)
        macAddressVal = connectedWifi.optString("macAddress", bssidVal)
        linkSpeedVal = connectedWifi.optInt("linkSpeed", 65)
        levelVal = connectedWifi.optInt("level", -65)
        networkIdVal = connectedWifi.optInt("networkId", 1)
    } else {
        val lat = config.optDouble("lat", 0.0)
        val lng = config.optDouble("lng", 0.0)
        val seed = ((lat * 100000).toLong() xor (lng * 100000).toLong())
        val random = Random(seed)
        bssidVal = String.format(
            "02:%02x:%02x:%02x:%02x:%02x",
            random.nextInt(256), random.nextInt(256),
            random.nextInt(256), random.nextInt(256), random.nextInt(256)
        )
        ssidVal = "WIFI_${random.nextInt(9000) + 1000}"
        freqVal = if (random.nextBoolean()) 2412 else 5180
        macAddressVal = bssidVal
        linkSpeedVal = 144
        levelVal = -50 - random.nextInt(20)
        networkIdVal = 1
    }

    var cleanWifiInfo: Any? = null
    try {
        val builderClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiInfo\$Builder", classLoader)
        if (builderClass != null) {
            val builder = XposedHelpers.newInstance(builderClass)
            XposedHelpers.callMethod(builder, "setSsid", ssidVal.toByteArray(Charsets.UTF_8))
            XposedHelpers.callMethod(builder, "setBssid", bssidVal)
            XposedHelpers.callMethod(builder, "setRssi", levelVal)
            XposedHelpers.callMethod(builder, "setNetworkId", networkIdVal)
            cleanWifiInfo = XposedHelpers.callMethod(builder, "build")
            if (cleanWifiInfo != null) {
                try { XposedHelpers.setObjectField(cleanWifiInfo, "mMacAddress", macAddressVal) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cleanWifiInfo, "mLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
                try { XposedHelpers.setIntField(cleanWifiInfo, "mFrequency", freqVal) } catch (_: Throwable) {}
            }
        }
    } catch (_: Throwable) {}

    val targetWifiInfo = if (cleanWifiInfo != null) {
        cleanWifiInfo
    } else {
        val copy = try {
            XposedHelpers.newInstance(transportInfo.javaClass, transportInfo)
        } catch (_: Throwable) {
            transportInfo
        }
        try {
            val wifiSsidClass = XposedHelpers.findClassIfExists("android.net.wifi.WifiSsid", classLoader)
            if (wifiSsidClass != null) {
                val wifiSsidObj = XposedHelpers.callStaticMethod(
                    wifiSsidClass,
                    "fromBytes",
                    ssidVal.toByteArray(Charsets.UTF_8)
                )
                if (wifiSsidObj != null) {
                    try { XposedHelpers.setObjectField(copy, "mWifiSsid", wifiSsidObj) } catch (_: Throwable) {}
                    try { XposedHelpers.callMethod(copy, "setSSID", wifiSsidObj) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        try { XposedHelpers.setObjectField(copy, "mSSID", "\"$ssidVal\"") } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(copy, "mBSSID", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.callMethod(copy, "setBSSID", bssidVal) } catch (_: Throwable) {}
        try { XposedHelpers.setObjectField(copy, "mMacAddress", macAddressVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(copy, "mRssi", levelVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(copy, "mLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(copy, "mFrequency", freqVal) } catch (_: Throwable) {}
        try { XposedHelpers.setIntField(copy, "mNetworkId", networkIdVal) } catch (_: Throwable) {}
        copy
    }

    try {
        XposedHelpers.setObjectField(nc, "mTransportInfo", targetWifiInfo)
        logWifi("[SysWifi] Sanitized ConnectivityService NetworkCapabilities.mTransportInfo for ${explicitPkg ?: "caller"} (SSID=$ssidVal, BSSID=$bssidVal)")
    } catch (e: Throwable) {
        logWifi("[SysWifi] Set mTransportInfo on NetworkCapabilities failed: $e")
    }
}
