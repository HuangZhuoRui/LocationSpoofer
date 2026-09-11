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
import io.github.libxposed.api.*

/**
 * 反检测模块 (Anti-Detection Hooker)
 *
 * 上下文:
 * 部分应用 (如企业微信、钉钉、打卡应用) 会尝试通过系统 API 检测是否存在 "模拟位置" (Mock Location)。
 *
 * 作用:
 * 本模块在系统 API 层级拦截对模拟位置开关与 AppOps 权限的探测。
 *
 * 设计原则:
 * 1. 绝不 Hook java.lang.ClassLoader.loadClass / java.lang.Class.forName / Throwable.getStackTrace，
 *    避免在 ART (Android 14/15/16) 运行时引发类加载器锁死锁、ANR 卡死和崩溃。
 * 2. 仅拦截系统公开的模拟位置相关服务 (AppOpsManager 与 Settings.Secure)。
 */
internal fun LocationHooker.hookAntiDetection(classLoader: ClassLoader) {

    // 1. 拦截 AppOpsManager 的权限自检与 OP_MOCK_LOCATION (58)
    try {
        val appOpsClass = XposedHelpers.findClassIfExists("android.app.AppOpsManager", classLoader)
        if (appOpsClass != null) {
            val locationOpSet = setOf(
                0, 1, 2, 12, 41, 42, 59, 77, // OP_COARSE_LOCATION, OP_FINE_LOCATION, OP_GPS, OP_NEIGHBORING_CELLS, OP_MONITOR_LOCATION, OP_MONITOR_HIGH_POWER_LOCATION, OP_BLUETOOTH_SCAN etc.
                "android:coarse_location", "android:fine_location", "android:gps",
                "android:monitor_location", "android:monitor_location_high_power",
                "android:bluetooth_scan", "android:bluetooth_connect", "android:bluetooth_advertise"
            )

            val opMethods = arrayOf("checkOp", "checkOpNoThrow", "noteOp", "noteOpNoThrow", "noteProxyOp", "noteProxyOpNoThrow")
            for (mName in opMethods) {
                try {
                    XposedHelpers.hookAllMethods(appOpsClass, mName) { chain, _ ->
                        val config = readConfig()
                        if (config == null || !config.optBoolean("active", false)) {
                            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                        }
                        val opArg = chain.args.getOrNull(0)
                        val isMockOp = when (opArg) {
                            58 -> true
                            "android:mock_location", "mock_location" -> true
                            else -> false
                        }
                        if (isMockOp) {
                            // 严正返回 MODE_IGNORED (1)，向应用与安全SDK隐藏模拟位置开关
                            return@hookAllMethods 1
                        }

                        // 定位与蓝牙相关操作自检，返回 MODE_ALLOWED (0)
                        if (opArg != null && locationOpSet.contains(opArg)) {
                            return@hookAllMethods 0
                        }

                        // 其余操作（如相机 OP_CAMERA、录音等）正常放行，避免破坏人脸识别活体检测底层权限
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }

    // 2. 拦截 Settings.Secure 的 mock_location 开关查询
    try {
        try {
            XposedHelpers.hookMethod(
                "android.provider.Settings\$Secure", classLoader, "getInt",
                android.content.ContentResolver::class.java,
                String::class.java
            ) { chain, method ->
                val config = readConfig()
                if (config == null || !config.optBoolean("active", false)) {
                    return@hookMethod chain.proceed(chain.args.toTypedArray())
                }
                val name = chain.args.getOrNull(1) as? String
                if (name == "mock_location") {
                    return@hookMethod 0
                }
                return@hookMethod chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookMethod(
                "android.provider.Settings\$Secure", classLoader, "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!
            ) { chain, method ->
                val config = readConfig()
                if (config == null || !config.optBoolean("active", false)) {
                    return@hookMethod chain.proceed(chain.args.toTypedArray())
                }
                val name = chain.args.getOrNull(1) as? String
                if (name == "mock_location") {
                    return@hookMethod 0
                }
                return@hookMethod chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookMethod(
                "android.provider.Settings\$Secure", classLoader, "getString",
                android.content.ContentResolver::class.java,
                String::class.java
            ) { chain, method ->
                val config = readConfig()
                if (config == null || !config.optBoolean("active", false)) {
                    return@hookMethod chain.proceed(chain.args.toTypedArray())
                }
                val name = chain.args.getOrNull(1) as? String
                if (name == "mock_location") {
                    return@hookMethod "0"
                }
                return@hookMethod chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }

    // 3. 拦截权限自检 Context / ContextWrapper / ContextImpl
    try {
        // 定位权限：目标 App 本来就必须持有才有模拟的意义，谎报的收益大、风险低。
        val locationPermissions = setOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        )

        // 蓝牙权限只在真正开启蓝牙模拟时才谎报，且**绝不包含 BLUETOOTH_CONNECT**。
        //
        // 原因：权限校验有客户端和服务端两道。我们只能骗客户端的 checkSelfPermission，
        // 骗不了 Binder 对端系统进程里的那道（如 AdapterService 的 checkPermissionForDataDelivery）。
        // 一旦谎报 BLUETOOTH_CONNECT，App 自检通过后会真的去调
        // BluetoothAdapter.getProfileConnectionState() 这类我们并未拦截的 API，
        // 请求打到蓝牙进程被如实拒绝，抛出 SecurityException 直接把 App 搞崩
        // —— 高德地图闪退就是这么来的（用户当时甚至没开蓝牙模拟）。
        // BLUETOOTH_SCAN 保留：它守的是 startScan/startDiscovery，而这些我们在
        // BluetoothHooker 里是完整拦截、不放行到服务端的，所以谎报它才有意义且安全。
        val bluetoothPermissions = setOf(
            "android.permission.BLUETOOTH",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE"
        )
        val contextClasses = listOfNotNull(
            XposedHelpers.findClassIfExists("android.content.Context", classLoader),
            XposedHelpers.findClassIfExists("android.content.ContextWrapper", classLoader),
            XposedHelpers.findClassIfExists("android.app.ContextImpl", classLoader)
        )
        val permMethods = arrayOf("checkPermission", "checkSelfPermission", "checkCallingOrSelfPermission", "checkCallingPermission")
        for (ctxClazz in contextClasses) {
            for (mName in permMethods) {
                try {
                    XposedHelpers.hookAllMethods(ctxClazz, mName) { chain, _ ->
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            val perm = chain.args.firstOrNull { it is String } as? String
                            val shouldFake = perm != null && (
                                    locationPermissions.contains(perm) ||
                                            (bluetoothPermissions.contains(perm) &&
                                                    config.optBoolean("mock_bluetooth", false))
                                    )
                            if (shouldFake) {
                                return@hookAllMethods 0 // PackageManager.PERMISSION_GRANTED
                            }
                        }
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                } catch (_: Throwable) {}
            }
        }
    } catch (_: Throwable) {}

    // 4. 拦截 PackageManager.hasSystemFeature (确保蓝牙LE与定位硬件特性恒支持)
    try {
        val appPmClass = XposedHelpers.findClassIfExists("android.app.ApplicationPackageManager", classLoader)
        val pmClass = XposedHelpers.findClassIfExists("android.content.pm.PackageManager", classLoader)
        for (clazz in listOfNotNull(appPmClass, pmClass)) {
            try {
                XposedHelpers.hookAllMethods(clazz, "hasSystemFeature") { chain, _ ->
                    val feat = chain.args.firstOrNull { it is String } as? String
                    val isBtFeature = feat == "android.hardware.bluetooth_le" ||
                            feat == "android.hardware.bluetooth"
                    val isLocationFeature = feat == "android.hardware.location.gps" ||
                            feat == "android.hardware.location.network"
                    if (isBtFeature || isLocationFeature) {
                        val config = readConfig()
                        if (config != null && config.optBoolean("active", false)) {
                            // 蓝牙硬件特性同样只在开启蓝牙模拟时才谎报：
                            // 没开模拟却声称设备有蓝牙，只会把 App 推上它本来不会走的蓝牙分支，
                            // 后续那些我们没拦截的蓝牙 API 该失败还是失败（见 BLUETOOTH_CONNECT 的教训）。
                            if (isLocationFeature || config.optBoolean("mock_bluetooth", false)) {
                                return@hookAllMethods true
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
    } catch (_: Throwable) {}

    XposedBridge.log("[LocationSpoofer] Anti-detection hooks installed")
}

