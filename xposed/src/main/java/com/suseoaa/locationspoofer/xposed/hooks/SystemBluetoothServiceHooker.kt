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
import com.suseoaa.locationspoofer.xposed.hooks.network.*
import com.suseoaa.locationspoofer.xposed.utils.*
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Timer
import java.util.TimerTask
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private fun sysLog(msg: String) = XposedBridge.log(msg)

/**
 * com.android.bluetooth 系统级蓝牙与 BLE 信标拦截派发引擎
 *
 * 核心原理：
 * 全设备所有应用通过 BluetoothLeScanner 进行 BLE 扫描时，均会经由 IBluetoothGatt
 * 远程 IPC 路由至 com.android.bluetooth 进程中的 GattService。
 *
 * 本模块在 com.android.bluetooth 进程内直接拦截 GattService：
 * 1. 当目标应用调用 registerScanner 或 startScan 时，记录其 IScannerCallback 监听句柄；
 * 2. 绑定 IBinder.linkToDeath，客户端退出时自动移除；
 * 3. 启动系统级 BLE 周期性主动派发定时器：当配置开启 mock_bluetooth 且包含虚拟蓝牙设备（如打卡信标）时，
 *    周期性向目标应用的 IScannerCallback 派发伪造的 ScanResult，彻底解决室内无真实打卡机信号的问题；
 * 4. 拦截并阻止向目标应用派发真实物理硬件捕获的外部蓝牙信标，防止真实环境特征泄露。
 */

private data class BleScannerRegistration(
    val callbackRef: WeakReference<Any>,
    val packageName: String,
    val registeredTime: Long = System.currentTimeMillis()
)

private val activeBleScanners = ConcurrentHashMap<IBinder, BleScannerRegistration>()

@Volatile
private var isBleTimerStarted = false

@Volatile
internal var isBluetoothServiceHooked = false

internal fun LocationHooker.hookSystemBluetoothService(classLoader: ClassLoader) {
    if (isBluetoothServiceHooked) return

    var gattServiceClass = XposedHelpers.findClassIfExists(
        "com.android.bluetooth.gatt.GattService", classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.bluetooth.btservice.AdapterService", classLoader
    )

    if (gattServiceClass == null) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
            val binder = if (smClass != null) {
                XposedHelpers.callStaticMethod(smClass, "getService", "bluetooth_gatt") as? android.os.IBinder
            } else null
            if (binder != null) {
                gattServiceClass = binder.javaClass
                sysLog("[SysBle] Captured GattService from ServiceManager.getService(\"bluetooth_gatt\"): ${binder.javaClass}")
            }
        } catch (_: Throwable) {}
    }

    if (gattServiceClass == null) {
        val apexPaths = arrayOf(
            "/apex/com.android.btservices/javalib/service-bluetooth.jar",
            "/apex/com.android.bluetooth/javalib/service-bluetooth.jar"
        )
        for (path in apexPaths) {
            val apexFile = java.io.File(path)
            if (apexFile.exists()) {
                try {
                    val apexCl = dalvik.system.PathClassLoader(apexFile.absolutePath, classLoader)
                    gattServiceClass = XposedHelpers.findClassIfExists("com.android.bluetooth.gatt.GattService", apexCl)
                    if (gattServiceClass != null) {
                        sysLog("[SysBle] Loaded GattService from APEX: $path")
                        break
                    }
                } catch (t: Throwable) {
                    sysLog("[SysBle] Failed loading APEX $path: $t")
                }
            }
        }
    }

    if (gattServiceClass == null) {
        sysLog("[SysBle] GattService not found in com.android.bluetooth")
        return
    }

    if (hookedCallbackClasses.putIfAbsent(gattServiceClass, true) != null) {
        isBluetoothServiceHooked = true
        return
    }

    startSystemBleHeartbeat(classLoader)

    // =========================================================================
    // 1. registerScanner：记录目标应用的 IScannerCallback
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(gattServiceClass, "registerScanner") { chain, _ ->
            try {
                val config = readConfig()
                if (config != null) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

                    if (isTarget) {
                        val callback = chain.args.firstOrNull { arg ->
                            arg != null && (LocationHooker.hasTypeByName(
                                arg.javaClass,
                                "android.bluetooth.le.IScannerCallback"
                            ) || arg.javaClass.name.contains("IScannerCallback"))
                        }
                        if (callback != null) {
                            val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder)
                            if (binder != null) {
                                val pkgName = explicitPkg ?: "unknown"
                                activeBleScanners[binder] = BleScannerRegistration(
                                    callbackRef = WeakReference(callback),
                                    packageName = pkgName
                                )
                                try {
                                    binder.linkToDeath({
                                        activeBleScanners.remove(binder)
                                        sysLog("[SysBle] Scanner callback died and removed for $pkgName")
                                    }, 0)
                                } catch (_: Throwable) {}

                                ensureScannerCallbackHooked(callback)
                                sysLog("[SysBle] Registered target BLE scanner for ${explicitPkg ?: "caller"} (total active: ${activeBleScanners.size})"
                                )
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                sysLog("[SysBle] registerScanner pre-hook error: $e")
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        sysLog("[SysBle] GattService.registerScanner hooked")
    } catch (e: Throwable) {
        sysLog("[SysBle] hook registerScanner failed: $e")
    }

    // =========================================================================
    // 2. startScan：目标应用启动扫描时立即触发一次虚假信标派发
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(gattServiceClass, "startScan") { chain, _ ->
            try {
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                    val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

                    if (isTarget) {
                        sysLog("[SysBle] Target app started BLE scan: ${explicitPkg ?: "caller"}")
                        // 立即派发配置中的信标数据
                        dispatchFakeBeaconsToActiveScanners(config, classLoader)
                    }
                }
            } catch (e: Throwable) {
                sysLog("[SysBle] startScan pre-hook error: $e")
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        sysLog("[SysBle] GattService.startScan hooked")
    } catch (e: Throwable) {
        sysLog("[SysBle] hook startScan failed: $e")
    }

    // =========================================================================
    // 3. stopScan / unregisterScanner：注销监听
    // =========================================================================
    val unregisterMethods = arrayOf("stopScan", "unregisterScanner", "flushPendingBatchResults")
    for (mName in unregisterMethods) {
        try {
            XposedHelpers.hookAllMethods(gattServiceClass, mName) { chain, _ ->
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
    isBluetoothServiceHooked = true
}

/** 启动系统级 BLE 主动派发定时器（每 1200ms 向活跃的目标应用推送虚拟信标） */
private fun LocationHooker.startSystemBleHeartbeat(classLoader: ClassLoader) {
    if (isBleTimerStarted) return
    synchronized(activeBleScanners) {
        if (isBleTimerStarted) return
        isBleTimerStarted = true
    }

    val timer = Timer("LocationSpoofer-SysBleHeartbeat", true)
    timer.scheduleAtFixedRate(object : TimerTask() {
        override fun run() {
            try {
                if (activeBleScanners.isEmpty()) return
                val config = readConfig() ?: return
                if (!config.optBoolean("active", false) || !config.optBoolean("mock_bluetooth", true)) return

                dispatchFakeBeaconsToActiveScanners(config, classLoader)
            } catch (_: Throwable) {}
        }
    }, 1200L, 1200L)
}

/** 向当前所有处于活跃状态的目标应用 IScannerCallback 派发配置中的虚拟 BLE 信标 */
private fun LocationHooker.dispatchFakeBeaconsToActiveScanners(config: JSONObject, classLoader: ClassLoader) {
    val bluetoothArray = config.optJSONArray("bluetooth_json") ?: return
    if (bluetoothArray.length() == 0) return

    for ((binder, info) in activeBleScanners) {
        if (!binder.isBinderAlive) {
            activeBleScanners.remove(binder)
            continue
        }
        val callback = info.callbackRef.get()
        if (callback != null) {
            try {
                deliverBleScanResults(config, callback, classLoader)
            } catch (e: Throwable) {
                activeBleScanners.remove(binder)
            }
        }
    }
}

private fun LocationHooker.ensureScannerCallbackHooked(callback: Any) {
    val clazz = callback.javaClass
    if (hookedCallbackClasses.putIfAbsent(clazz, true) != null) return
    val methods = arrayOf("onScanResult", "onBatchScanResults")
    for (mName in methods) {
        try {
            XposedHelpers.hookAllMethods(clazz, mName) { innerChain, _ ->
                val binder = (innerChain.thisObject as? IInterface)?.asBinder() ?: (innerChain.thisObject as? IBinder)
                val config = readConfig()
                val isGlobal = config?.optBoolean("system_hook_global_mode", false) == true
                val isTarget = (binder != null && activeBleScanners.containsKey(binder)) || isGlobal
                if (isTarget && config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    // 阻止真实物理硬件扫描到的外部蓝牙设备特征泄漏给目标应用
                    return@hookAllMethods null
                }
                return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }
}
