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

import android.os.IBinder
import android.os.IInterface
import android.util.Log
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.hooks.network.*
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemComponent
import com.vincenthzr.locationspoofer.xposed.diagnostics.HookStatus
import com.vincenthzr.locationspoofer.xposed.hooks.vendor.SystemClassLocator
import com.vincenthzr.locationspoofer.xposed.utils.*
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
 * 本模块在 com.android.bluetooth 进程内拦截 BLE 扫描入口（Android 17 起是 le_scan.ScanBinder，更早是 GattService）：
 * 1. 当目标应用调用 registerScanner / startScan / registerAndStartScan 时，记录其 IScannerCallback 监听句柄；
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

    // 扫描入口类：Android 17 的 le_scan.ScanBinder，或更早版本的 GattService（可能在 bluetooth APEX 里）；
    // 都找不到时从 ServiceManager 里已注册的 GATT 服务实例拿真实类
    var scanServiceClass = SystemClassLocator.locate(
        SystemComponent.BLUETOOTH_SCAN_SERVICE, classLoader,
        apexJars = listOf(
            "/apex/com.android.btservices/javalib/service-bluetooth.jar",
            "/apex/com.android.bluetooth/javalib/service-bluetooth.jar"
        )
    )

    if (scanServiceClass == null) {
        try {
            val smClass = XposedHelpers.findClassIfExists("android.os.ServiceManager", classLoader)
            val binder = if (smClass != null) {
                XposedHelpers.callStaticMethod(smClass, "getService", "bluetooth_gatt") as? android.os.IBinder
            } else null
            if (binder != null) {
                scanServiceClass = binder.javaClass
                HookStatus.classFound(SystemComponent.BLUETOOTH_SCAN_SERVICE, binder.javaClass, "ServiceManager.getService")
                sysLog("[SysBle] Captured GattService from ServiceManager.getService(\"bluetooth_gatt\"): ${binder.javaClass}")
            }
        } catch (_: Throwable) {}
    }

    if (scanServiceClass == null) {
        sysLog("[SysBle] BLE scan service not found in com.android.bluetooth")
        return
    }

    if (hookedCallbackClasses.putIfAbsent(scanServiceClass, true) != null) {
        isBluetoothServiceHooked = true
        return
    }

    startSystemBleHeartbeat(classLoader)

    // 三个方法名分属不同 Android 版本，每个版本上只存在其中一部分（Hook 运行状态里其余的显示 ×0）：
    // - registerScanner：旧版本的第一步，只登记回调，此时还没开始扫描；
    // - startScan：旧版本的第二步，扫描开始，立即派发一次虚拟信标；
    // - registerAndStartScan：Android 17 合并成一步。此刻客户端还没收到 scannerId，
    //   BluetoothLeScanner 会丢弃这之前到达的结果，所以首次派发延后一点，之后由心跳定时器持续推送。
    hookScannerEntry(scanServiceClass, "registerScanner", classLoader, dispatchDelayMs = null)
    hookScannerEntry(scanServiceClass, "startScan", classLoader, dispatchDelayMs = 0L, register = false)
    hookScannerEntry(scanServiceClass, "registerAndStartScan", classLoader, dispatchDelayMs = 500L)

    isBluetoothServiceHooked = true
}

/**
 * 在扫描入口方法前置拦截目标应用：
 * @param register 是否从参数里取出 IScannerCallback 登记为活跃扫描者（之后由心跳定时器持续派发虚拟信标）
 * @param dispatchDelayMs 非空时表示这次调用会开始扫描，在这么久之后立即派发一次虚拟信标
 */
private fun LocationHooker.hookScannerEntry(
    serviceClass: Class<*>,
    methodName: String,
    classLoader: ClassLoader,
    dispatchDelayMs: Long?,
    register: Boolean = true
) {
    try {
        XposedHelpers.hookAllMethods(serviceClass, methodName) { chain, _ ->
            try {
                val config = readConfig()
                val explicitPkg = config?.let { SystemHookUtils.extractPackageName(chain.args) }
                if (config != null && SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)) {
                    if (register) registerTargetScanner(chain.args, explicitPkg)
                    val mocking = config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)
                    if (dispatchDelayMs != null && mocking) {
                        sysLog("[SysBle] Target app started BLE scan via $methodName: ${explicitPkg ?: "caller"}")
                        if (dispatchDelayMs == 0L) {
                            dispatchFakeBeaconsToActiveScanners(config, classLoader)
                        } else {
                            Timer("LocationSpoofer-SysBleFirst", true).schedule(object : TimerTask() {
                                override fun run() {
                                    try {
                                        dispatchFakeBeaconsToActiveScanners(readConfig() ?: return, classLoader)
                                    } catch (_: Throwable) {}
                                }
                            }, dispatchDelayMs)
                        }
                    }
                }
            } catch (e: Throwable) {
                sysLog("[SysBle] $methodName pre-hook error: $e")
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
    } catch (e: Throwable) {
        sysLog("[SysBle] hook $methodName failed: $e")
    }
}

/** 从扫描入口的参数里取出目标应用的 IScannerCallback，登记为活跃扫描者并在客户端退出时自动移除 */
private fun LocationHooker.registerTargetScanner(args: List<Any?>, explicitPkg: String?) {
    val callback = args.firstOrNull { arg ->
        arg != null && (LocationHooker.hasTypeByName(arg.javaClass, "android.bluetooth.le.IScannerCallback") ||
            arg.javaClass.name.contains("IScannerCallback"))
    } ?: return
    val binder = (callback as? IInterface)?.asBinder() ?: (callback as? IBinder) ?: return
    val pkgName = explicitPkg ?: "unknown"
    activeBleScanners[binder] = BleScannerRegistration(callbackRef = WeakReference(callback), packageName = pkgName)
    try {
        binder.linkToDeath({
            activeBleScanners.remove(binder)
            sysLog("[SysBle] Scanner callback died and removed for $pkgName")
        }, 0)
    } catch (_: Throwable) {}
    ensureScannerCallbackHooked(callback)
    sysLog("[SysBle] Registered target BLE scanner for $pkgName (total active: ${activeBleScanners.size})")
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
