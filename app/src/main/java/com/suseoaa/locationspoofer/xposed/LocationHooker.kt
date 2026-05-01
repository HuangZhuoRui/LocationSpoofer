package com.suseoaa.locationspoofer.xposed

import android.location.Location
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.suseoaa.locationspoofer.utils.CoordinateConverter
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONObject
import java.io.File
import java.util.ArrayList

class LocationHooker : IXposedHookLoadPackage {

    companion object {
        // 需要注入的目标应用包名（含前缀匹配，覆盖所有子进程如 :appbrand0, :tools 等）
        val TARGET_PACKAGES = setOf(
            "com.tencent.mm",           // 微信（含所有 :appbrand 小程序子进程）
            "com.chaoxing.mobile",      // 超星学习通
            "cn.chaoxing.lemon",        // 学习通备用包名
            "com.alibaba.android.rimet",// 钉钉
            "com.sankuai.meituan",      // 美团
            "com.baidu.BaiduMap",       // 百度地图
            "com.autonavi.minimap",     // 高德地图
            "com.tencent.map",          // 腾讯地图
            "com.android.systemui",     // 系统UI（覆盖系统级定位弹窗）
            "com.google.android.gms",   // Google Play 服务（覆盖 Fused Location Provider）
        )

        // 需要返回 GCJ-02 的应用包名（高德/腾讯等国内地图生态）
        val GCJ_PACKAGES = setOf(
            "com.tencent.mm",
            "com.chaoxing.mobile",
            "cn.chaoxing.lemon",
            "com.alibaba.android.rimet",
            "com.sankuai.meituan",
            "com.baidu.BaiduMap",
            "com.autonavi.minimap",
            "com.tencent.map"
        )

        // 系统进程同样需要覆盖（android进程持有LocationManagerService）
        val SYSTEM_PACKAGES = setOf("android", "system", "com.android.phone")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val pkg = lpparam.packageName

        // 宿主App自报平安
        if (pkg == "com.suseoaa.locationspoofer") {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.suseoaa.locationspoofer.utils.LSPosedManager",
                    lpparam.classLoader,
                    "isModuleActive",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }
            return // 宿主App不需要注入定位Hook
        }

        // 在应用加载时读取活动与全局标志（一次性，快速 I/O）
        val flags = readConfigFlagsFromFile()
        val globalMode = flags.globalMode
        val configActive = flags.active
        val hookAllPackages = globalMode || configActive

        // 系统进程：默认只Hook Location API，开启全局模式时追加网络/基站接管
        if (SYSTEM_PACKAGES.contains(pkg)) {
            hookLocationAPIs(lpparam.classLoader, useGcj = false)
            hookLocationManagerOverrides(lpparam.classLoader, useGcj = false)
            if (hookAllPackages) {
                hookNetworkAndCellAPIs(lpparam.classLoader)
                hookBluetoothLE(lpparam.classLoader)
            }
            return
        }

        // 精确包名匹配 + 子进程前缀匹配（如 com.tencent.mm:appbrand0）
        // 全局模式下跳过包名过滤，钩住所有应用
        val isTarget = hookAllPackages || TARGET_PACKAGES.any { target ->
            pkg == target || pkg.startsWith("$target:")
        }

        if (!isTarget) return

        XposedBridge.log("[LocationSpoofer] Hooking package: $pkg (global=$globalMode)")

        val useGcj = isGcjPackage(pkg)
        hookLocationAPIs(lpparam.classLoader, useGcj)
        hookLocationManagerOverrides(lpparam.classLoader, useGcj)
        hookNetworkAndCellAPIs(lpparam.classLoader)
        hookBluetoothLE(lpparam.classLoader)
    }

    private fun isGcjPackage(pkg: String): Boolean {
        return GCJ_PACKAGES.any { target -> pkg == target || pkg.startsWith("$target:") }
    }

    /**
     * 从配置文件读取全局模式标志。
     * 此方法在 handleLoadPackage 中调用，即每个应用进程启动时执行一次。
     * 读取独立于 readConfig() 缓存之外，保证实时性。
     */
    private data class ConfigFlags(val active: Boolean, val globalMode: Boolean)

    private fun readConfigFlagsFromFile(): ConfigFlags {
        return try {
            val file = java.io.File("/data/local/tmp/locationspoofer_config.json")
            if (!file.exists() || !file.canRead()) return ConfigFlags(false, false)
            val json = org.json.JSONObject(file.readText())
            val active = json.optBoolean("active", false)
            val globalMode = json.optBoolean("is_global_mode", false)
            ConfigFlags(active, globalMode)
        } catch (e: Exception) {
            ConfigFlags(false, false)
        }
    }

    private var startTimestamp = System.currentTimeMillis()

    private fun getJitteredLocation(baseLat: Double, baseLng: Double): Pair<Double, Double> {
        val elapsed = System.currentTimeMillis() - startTimestamp
        val driftLat = kotlin.math.sin(elapsed / 10000.0) * 0.000015
        val driftLng = kotlin.math.cos(elapsed / 12000.0) * 0.000015
        return Pair(baseLat + driftLat, baseLng + driftLng)
    }

    private fun getJitteredAccuracy(): Float {
        val elapsed = System.currentTimeMillis() - startTimestamp
        return (20.0 + 10.0 * kotlin.math.sin(elapsed / 5000.0)).toFloat()
    }

    private fun resolveBaseLatLng(
        config: JSONObject,
        fallbackLat: Double,
        fallbackLng: Double,
        useGcj: Boolean
    ): Pair<Double, Double> {
        val rawLat = config.optDouble("lat", fallbackLat)
        val rawLng = config.optDouble("lng", fallbackLng)
        return if (useGcj) CoordinateConverter.wgs84ToGcj02(rawLat, rawLng) else Pair(
            rawLat,
            rawLng
        )
    }

    private fun findClassIfExists(name: String, classLoader: ClassLoader): Class<*>? {
        return try {
            XposedHelpers.findClassIfExists(name, classLoader)
        } catch (e: Throwable) {
            null
        }
    }

    private fun hookLocationAPIs(classLoader: ClassLoader, useGcj: Boolean) {
        try {
            val getLatHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val (baseLat, baseLng) = resolveBaseLatLng(
                            config,
                            fallbackLat = param.result as Double,
                            fallbackLng = 0.0,
                            useGcj = useGcj
                        )
                        param.result = getJitteredLocation(baseLat, baseLng).first
                    }
                }
            }

            val getLngHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val (baseLat, baseLng) = resolveBaseLatLng(
                            config,
                            fallbackLat = 0.0,
                            fallbackLng = param.result as Double,
                            useGcj = useGcj
                        )
                        param.result = getJitteredLocation(baseLat, baseLng).second
                    }
                }
            }

            val getAccHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = getJitteredAccuracy()
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLatitude",
                getLatHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLongitude",
                getLngHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getAccuracy",
                getAccHook
            )

            // ★ 核心反检测：抹除 isFromMockProvider 标志位（strategy:100 的根本来源）
            val antiMockHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = false
                    }
                }
            }
            // Android 6~11: isFromMockProvider()
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "isFromMockProvider",
                antiMockHook
            )
            // Android 12+: isMock()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.Location",
                    classLoader,
                    "isMock",
                    antiMockHook
                )
            } catch (e: Throwable) { /* API < 31 没有此方法 */
            }

            // ★ Android 13 专项：直接对 Location 对象的 mMock / mIsFromMockProvider 字段写 false
            // (Android 12+ 字段名改为 mMock，Android 6-11 为 mIsFromMockProvider)
            val fieldCleanHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val loc = param.thisObject ?: return
                        try {
                            XposedHelpers.setBooleanField(loc, "mMock", false)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false)
                        } catch (e: Throwable) {
                        }
                        // 清理 extras bundle 中可能残留的 mock 标记
                        try {
                            val extras =
                                XposedHelpers.callMethod(loc, "getExtras") as? android.os.Bundle
                            extras?.remove("mockLocation")
                            extras?.remove("isMock")
                        } catch (e: Throwable) {
                        }
                    }
                }
            }
            // 在 getLatitude/getLongitude/getAccuracy 时同步清字段，确保在实际读值前已抹除
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLatitude",
                fieldCleanHook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location",
                classLoader,
                "getLongitude",
                fieldCleanHook
            )

            // ★ 拦截 Settings.Secure.getInt("mock_location") — 部分ROM通过这个判断是否开了开发者模式模拟位置
            try {
                XposedHelpers.findAndHookMethod(
                    "android.provider.Settings\$Secure",
                    classLoader,
                    "getInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val key = param.args[1] as? String ?: return
                                if (key == "mock_location" || key == "allow_mock_location") {
                                    param.result = 0 // 0 = 关闭模拟位置（欺骗系统认为我们没开）
                                }
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // ★ 拦截 getProvider：将 "mock" / "test" 提供者名隐藏，换成 "gps"
            XposedHelpers.findAndHookMethod(
                "android.location.Location", classLoader, "getProvider",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val provider = param.result as? String ?: return
                            if (provider.contains("mock", ignoreCase = true) ||
                                provider.contains("test", ignoreCase = true) ||
                                provider.contains("fake", ignoreCase = true)
                            ) {
                                param.result = android.location.LocationManager.GPS_PROVIDER
                            }
                        }
                    }
                })

            // ★ 拦截 LocationManager.getProviders() / getAllProviders()：移除 mock/test 提供者
            val providerListHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        @Suppress("UNCHECKED_CAST")
                        val list = param.result as? MutableList<String> ?: return
                        val cleaned = list.filterNot {
                            it.contains("mock", ignoreCase = true) ||
                                    it.contains("test", ignoreCase = true) ||
                                    it.contains("fake", ignoreCase = true)
                        }.toMutableList()
                        if (!cleaned.contains(android.location.LocationManager.GPS_PROVIDER))
                            cleaned.add(android.location.LocationManager.GPS_PROVIDER)
                        param.result = cleaned
                    }
                }
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getProviders",
                    Boolean::class.javaPrimitiveType!!, providerListHook
                )
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", classLoader, "getAllProviders",
                    providerListHook
                )
            } catch (e: Throwable) {
                XposedBridge.log(e)
            }

            // AMap SDK 专属 Hook（含抖动，与原生Location保持同步）
            val amapHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val (baseLat, baseLng) = resolveBaseLatLng(
                            config,
                            fallbackLat = 0.0,
                            fallbackLng = 0.0,
                            useGcj = useGcj
                        )
                        val jittered = getJitteredLocation(baseLat, baseLng)
                        when (param.method.name) {
                            "getLatitude" -> param.result = jittered.first
                            "getLongitude" -> param.result = jittered.second
                        }
                    }
                }
            }
            val amapLocationClass = findClassIfExists("com.amap.api.location.AMapLocation", classLoader)
            if (amapLocationClass != null) {
                XposedHelpers.findAndHookMethod(
                    amapLocationClass,
                    "getLatitude",
                    amapHook
                )
                XposedHelpers.findAndHookMethod(
                    amapLocationClass,
                    "getLongitude",
                    amapHook
                )
            }

            // ★★★ 高德SDK深度反检测（strategy:500 的来源）
            // mockData JSON 就是 AMapLocation.getMockData() 的返回值，直接抹零
            val amapNullHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = null
                    }
                }
            }
            val amapFalseHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = false
                    }
                }
            }
            val amapZeroHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        param.result = 0
                    }
                }
            }

            if (amapLocationClass != null) {
                // 1. getMockData() → null（直接砍掉 mockData 字段的数据来源）
                XposedHelpers.findAndHookMethod(
                    amapLocationClass,
                    "getMockData",
                    amapNullHook
                )
                // 2. getMockFlag() / getMockType() → 0
                try {
                    XposedHelpers.findAndHookMethod(
                        amapLocationClass,
                        "getMockFlag",
                        amapZeroHook
                    )
                } catch (e: Throwable) {
                }
                try {
                    XposedHelpers.findAndHookMethod(
                        amapLocationClass,
                        "getMockType",
                        amapZeroHook
                    )
                } catch (e: Throwable) {
                }
                // 3. isMocked() → false（AMap SDK 12.0+ 新接口）
                try {
                    XposedHelpers.findAndHookMethod(
                        amapLocationClass,
                        "isMocked",
                        amapFalseHook
                    )
                } catch (e: Throwable) {
                }
                // 4. getErrorCode() → 0（非0表示定位失败）
                XposedHelpers.findAndHookMethod(
                    amapLocationClass,
                    "getErrorCode",
                    amapZeroHook
                )
                // 5. getLocationType() → 1（GPS类型，最可信）
                XposedHelpers.findAndHookMethod(
                    amapLocationClass, "getLocationType",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) param.result =
                                1 // 1 = GPS定位
                        }
                    })
                // 6. getProvider() → "gps"
                XposedHelpers.findAndHookMethod(
                    amapLocationClass, "getProvider",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) param.result =
                                "gps"
                        }
                    })
                // 7. 直接写底层 mock 相关字段（防反射读字段绕过 getter）
                val setFieldHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val obj = param.thisObject ?: return
                            try {
                                XposedHelpers.setObjectField(obj, "mockData", null)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setIntField(obj, "mockFlag", 0)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setIntField(obj, "mockType", 0)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setBooleanField(obj, "isMocked", false)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setBooleanField(obj, "mMock", false)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setIntField(obj, "errorCode", 0)
                            } catch (e: Throwable) {
                            }
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(
                    amapLocationClass,
                    "getLatitude",
                    setFieldHook
                )
            }

            // 8. AMapLocationQualityReport 质量报告也要清零
            val qualityClass = findClassIfExists(
                "com.amap.api.location.AMapLocationQualityReport",
                classLoader
            )
            if (qualityClass != null) {
                try {
                    XposedHelpers.findAndHookMethod(
                        qualityClass,
                        "getMockInfo",
                        amapNullHook
                    )
                } catch (e: Throwable) {
                }
                try {
                    XposedHelpers.findAndHookMethod(
                        qualityClass,
                        "isMockLocation",
                        amapFalseHook
                    )
                } catch (e: Throwable) {
                }
            }

            // 9. setMockEnable(false) 让高德SDK禁用自身的 mock 校验流程
            val amapClientClass = findClassIfExists(
                "com.amap.api.location.AMapLocationClient",
                classLoader
            )
            if (amapClientClass != null) {
                try {
                    XposedHelpers.findAndHookMethod(
                        amapClientClass, "setMockEnable",
                        Boolean::class.javaPrimitiveType!!,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val config = readConfig()
                                if (config != null && config.optBoolean("active", false)) {
                                    // 强制设为 true，让高德自己相信当前位置是真实的
                                    param.args[0] = true
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                }
            }

        } catch (e: Throwable) {
        }
    }

    private fun hookLocationManagerOverrides(classLoader: ClassLoader, useGcj: Boolean) {
        val lastKnownHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return
                val provider = param.args.firstOrNull() as? String ?: android.location.LocationManager.GPS_PROVIDER
                param.result = buildFakeLocation(config, provider, useGcj)
            }
        }

        val lastLocationHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return
                param.result = buildFakeLocation(config, android.location.LocationManager.GPS_PROVIDER, useGcj)
            }
        }

        val currentLocationHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false)) return

                val provider = (param.args.firstOrNull() as? String)
                    ?: android.location.LocationManager.GPS_PROVIDER
                val location = buildFakeLocation(config, provider, useGcj)

                val executor = param.args.getOrNull(2) as? java.util.concurrent.Executor
                @Suppress("UNCHECKED_CAST")
                val consumer = param.args.getOrNull(3) as? java.util.function.Consumer<Location>
                if (executor != null && consumer != null) {
                    executor.execute { consumer.accept(location) }
                } else {
                    consumer?.accept(location)
                }
                param.result = null
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "getLastKnownLocation",
                String::class.java,
                lastKnownHook
            )
        } catch (e: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "getLastLocation",
                lastLocationHook
            )
        } catch (e: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                classLoader,
                "getCurrentLocation",
                String::class.java,
                android.os.CancellationSignal::class.java,
                java.util.concurrent.Executor::class.java,
                java.util.function.Consumer::class.java,
                currentLocationHook
            )
        } catch (e: Throwable) {
        }

        val locationRequestClass = findClassIfExists("android.location.LocationRequest", classLoader)
        if (locationRequestClass != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager",
                    classLoader,
                    "getCurrentLocation",
                    locationRequestClass,
                    android.os.CancellationSignal::class.java,
                    java.util.concurrent.Executor::class.java,
                    java.util.function.Consumer::class.java,
                    currentLocationHook
                )
            } catch (e: Throwable) {
            }
        }

        // 拦截底层的 LocationListener 回调接收器
        // 涵盖 Android 11- 的 ListenerTransport 和 Android 12+ 的 LocationListenerTransport
        val onLocationChangedHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val args = param.args
                    if (args.isNotEmpty()) {
                        val firstArg = args[0]
                        if (firstArg is android.location.Location) {
                            // 单个 Location
                            param.args[0] = buildFakeLocation(config, android.location.LocationManager.GPS_PROVIDER, useGcj)
                        } else if (firstArg is java.util.List<*>) {
                            // Android 12+ 引入的批量回调 List<Location>
                            val fakeList = java.util.ArrayList<android.location.Location>()
                            fakeList.add(buildFakeLocation(config, android.location.LocationManager.GPS_PROVIDER, useGcj))
                            param.args[0] = fakeList
                        }
                    }
                }
            }
        }

        try {
            val listenerTransportClass = XposedHelpers.findClassIfExists(
                "android.location.LocationManager\$ListenerTransport", classLoader
            )
            if (listenerTransportClass != null) {
                XposedHelpers.findAndHookMethod(
                    listenerTransportClass,
                    "onLocationChanged",
                    android.location.Location::class.java,
                    onLocationChangedHook
                )
            }
        } catch (e: Throwable) {
        }

        try {
            val locationListenerTransportClass = XposedHelpers.findClassIfExists(
                "android.location.LocationManager\$LocationListenerTransport", classLoader
            )
            if (locationListenerTransportClass != null) {
                XposedHelpers.findAndHookMethod(
                    locationListenerTransportClass,
                    "onLocationChanged",
                    android.location.Location::class.java,
                    onLocationChangedHook
                )
                try {
                    XposedHelpers.findAndHookMethod(
                        locationListenerTransportClass,
                        "onLocationChanged",
                        java.util.List::class.java,
                        onLocationChangedHook
                    )
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {
        }
    }

    private fun buildFakeLocation(config: JSONObject, provider: String, useGcj: Boolean): Location {
        val (baseLat, baseLng) = resolveBaseLatLng(
            config,
            fallbackLat = 0.0,
            fallbackLng = 0.0,
            useGcj = useGcj
        )
        val (lat, lng) = getJitteredLocation(baseLat, baseLng)
        val accuracy = getJitteredAccuracy()
        val bearing = config.optDouble("sim_bearing", 0.0).toFloat()

        return Location(provider).apply {
            latitude = lat
            longitude = lng
            this.accuracy = accuracy
            this.bearing = bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    private fun hookNetworkAndCellAPIs(classLoader: ClassLoader) {
        // 1. 伪造 WifiInfo Getter（getBSSID / getSSID / getMacAddress）
        val wifiInfoHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val wifiArray = config.optJSONArray("wifi_json")
                    val firstWifi =
                        if (wifiArray != null && wifiArray.length() > 0) wifiArray.getJSONObject(0) else null
                    when (param.method.name) {
                        "getBSSID", "getMacAddress" -> param.result =
                            firstWifi?.optString("bssid") ?: "ac:22:0b:f4:11:33"

                        "getSSID" -> param.result =
                            "\"${firstWifi?.optString("ssid") ?: "HOME_WIFI"}\""

                        "getNetworkId" -> param.result = 1
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getBSSID",
                wifiInfoHook
            )
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getMacAddress",
                wifiInfoHook
            )
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getSSID",
                wifiInfoHook
            )
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getNetworkId",
                wifiInfoHook
            )
        } catch (e: Throwable) {
        }

        // 2. 伪造 Wi-Fi 扫描列表（getScanResults）
        val wifiScanHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val fakeList = java.util.ArrayList<Any>()
                    val wifiArray = config.optJSONArray("wifi_json")
                    if (wifiArray != null && wifiArray.length() > 0) {
                        try {
                            val scanResultClass =
                                XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                            for (i in 0 until wifiArray.length()) {
                                val wifi = wifiArray.getJSONObject(i)
                                val fakeScanResult = XposedHelpers.newInstance(scanResultClass)
                                XposedHelpers.setObjectField(
                                    fakeScanResult,
                                    "SSID",
                                    wifi.optString("ssid")
                                )
                                XposedHelpers.setObjectField(
                                    fakeScanResult,
                                    "BSSID",
                                    wifi.optString("bssid")
                                )
                                XposedHelpers.setIntField(
                                    fakeScanResult,
                                    "level",
                                    (-80..-40).random()
                                )
                                XposedHelpers.setIntField(
                                    fakeScanResult,
                                    "frequency",
                                    listOf(2412, 2437, 2462, 5180, 5240).random()
                                )
                                XposedHelpers.setObjectField(
                                    fakeScanResult,
                                    "capabilities",
                                    "[WPA2-PSK-CCMP][ESS]"
                                )
                                fakeList.add(fakeScanResult)
                            }
                        } catch (e: Throwable) { // 忽略
                        }
                    }
                    param.result = fakeList
                }
            }
        }

        // 3. 完整的 WifiManager Hook 组合
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager",
                classLoader,
                "getScanResults",
                wifiScanHook
            )

            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getWifiState",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result =
                            3 // WIFI_STATE_ENABLED
                    }
                })

            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "isWifiEnabled",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) param.result =
                            true
                    }
                })

            // 4. 拦截 getConnectionInfo：返回伪造的 WifiInfo 对象（包含当地真实 BSSID）
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiManager", classLoader, "getConnectionInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val wifiArray = config.optJSONArray("wifi_json")
                            if (wifiArray != null && wifiArray.length() > 0) {
                                val firstWifi = wifiArray.getJSONObject(0)
                                try {
                                    val wifiInfoClass = XposedHelpers.findClass(
                                        "android.net.wifi.WifiInfo",
                                        classLoader
                                    )
                                    val fakeWifiInfo = XposedHelpers.newInstance(wifiInfoClass)
                                    try {
                                        XposedHelpers.setObjectField(
                                            fakeWifiInfo,
                                            "mBSSID",
                                            firstWifi.optString("bssid")
                                        )
                                    } catch (e: Throwable) {
                                    }
                                    try {
                                        XposedHelpers.setObjectField(
                                            fakeWifiInfo,
                                            "mMacAddress",
                                            firstWifi.optString("bssid")
                                        )
                                    } catch (e: Throwable) {
                                    }
                                    try {
                                        val wifiSsidClass = XposedHelpers.findClass(
                                            "android.net.wifi.WifiSsid",
                                            classLoader
                                        )
                                        val createMethod = XposedHelpers.findMethodExact(
                                            wifiSsidClass,
                                            "createFromAsciiEncoded",
                                            String::class.java
                                        )
                                        val wifiSsid =
                                            createMethod.invoke(null, firstWifi.optString("ssid"))
                                        XposedHelpers.setObjectField(
                                            fakeWifiInfo,
                                            "mWifiSsid",
                                            wifiSsid
                                        )
                                    } catch (e: Throwable) {
                                        try {
                                            XposedHelpers.setObjectField(
                                                fakeWifiInfo,
                                                "mSSID",
                                                "\"${firstWifi.optString("ssid")}\""
                                            )
                                        } catch (e2: Throwable) {
                                        }
                                    }
                                    try {
                                        XposedHelpers.setIntField(fakeWifiInfo, "mNetworkId", 1)
                                    } catch (e: Throwable) {
                                    }
                                    param.result = fakeWifiInfo
                                } catch (e: Throwable) { // 忽略
                                }
                            }
                        }
                    }
                })
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 5. 基站信息伪造（CellLocation / AllCellInfo / NeighboringCellInfo）
        val telephonyTypeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config == null || !config.optBoolean("active", false)) return
                val snapshot = readTelephonySnapshot()
                param.result = snapshot.networkType
            }
        }

        val operatorHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config == null || !config.optBoolean("active", false)) return
                val snapshot = readTelephonySnapshot()
                param.result = formatOperator(snapshot.mcc, snapshot.mnc)
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getNetworkType",
                telephonyTypeHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getDataNetworkType",
                telephonyTypeHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getVoiceNetworkType",
                telephonyTypeHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getNetworkOperator",
                operatorHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getSimOperator",
                operatorHook
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        val cellHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val config = readConfig()
                if (config == null || !config.optBoolean("active", false)) return

                val lat = config.optDouble("lat", 0.0)
                val lng = config.optDouble("lng", 0.0)
                val ids = generateCellIds(lat, lng)
                val snapshot = readTelephonySnapshot()

                when (param.method.name) {
                    "getCellLocation" -> {
                        param.result = buildFakeGsmCellLocation(classLoader, ids.lac, ids.cid)
                    }

                    "getAllCellInfo" -> {
                        param.result =
                            buildFakeCellInfoList(classLoader, snapshot.mcc, snapshot.mnc, ids, snapshot.networkType)
                    }

                    "getNeighboringCellInfo" -> param.result = ArrayList<Any>()
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getAllCellInfo",
                cellHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getCellLocation",
                cellHook
            )
            XposedHelpers.findAndHookMethod(
                "android.telephony.TelephonyManager",
                classLoader,
                "getNeighboringCellInfo",
                cellHook
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 6. 拦截主动异步请求基站 (Android 10+ requestCellInfoUpdate)
        try {
            val cellInfoCallbackClass = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyManager\$CellInfoCallback", 
                classLoader
            )
            if (cellInfoCallbackClass != null) {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    classLoader,
                    "requestCellInfoUpdate",
                    java.util.concurrent.Executor::class.java,
                    cellInfoCallbackClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                val executor = param.args[0] as? java.util.concurrent.Executor
                                val callback = param.args[1]
                                if (executor != null && callback != null) {
                                    executor.execute {
                                        try {
                                            XposedHelpers.callMethod(callback, "onCellInfo", java.util.ArrayList<Any>())
                                        } catch (e: Throwable) {
                                            XposedBridge.log(e)
                                        }
                                    }
                                }
                                param.result = null // 拦截原方法调用
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 7. 拦截被动基站监听 (Android 11- PhoneStateListener)
        try {
            val phoneStateListenerClass = XposedHelpers.findClassIfExists(
                "android.telephony.PhoneStateListener", 
                classLoader
            )
            if (phoneStateListenerClass != null) {
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    classLoader,
                    "listen",
                    phoneStateListenerClass,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config != null && config.optBoolean("active", false)) {
                                var events = param.args[1] as Int
                                // PhoneStateListener.LISTEN_CELL_LOCATION = 16
                                // PhoneStateListener.LISTEN_CELL_INFO = 1024
                                events = events and 16.inv()
                                events = events and 1024.inv()
                                param.args[1] = events
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 8. 拦截被动基站监听 (Android 12+ TelephonyCallback)
        try {
            val telephonyCallbackClass = XposedHelpers.findClassIfExists(
                "android.telephony.TelephonyCallback",
                classLoader
            )
            if (telephonyCallbackClass != null) {
                val hookedTelephonyCallbackClasses = mutableSetOf<Class<*>>()
                
                XposedHelpers.findAndHookMethod(
                    "android.telephony.TelephonyManager",
                    classLoader,
                    "registerTelephonyCallback",
                    java.util.concurrent.Executor::class.java,
                    telephonyCallbackClass,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val config = readConfig()
                            if (config == null || !config.optBoolean("active", false)) return

                            val callbackObj = param.args[1] ?: return
                            val callbackClass = callbackObj.javaClass

                            synchronized(hookedTelephonyCallbackClasses) {
                                if (hookedTelephonyCallbackClasses.contains(callbackClass)) return
                                hookedTelephonyCallbackClasses.add(callbackClass)
                                
                                try {
                                    XposedHelpers.findAndHookMethod(
                                        callbackClass,
                                        "onCellInfoChanged",
                                        java.util.List::class.java,
                                        object : XC_MethodHook() {
                                            override fun beforeHookedMethod(innerParam: MethodHookParam) {
                                                if (readConfig()?.optBoolean("active", false) == true) {
                                                    innerParam.args[0] = java.util.ArrayList<Any>()
                                                }
                                            }
                                        }
                                    )
                                } catch (e: Throwable) {
                                    // Ignore if method not implemented
                                }

                                try {
                                    val cellLocationClass = XposedHelpers.findClassIfExists("android.telephony.CellLocation", classLoader)
                                    if (cellLocationClass != null) {
                                        XposedHelpers.findAndHookMethod(
                                            callbackClass,
                                            "onCellLocationChanged",
                                            cellLocationClass,
                                            object : XC_MethodHook() {
                                                override fun beforeHookedMethod(innerParam: MethodHookParam) {
                                                    val cfg = readConfig()
                                                    if (cfg?.optBoolean("active", false) == true) {
                                                        val lat = cfg.optDouble("lat", 0.0)
                                                        val lng = cfg.optDouble("lng", 0.0)
                                                        val ids = generateCellIds(lat, lng)
                                                        innerParam.args[0] = buildFakeGsmCellLocation(classLoader, ids.lac, ids.cid)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                } catch (e: Throwable) {
                                    // Ignore if method not implemented
                                }
                            }
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

    /**
     * 拦截蓝牙 BLE 扫描结果，防止通过附近 BLE 信标定位。
     * 当模拟激活时，返回空列表，屏蔽所有 iBeacon / Eddystone 信标探测。
     */
    private fun hookBluetoothLE(classLoader: ClassLoader) {
        try {
            // Android 5.0+ BLE Scanner
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.le.BluetoothLeScanner",
                classLoader,
                "startScan",
                java.util.List::class.java,
                android.bluetooth.le.ScanSettings::class.java,
                android.bluetooth.le.ScanCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            // 替换 callback 为无操作版本，阻止真实扫描结果传递
                            param.result = null // startScan 返回 void，直接短路执行
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }

        // 同时Hook老接口（Android 4.x BluetoothAdapter.startLeScan）
        try {
            XposedHelpers.findAndHookMethod(
                "android.bluetooth.BluetoothAdapter",
                classLoader,
                "startLeScan",
                android.bluetooth.BluetoothAdapter.LeScanCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            param.result = false // 假装开启失败，不返回任何扫描结果
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

    private data class FakeCellIds(
        val lac: Int,
        val cid: Int,
        val tac: Int,
        val pci: Int,
        val earfcn: Int,
        val nci: Long
    )

    private fun generateCellIds(lat: Double, lng: Double): FakeCellIds {
        val seed =
            java.lang.Double.doubleToLongBits(lat) xor (java.lang.Double.doubleToLongBits(lng) * 31L)
        val lac = ((seed ushr 8) and 0xFFFF).toInt().coerceIn(1, 65535)
        val cid = ((seed ushr 24) and 0xFFFF).toInt().coerceIn(1, 65535)
        val tac = ((seed ushr 16) and 0xFFFF).toInt().coerceIn(1, 65535)
        val pci = ((seed ushr 4) and 0x1FF).toInt().coerceIn(0, 503)
        val earfcn = ((seed ushr 12) and 0x3FFF).toInt().coerceIn(0, 65535)
        val nci = ((seed ushr 20) and 0x0FFFFFFF).toLong().coerceAtLeast(1)
        return FakeCellIds(lac, cid, tac, pci, earfcn, nci)
    }

    private fun buildFakeGsmCellLocation(classLoader: ClassLoader, lac: Int, cid: Int): Any? {
        return try {
            val gsmCellLocationClass = XposedHelpers.findClass(
                "android.telephony.gsm.GsmCellLocation",
                classLoader
            )
            val fakeLocation = XposedHelpers.newInstance(gsmCellLocationClass)
            XposedHelpers.callMethod(fakeLocation, "setLacAndCid", lac, cid)
            fakeLocation
        } catch (e: Throwable) {
            null
        }
    }

    private fun buildFakeCellInfoList(
        classLoader: ClassLoader,
        mcc: Int,
        mnc: Int,
        ids: FakeCellIds,
        networkType: Int
    ): java.util.ArrayList<Any> {
        val list = java.util.ArrayList<Any>()
        val cellInfo = if (isLteLike(networkType)) {
            buildCellInfoLte(classLoader, mcc, mnc, ids)
        } else {
            buildCellInfoGsm(classLoader, mcc, mnc, ids)
        }
        if (cellInfo != null) list.add(cellInfo)
        return list
    }

    private val lteCaNetworkType: Int by lazy {
        try {
            TelephonyManager::class.java.getField("NETWORK_TYPE_LTE_CA").getInt(null)
        } catch (e: Throwable) {
            -1
        }
    }

    private fun isLteLike(networkType: Int): Boolean {
        return networkType == TelephonyManager.NETWORK_TYPE_LTE ||
                networkType == TelephonyManager.NETWORK_TYPE_NR ||
                (lteCaNetworkType != -1 && networkType == lteCaNetworkType)
    }

    private fun buildCellInfoGsm(
        classLoader: ClassLoader,
        mcc: Int,
        mnc: Int,
        ids: FakeCellIds
    ): Any? {
        return try {
            val cellInfoClass =
                XposedHelpers.findClass("android.telephony.CellInfoGsm", classLoader)
            val cellIdentityClass =
                XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
            val cellSignalClass =
                XposedHelpers.findClass("android.telephony.CellSignalStrengthGsm", classLoader)

            val cellInfo = XposedHelpers.newInstance(cellInfoClass)
            val cellIdentity = XposedHelpers.newInstance(cellIdentityClass)
            val cellSignal = XposedHelpers.newInstance(cellSignalClass)

            setIntFieldSafe(cellIdentity, "mLac", ids.lac)
            setIntFieldSafe(cellIdentity, "mCid", ids.cid)
            setIntFieldSafe(cellIdentity, "mMcc", mcc)
            setIntFieldSafe(cellIdentity, "mMnc", mnc)
            setStringFieldSafe(cellIdentity, "mMccStr", mcc.toString().padStart(3, '0'))
            setStringFieldSafe(cellIdentity, "mMncStr", mnc.toString().padStart(2, '0'))

            setIntFieldSafe(cellSignal, "mSignalStrength", 20)
            setIntFieldSafe(cellSignal, "mBitErrorRate", 0)

            setObjectFieldSafe(cellInfo, "mCellIdentity", cellIdentity)
            setObjectFieldSafe(cellInfo, "mCellSignalStrength", cellSignal)
            setBooleanFieldSafe(cellInfo, "mRegistered", true)

            cellInfo
        } catch (e: Throwable) {
            null
        }
    }

    private fun buildCellInfoLte(
        classLoader: ClassLoader,
        mcc: Int,
        mnc: Int,
        ids: FakeCellIds
    ): Any? {
        return try {
            val cellInfoClass =
                XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader)
            val cellIdentityClass =
                XposedHelpers.findClass("android.telephony.CellIdentityLte", classLoader)
            val cellSignalClass =
                XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)

            val cellInfo = XposedHelpers.newInstance(cellInfoClass)
            val cellIdentity = XposedHelpers.newInstance(cellIdentityClass)
            val cellSignal = XposedHelpers.newInstance(cellSignalClass)

            setIntFieldSafe(cellIdentity, "mCi", ids.cid)
            setIntFieldSafe(cellIdentity, "mPci", ids.pci)
            setIntFieldSafe(cellIdentity, "mTac", ids.tac)
            setIntFieldSafe(cellIdentity, "mEarfcn", ids.earfcn)
            setIntFieldSafe(cellIdentity, "mMcc", mcc)
            setIntFieldSafe(cellIdentity, "mMnc", mnc)
            setStringFieldSafe(cellIdentity, "mMccStr", mcc.toString().padStart(3, '0'))
            setStringFieldSafe(cellIdentity, "mMncStr", mnc.toString().padStart(2, '0'))

            setIntFieldSafe(cellSignal, "mSignalStrength", 30)
            setIntFieldSafe(cellSignal, "mRsrp", -95)
            setIntFieldSafe(cellSignal, "mRsrq", -10)
            setIntFieldSafe(cellSignal, "mRssnr", 30)

            setObjectFieldSafe(cellInfo, "mCellIdentity", cellIdentity)
            setObjectFieldSafe(cellInfo, "mCellSignalStrength", cellSignal)
            setBooleanFieldSafe(cellInfo, "mRegistered", true)

            cellInfo
        } catch (e: Throwable) {
            null
        }
    }

    private fun setIntFieldSafe(target: Any, field: String, value: Int) {
        try {
            XposedHelpers.setIntField(target, field, value)
        } catch (e: Throwable) {
        }
    }

    private fun setStringFieldSafe(target: Any, field: String, value: String) {
        try {
            XposedHelpers.setObjectField(target, field, value)
        } catch (e: Throwable) {
        }
    }

    private fun setBooleanFieldSafe(target: Any, field: String, value: Boolean) {
        try {
            XposedHelpers.setBooleanField(target, field, value)
        } catch (e: Throwable) {
        }
    }

    private fun setObjectFieldSafe(target: Any, field: String, value: Any?) {
        try {
            XposedHelpers.setObjectField(target, field, value)
        } catch (e: Throwable) {
        }
    }

    private data class TelephonySnapshot(
        val networkType: Int,
        val mcc: Int,
        val mnc: Int
    )

    private var lastTelephonySnapshot: TelephonySnapshot? = null
    private var lastTelephonyReadTime: Long = 0

    private fun readTelephonySnapshot(): TelephonySnapshot {
        val now = System.currentTimeMillis()
        val cached = lastTelephonySnapshot
        if (cached != null && now - lastTelephonyReadTime < 2000) return cached

        var snapshot = TelephonySnapshot(
            TelephonyManager.NETWORK_TYPE_LTE,
            460,
            0
        )
        val app = android.app.AndroidAppHelper.currentApplication()
        if (app != null) {
            try {
                val uri = android.net.Uri.parse(
                    "content://com.suseoaa.locationspoofer.provider/telephony"
                )
                val cursor = app.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val networkTypeIdx = it.getColumnIndex("network_type")
                        val mccIdx = it.getColumnIndex("mcc")
                        val mncIdx = it.getColumnIndex("mnc")

                        val rawNetworkType = if (networkTypeIdx != -1) it.getInt(networkTypeIdx) else snapshot.networkType
                        val mcc = if (mccIdx != -1) it.getInt(mccIdx) else snapshot.mcc
                        val mnc = if (mncIdx != -1) it.getInt(mncIdx) else snapshot.mnc

                        val normalizedNetworkType =
                            if (rawNetworkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) snapshot.networkType else rawNetworkType
                        snapshot = TelephonySnapshot(normalizedNetworkType, mcc, mnc)
                    }
                }
            } catch (e: Throwable) {
            }
        }

        lastTelephonySnapshot = snapshot
        lastTelephonyReadTime = now
        return snapshot
    }

    private fun formatOperator(mcc: Int, mnc: Int): String {
        return mcc.toString().padStart(3, '0') + mnc.toString().padStart(2, '0')
    }

    private var lastConfig: JSONObject? = null
    private var lastReadTime: Long = 0

    private fun readConfig(): JSONObject? {
        val app = android.app.AndroidAppHelper.currentApplication()
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastReadTime < 800 && lastConfig != null) {
            return lastConfig
        }

        if (app != null) {
            try {
                val uri =
                    android.net.Uri.parse("content://com.suseoaa.locationspoofer.provider/config")
                val cursor = app.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val active = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1
                    val lat = cursor.getDouble(cursor.getColumnIndexOrThrow("lat"))
                    val lng = cursor.getDouble(cursor.getColumnIndexOrThrow("lng"))
                    val wifiJson = cursor.getString(cursor.getColumnIndexOrThrow("wifi_json"))

                    val simModeIdx = cursor.getColumnIndex("sim_mode")
                    val simMode = if (simModeIdx != -1) cursor.getString(simModeIdx) else "STILL"

                    val simBearingIdx = cursor.getColumnIndex("sim_bearing")
                    val simBearing = if (simBearingIdx != -1) cursor.getFloat(simBearingIdx) else 0f

                    val startTimestampIdx = cursor.getColumnIndex("start_timestamp")
                    val startTimestamp =
                        if (startTimestampIdx != -1) cursor.getLong(startTimestampIdx) else System.currentTimeMillis()

                    cursor.close()

                    val config = JSONObject()
                    config.put("active", active)
                    config.put("lat", lat)
                    config.put("lng", lng)
                    config.put("wifi_json", org.json.JSONArray(wifiJson))
                    config.put("sim_mode", simMode)
                    config.put("sim_bearing", simBearing.toDouble())
                    config.put("start_timestamp", startTimestamp)

                    lastConfig = config
                    lastReadTime = currentTime
                    return config
                }
            } catch (e: Throwable) { // 忽略查询错误
            }
        }

        // 回退到本地文件
        return try {
            val file = File("/data/local/tmp/locationspoofer_config.json")
            if (file.exists() && file.canRead()) {
                val content = file.readText()
                val config = JSONObject(content)
                if (!config.has("wifi_json")) config.put("wifi_json", org.json.JSONArray())
                lastConfig = config
                lastReadTime = currentTime
                config
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
