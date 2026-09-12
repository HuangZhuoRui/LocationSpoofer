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
    "UnusedImport")

package com.suseoaa.locationspoofer.xposed.hooks

import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.XposedBridge
import com.suseoaa.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject

private fun sysLog(msg: String) = XposedBridge.log(msg)

/**
 * system_server 级 AppOps 与系统设置（AppOpsService / Settings）反检测模块
 *
 * 核心原理：
 * 考勤与反作弊 SDK 会通过 AppOpsManager.checkOp(OP_MOCK_LOCATION, ...) 或读取 Settings.Secure
 * 查询系统是否开启了“允许模拟位置”。
 *
 * 本模块在 system_server 中的 AppOpsService 根节点拦截该查询，向目标应用返回 MODE_IGNORED (1)，
 * 并在设置层返回 0，从系统内核层面彻底隐蔽模拟定位的存在。
 */

internal fun LocationHooker.hookSystemAppOpsService(classLoader: ClassLoader) {
    val appOpsServiceClass = XposedHelpers.findClassIfExists(
        "com.android.server.appop.AppOpsService", classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.server.AppOpsService", classLoader
    )

    if (appOpsServiceClass != null && hookedCallbackClasses.putIfAbsent(appOpsServiceClass, true) == null) {
        val opMethods = arrayOf("checkOperation", "noteOperation", "checkAudioOperation")
        for (methodName in opMethods) {
            try {
                XposedHelpers.hookAllMethods(appOpsServiceClass, methodName) { chain, _ ->
                    val config = readConfig()
                    if (config != null && config.optBoolean("active", false)) {
                        val codeArg = chain.args.firstOrNull { it is Int } as? Int
                        if (codeArg == 58) { // OP_MOCK_LOCATION
                            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
                            if (SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)) {
                                return@hookAllMethods 1 // MODE_IGNORED
                            }
                        }
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }
        sysLog("[SysHook] AppOpsService OP_MOCK_LOCATION hooked")
    }

    // 拦截 Settings.Secure 中 mock_location 开关读取
    try {
        val settingsSecureClass = XposedHelpers.findClassIfExists("android.provider.Settings\$Secure", classLoader)
        if (settingsSecureClass != null) {
            XposedHelpers.hookAllMethods(settingsSecureClass, "getString") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false)) {
                    val key = chain.args.firstOrNull { it is String } as? String
                    if (key == "mock_location" || key == "allow_mock_location") {
                        return@hookAllMethods "0"
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        }
    } catch (_: Throwable) {}
}
