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
import com.suseoaa.locationspoofer.xposed.hooks.network.buildFakeCellInfoList
import com.suseoaa.locationspoofer.xposed.utils.XposedBridge
import com.suseoaa.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject

/**
 * system_server / 电话底层服务（PhoneInterfaceManager）基站小区拦截与伪造模块
 *
 * 核心原理：
 * 全设备所有应用调用 TelephonyManager.getAllCellInfo() 与 getCellLocation()
 * 都会经由 ITelephony 远程 IPC 路由至 PhoneInterfaceManager。
 *
 * 在此处拦截，向目标应用派发与目标模拟坐标一致的 GSM/LTE/5G NR 基站小区数据，
 * 彻底消除“GPS 坐标在 A 地，但蜂窝塔信号显示在 B 地”的交叉比对异常。
 */

internal fun LocationHooker.hookSystemTelephonyService(classLoader: ClassLoader) {
    val phoneServiceClass = XposedHelpers.findClassIfExists(
        "com.android.internal.telephony.PhoneInterfaceManager", classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.server.telephony.PhoneInterfaceManager", classLoader
    )

    if (phoneServiceClass == null) {
        XposedBridge.log("[LocationSpoofer][SysCell] PhoneInterfaceManager not found, skipping telephony hook")
        return
    }

    if (hookedCallbackClasses.putIfAbsent(phoneServiceClass, true) != null) {
        return
    }

    // =========================================================================
    // 1. getAllCellInfo：向目标应用派发伪造基站列表 (GSM/LTE/5G NR)
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(phoneServiceClass, "getAllCellInfo") { chain, _ ->
            val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean("active", false)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

            if (!isTarget) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val mockCell = config.optBoolean("mock_cell", true)
            if (!mockCell) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val lat = config.optDouble("lat", 0.0)
            val lng = config.optDouble("lng", 0.0)

            try {
                val fakeCellList = buildFakeCellInfoList(classLoader, lat, lng, config)
                XposedBridge.logOpenCellIdEvery(
                    "sys_cell_all_${explicitPkg ?: "global"}",
                    "[SysCell] Dispatched ${fakeCellList.size} fake CellInfo items to $explicitPkg"
                )
                return@hookAllMethods fakeCellList
            } catch (e: Throwable) {
                XposedBridge.log("[LocationSpoofer][SysCell] buildFakeCellInfoList failed: $e")
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        }
        XposedBridge.log("[LocationSpoofer][SysCell] PhoneInterfaceManager.getAllCellInfo hooked")
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer][SysCell] hook getAllCellInfo failed: $e")
    }

    // =========================================================================
    // 2. getCellLocation：向目标应用派发对应伪造位置的 CellLocation / Bundle
    // =========================================================================
    try {
        XposedHelpers.hookAllMethods(phoneServiceClass, "getCellLocation") { chain, _ ->
            val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean("active", false)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

            if (!isTarget) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val mockCell = config.optBoolean("mock_cell", true)
            if (!mockCell) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val lat = config.optDouble("lat", 0.0)
            val lng = config.optDouble("lng", 0.0)

            try {
                val gsmCellLocationClass = XposedHelpers.findClass(
                    "android.telephony.gsm.GsmCellLocation", classLoader
                )
                val fakeLocation = XposedHelpers.newInstance(gsmCellLocationClass)
                val lac = ((lat * 100).toInt().coerceAtLeast(1000) % 65535)
                val cid = ((lng * 1000).toInt().coerceAtLeast(10000) % 268435455)
                XposedHelpers.callMethod(fakeLocation, "setLacAndCid", lac, cid)
                return@hookAllMethods fakeLocation
            } catch (e: Throwable) {
                XposedBridge.log("[LocationSpoofer][SysCell] build fake CellLocation failed: $e")
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        }
        XposedBridge.log("[LocationSpoofer][SysCell] PhoneInterfaceManager.getCellLocation hooked")
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer][SysCell] hook getCellLocation failed: $e")
    }
}
