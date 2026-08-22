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

package com.suseoaa.locationspoofer.xposed.hooks.network

import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.*
import com.suseoaa.locationspoofer.xposed.hooks.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Member
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * 蜂窝网络环境伪造模块 (Cellular Environment Hooker - 基站数据)
 * 
 * 上下文:
 * 现代手机定位是 "融合定位"。如果我们在北京，但手机连着的 4G/5G 手机基站显示在上海，
 * 地图 SDK 会立刻判定定位异常 (坐标在北京和上海之间横跳)。
 * 
 * 作用:
 * 把设备的基站 (CellInfo, CellLocation, ServiceState) 强制篡改为与伪造位置相符的数据。
 * 关键部分解释:
 * 1. hookCellEnvironment: 拦截 `TelephonyManager.getAllCellInfo()` 和各种电话状态回调。
 *    对于 ColorOS 等定制系统，采取了 "就地修改" (in-place mutation) 策略，直接修改原有的 CellInfo 对象属性，
 *    以防止抛出 ClassCastException 导致系统崩溃。
 * 2. buildFakeCellInfoList 等辅助方法: 根据纬度、经度计算伪基站数据 (MCC, MNC, LAC, CID, PCI)，
 *    构造出与真实网络无异的蜂窝塔信息。
 */

internal fun LocationHooker.hookCellEnvironment(
    classLoader: ClassLoader,
    isCoreSystemProcess: Boolean = false
) {
    if (isCoreSystemProcess) {
        XposedBridge.logOpenCellId("Skipping cell environment hooks in core system process")
        return
    }
    XposedBridge.logOpenCellId("Installing cell hooks classLoader=$classLoader")

    // 1. 基站信息伪造（CellLocation / AllCellInfo / NeighboringCellInfo）
    fun handleCellMethod(chain: XposedInterface.Chain, method: java.lang.reflect.Executable): Any? {
        val methodName = method.name
        val config = readConfig()
        if (config == null) {
            XposedBridge.logOpenCellIdEvery(
                "$methodName:config-null",
                "$methodName skipped: config=null",
                30_000L
            )
            return chain.proceed(chain.args.toTypedArray())
        }
        if (!config.optBoolean("active", false)) {
            XposedBridge.logOpenCellIdEvery(
                "$methodName:inactive",
                "$methodName skipped: active=false",
                30_000L
            )
            return chain.proceed(chain.args.toTypedArray())
        }
        val lat = config.optDouble("lat", 0.0)
        val lng = config.optDouble("lng", 0.0)
        val mockCellForLog = config.optBoolean("mock_cell", true)
        val cellCountForLog = config.optJSONArray("cell_json")?.length() ?: 0
        XposedBridge.logOpenCellIdEvery(
            "$methodName:called:$mockCellForLog:$cellCountForLog",
            "$methodName called active=true mockCell=$mockCellForLog cellJsonCount=$cellCountForLog lat=$lat lng=$lng"
        )

        when (methodName) {
            "getCellLocation" -> {
                try {
                    val mockCell = config.optBoolean("mock_cell", true)
                    if (mockCell) {
                        val gsmCellLocationClass = XposedHelpers.findClass(
                            "android.telephony.gsm.GsmCellLocation", classLoader
                        )
                        val fakeLocation = XposedHelpers.newInstance(gsmCellLocationClass)
                        val cellArray = config.optJSONArray("cell_json")
                        val lac: Int
                        val cid: Int
                        if (cellArray != null && cellArray.length() > 0) {
                            val cell = cellArray.getJSONObject(0)
                            lac = cellAreaCode(cell, fallbackAreaCode(lat, lng))
                            cid = cellIdentityCode(cell, fallbackCellIdentity(lat, lng))
                        } else {
                            lac = fallbackAreaCode(lat, lng)
                            cid = fallbackCellIdentity(lat, lng)
                        }
                        XposedHelpers.callMethod(fakeLocation, "setLacAndCid", lac, cid)
                        XposedBridge.logOpenCellId("getCellLocation returning GsmCellLocation lac=$lac cid=$cid")
                        return fakeLocation
                    } else {
                        XposedBridge.logOpenCellId("getCellLocation returning null because mock_cell=false")
                        return null
                    }
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("getCellLocation failed: $e")
                    return null
                }
            }

            "getAllCellInfo" -> {
                try {
                    if (config.optBoolean("mock_cell", true)) {
                        val fakeCells = buildFakeCellInfoList(classLoader, lat, lng, config)
                        XposedBridge.logOpenCellIdEvery(
                            "getAllCellInfo:return:${fakeCells.size}",
                            "getAllCellInfo returning fakeCells=${fakeCells.size}"
                        )
                        return fakeCells
                    } else {
                        XposedBridge.logOpenCellId("getAllCellInfo returning empty because mock_cell=false")
                        return java.util.ArrayList<Any>()
                    }
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("getAllCellInfo build failed: $e")
                    return java.util.ArrayList<Any>()
                }
            }

            "getNeighboringCellInfo" -> {
                XposedBridge.logOpenCellId("getNeighboringCellInfo returning empty list")
                return java.util.ArrayList<Any>()
            }
        }
        return chain.proceed(chain.args.toTypedArray())
    }

    try {
        XposedHelpers.hookMethod(
            "android.telephony.TelephonyManager",
            classLoader,
            "getAllCellInfo"
        ) { chain, method -> return@hookMethod handleCellMethod(chain, method) }

        XposedHelpers.hookMethod(
            "android.telephony.TelephonyManager",
            classLoader,
            "getCellLocation"
        ) { chain, method -> return@hookMethod handleCellMethod(chain, method) }

        XposedHelpers.hookMethod(
            "android.telephony.TelephonyManager",
            classLoader,
            "getNeighboringCellInfo"
        ) { chain, method -> return@hookMethod handleCellMethod(chain, method) }
        XposedBridge.logOpenCellId("Installed TelephonyManager getAllCellInfo/getCellLocation/getNeighboringCellInfo hooks")
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("Install basic TelephonyManager cell hooks failed: $e")
    }

    // 2. TelephonyManager 元数据 Hook
    // 防止 MCC/MNC/运营商名称/网络类型泄漏真实地理位置
    // 高德用 getNetworkOperator() 验证基站数据是否与 GPS 位置地理一致
    fun handleTelephonyMeta(
        chain: XposedInterface.Chain,
        method: java.lang.reflect.Executable
    ): Any? {
        val methodName = method.name
        val config = readConfig()
        if (config == null) {
            XposedBridge.logOpenCellIdEvery(
                "$methodName:config-null",
                "$methodName skipped: config=null",
                30_000L
            )
            return chain.proceed(chain.args.toTypedArray())
        }
        if (!config.optBoolean("active", false)) {
            XposedBridge.logOpenCellIdEvery(
                "$methodName:inactive",
                "$methodName skipped: active=false",
                30_000L
            )
            return chain.proceed(chain.args.toTypedArray())
        }
        val mockCell = config.optBoolean("mock_cell", true)
        val cellArray = if (mockCell) config.optJSONArray("cell_json") else null
        XposedBridge.logOpenCellIdEvery(
            "$methodName:called:$mockCell:${cellArray?.length() ?: 0}",
            "$methodName called mockCell=$mockCell cellJsonCount=${cellArray?.length() ?: 0}"
        )
        when (methodName) {
            "getNetworkOperator" -> {
                if (cellArray != null && cellArray.length() > 0) {
                    val cell = cellArray.getJSONObject(0)
                    val mcc = positiveJsonInt(cell, "mcc", default = 460)
                    val mnc = positiveJsonInt(cell, "mnc", "net", default = 0)
                    val operator = String.format(java.util.Locale.US, "%d%02d", mcc, mnc)
                    XposedBridge.logOpenCellIdEvery(
                        "getNetworkOperator:return:$operator",
                        "getNetworkOperator returning $operator"
                    )
                    return operator
                } else if (!mockCell) {
                    return ""
                }
            }

            "getNetworkOperatorName" -> {
                if (cellArray != null && cellArray.length() > 0) {
                    val mnc = positiveJsonInt(
                        cellArray.getJSONObject(0),
                        "mnc",
                        "net",
                        default = 0
                    )
                    val result = when (mnc) {
                        0, 2, 7 -> "中国移动"
                        1, 6, 9 -> "中国联通"
                        3, 5, 11 -> "中国电信"
                        else -> "中国移动"
                    }
                    XposedBridge.logOpenCellIdEvery(
                        "getNetworkOperatorName:return:${result}",
                        "getNetworkOperatorName returning ${result}"
                    )
                    return result
                } else if (!mockCell) {
                    return ""
                }
            }

            "getSimOperator" -> { /* 保留真实值 */
            }

            "getSimOperatorName" -> { /* 保留真实值 */
            }

            "getNetworkType" -> return if (mockCell) 13 else 0
            "getDataNetworkType" -> return if (mockCell) 13 else 0
            "getPhoneType" -> return 1      // GSM 电话类型
            "getServiceState", "getServiceStateForSlot" -> {
                if (mockCell) buildFakeServiceState(classLoader, cellArray)?.let {
                    XposedBridge.logOpenCellIdEvery(
                        "$methodName:return-service-state",
                        "$methodName returning fake ServiceState"
                    )
                    return it
                }
            }

            "getSignalStrength" -> {
                if (mockCell) buildFakeSignalStrength(classLoader, config)?.let {
                    XposedBridge.logOpenCellIdEvery(
                        "getSignalStrength:return-signal-strength",
                        "getSignalStrength returning fake SignalStrength"
                    )
                    return it
                }
            }
        }
        return chain.proceed(chain.args.toTypedArray())
    }

    val telephonyMetaMethods = listOf(
        "getNetworkOperator", "getNetworkOperatorName",
        "getNetworkType", "getDataNetworkType", "getPhoneType",
        "getServiceState", "getServiceStateForSlot", "getSignalStrength"
    )
    for (method in telephonyMetaMethods) {
        try {
            XposedHelpers.hookAllMethods(
                XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
                method
            ) { chain, method -> return@hookAllMethods handleTelephonyMeta(chain, method) }
            XposedBridge.logOpenCellId("Installed TelephonyManager.$method hook")
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Install TelephonyManager.$method hook failed: $e")
        }
    }

    // 3. PhoneStateListener 回调拦截
    // 防止应用通过 TelephonyManager.listen() 的 LISTEN_CELL_INFO 回调
    // 绕过 getAllCellInfo() 的 Hook 获取真实基站数据
    try {
        XposedHelpers.hookMethod(
            "android.telephony.TelephonyManager", classLoader, "listen",
            "android.telephony.PhoneStateListener",
            Int::class.javaPrimitiveType!!
        ) { chain, method ->
            val config = readConfig()
            if (config == null) {
                XposedBridge.logOpenCellIdEvery(
                    "listen:config-null",
                    "TelephonyManager.listen skipped: config=null",
                    30_000L
                )
                return@hookMethod chain.proceed(chain.args.toTypedArray())
            }
            if (!config.optBoolean("active", false)) {
                XposedBridge.logOpenCellIdEvery(
                    "listen:inactive",
                    "TelephonyManager.listen skipped: active=false",
                    30_000L
                )
                return@hookMethod chain.proceed(chain.args.toTypedArray())
            }
            val listener =
                chain.args[0] ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            val originalEvents = chain.args[1] as Int
            val lat = config.optDouble("lat", 0.0)
            val lng = config.optDouble("lng", 0.0)
            val mockCell = config.optBoolean("mock_cell", true)
            val cellJsonCount = config.optJSONArray("cell_json")?.length() ?: 0
            val needsCellInfo = (originalEvents and 0x400) != 0
            val needsCellLocation = (originalEvents and 0x10) != 0
            val needsServiceState = (originalEvents and 0x1) != 0
            val needsSignalStrength = (originalEvents and 0x100) != 0
            if (!needsCellInfo && !needsCellLocation && !needsServiceState && !needsSignalStrength) {
                return@hookMethod chain.proceed(chain.args.toTypedArray())
            }
            XposedBridge.logOpenCellIdEvery(
                "listen:called:${listener.javaClass.name}:$originalEvents:$mockCell:$cellJsonCount",
                "TelephonyManager.listen called listener=${listener.javaClass.name} events=0x${
                    originalEvents.toString(
                        16
                    )
                } mockCell=$mockCell cellJsonCount=$cellJsonCount"
            )
            val fakeCells by lazy {
                if (mockCell) {
                    buildFakeCellInfoList(classLoader, lat, lng, config)
                } else {
                    java.util.ArrayList<Any>()
                }
            }
            if ((originalEvents and 0x10) != 0) {
                try {
                    if (mockCell) {
                        val gsmCellLocationClass = XposedHelpers.findClass(
                            "android.telephony.gsm.GsmCellLocation", classLoader
                        )
                        val fakeLocation =
                            XposedHelpers.newInstance(gsmCellLocationClass)
                        val cellArray = config.optJSONArray("cell_json")
                        val lac: Int
                        val cid: Int
                        if (cellArray != null && cellArray.length() > 0) {
                            val cell = cellArray.getJSONObject(0)
                            lac = cellAreaCode(cell, fallbackAreaCode(lat, lng))
                            cid = cellIdentityCode(cell, fallbackCellIdentity(lat, lng))
                        } else {
                            lac = fallbackAreaCode(lat, lng)
                            cid = fallbackCellIdentity(lat, lng)
                        }
                        XposedHelpers.callMethod(
                            fakeLocation,
                            "setLacAndCid",
                            lac,
                            cid
                        )
                        XposedHelpers.callMethod(
                            listener,
                            "onCellLocationChanged",
                            fakeLocation
                        )
                        XposedBridge.logOpenCellIdEvery(
                            "listen:onCellLocationChanged:$lac:$cid",
                            "listen dispatched onCellLocationChanged lac=$lac cid=$cid"
                        )
                    }
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("listen onCellLocationChanged failed: $e")
                }
            }
            if ((originalEvents and 0x400) != 0) {
                try {
                    XposedHelpers.callMethod(listener, "onCellInfoChanged", fakeCells)
                    XposedBridge.logOpenCellIdEvery(
                        "listen:onCellInfoChanged:${fakeCells.size}",
                        "listen dispatched onCellInfoChanged fakeCells=${fakeCells.size}"
                    )
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("listen onCellInfoChanged failed: $e")
                }
            }
            if ((originalEvents and 0x1) != 0) {
                try {
                    buildFakeServiceState(classLoader, config.optJSONArray("cell_json"))
                        ?.let {
                            XposedHelpers.callMethod(
                                listener,
                                "onServiceStateChanged",
                                it
                            )
                        }
                    XposedBridge.logOpenCellIdEvery(
                        "listen:onServiceStateChanged",
                        "listen dispatched onServiceStateChanged"
                    )
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("listen onServiceStateChanged failed: $e")
                }
            }
            if ((originalEvents and 0x100) != 0) {
                try {
                    buildFakeSignalStrength(classLoader, config)
                        ?.let {
                            XposedHelpers.callMethod(
                                listener,
                                "onSignalStrengthsChanged",
                                it
                            )
                        }
                    XposedBridge.logOpenCellIdEvery(
                        "listen:onSignalStrengthsChanged",
                        "listen dispatched onSignalStrengthsChanged"
                    )
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("listen onSignalStrengthsChanged failed: $e")
                }
            }
            var events = originalEvents
            // 移除会泄漏真实蜂窝环境的标志位
            // 这样系统就不会将真实的基站变更回调给应用
            events = events and 0x1.inv()    // 监听服务状态
            events = events and 0x10.inv()   // 监听基站位置
            events = events and 0x100.inv()  // 监听信号强度
            events = events and 0x400.inv()  // 监听基站信息
            val newArgs = chain.args.toTypedArray()
            newArgs[1] = events
            XposedBridge.logOpenCellIdEvery(
                "listen:sanitized:$originalEvents:$events",
                "TelephonyManager.listen sanitized events=0x${events.toString(16)}"
            )
            return@hookMethod chain.proceed(newArgs)
        }
        XposedBridge.logOpenCellId("Installed TelephonyManager.listen hook")
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("Install TelephonyManager.listen hook failed: $e")
    }

    // 4. TelephonyManager.requestCellInfoUpdate 异步刷新拦截 (Android 10+)
    try {
        XposedHelpers.hookAllMethods(
            XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
            "requestCellInfoUpdate"
        ) { chain, method ->
            val config = readConfig()
            if (config == null) {
                XposedBridge.logOpenCellIdEvery(
                    "requestCellInfoUpdate:config-null",
                    "requestCellInfoUpdate skipped: config=null",
                    30_000L
                )
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            if (!config.optBoolean("active", false)) {
                XposedBridge.logOpenCellIdEvery(
                    "requestCellInfoUpdate:inactive",
                    "requestCellInfoUpdate skipped: active=false",
                    30_000L
                )
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val executor =
                chain.args[0] as? java.util.concurrent.Executor ?: return@hookAllMethods null
            val callback = chain.args[1] ?: return@hookAllMethods null
            XposedBridge.logOpenCellIdEvery(
                "requestCellInfoUpdate:called:${callback.javaClass.name}",
                "requestCellInfoUpdate called callback=${callback.javaClass.name} args=${chain.args.size}"
            )

            val mockCell = config.optBoolean("mock_cell", true)
            val lat = config.optDouble("lat", 0.0)
            val lng = config.optDouble("lng", 0.0)

            val fakeCells = if (mockCell) {
                buildFakeCellInfoList(classLoader, lat, lng, config)
            } else {
                java.util.ArrayList<Any>()
            }

            // 异步回调
            executor.execute {
                try {
                    XposedHelpers.callMethod(callback, "onCellInfo", fakeCells)
                    XposedBridge.logOpenCellId("requestCellInfoUpdate dispatched onCellInfo fakeCells=${fakeCells.size}")
                } catch (e: Throwable) {
                    XposedBridge.logOpenCellId("requestCellInfoUpdate onCellInfo failed: $e")
                }
            }
            return@hookAllMethods null
        }
        XposedBridge.logOpenCellId("Installed TelephonyManager.requestCellInfoUpdate hook")
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("Install TelephonyManager.requestCellInfoUpdate hook failed: $e")
    }

    // 5. TelephonyCallback 拦截 (Android 12+ / API 31+)
    // registerTelephonyCallback 替代了旧版 listen()，
    // 通过 TelephonyCallback.CellInfoListener 接收基站变化。
    // 需要 hook 注册过程，对每个 callback 实例的 onCellInfoChanged 进行拦截。
    try {
        XposedHelpers.hookAllMethods(
            XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader),
            "registerTelephonyCallback"
        ) { chain, method ->
            val config = readConfig()
            if (config == null) {
                XposedBridge.logOpenCellIdEvery(
                    "registerTelephonyCallback:config-null",
                    "registerTelephonyCallback skipped: config=null",
                    30_000L
                )
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
            if (!config.optBoolean("active", false)) {
                XposedBridge.logOpenCellIdEvery(
                    "registerTelephonyCallback:inactive",
                    "registerTelephonyCallback skipped: active=false",
                    30_000L
                )
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            // 找到 TelephonyCallback 实例参数
            val callback = chain.args.firstOrNull { arg ->
                arg != null && (arg.javaClass.interfaces.any { iface ->
                    iface.name.contains("TelephonyCallback")
                } || LocationHooker.hasTypeByName(
                    arg.javaClass,
                    "android.telephony.TelephonyCallback"
                ))
            }
            if (callback == null) {
                XposedBridge.logOpenCellId("registerTelephonyCallback called but callback not found args=${chain.args.map { it?.javaClass?.name }}")
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val callbackClass = callback.javaClass
            XposedBridge.logOpenCellIdEvery(
                "registerTelephonyCallback:called:${callbackClass.name}",
                "registerTelephonyCallback called callback=${callbackClass.name} interfaces=${callbackClass.interfaces.joinToString { it.name }}"
            )

            if (isTelephonyCallbackListener(
                    classLoader,
                    callback,
                    "CellInfoListener"
                )
            ) {
                XposedBridge.logOpenCellIdEvery(
                    "registerTelephonyCallback:CellInfoListener:${callbackClass.name}",
                    "registerTelephonyCallback installing CellInfoListener hook on ${callbackClass.name}",
                    60_000L
                )
                XposedHelpers.hookAllMethods(
                    callbackClass,
                    "onCellInfoChanged"
                ) { innerChain, _ ->
                    val freshConfig = readConfig() ?: return@hookAllMethods innerChain.proceed(
                        innerChain.args.toTypedArray()
                    )
                    if (!freshConfig.optBoolean(
                            "active",
                            false
                        )
                    ) return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                    val lat = freshConfig.optDouble("lat", 0.0)
                    val lng = freshConfig.optDouble("lng", 0.0)
                    val fakeCells = buildFakeCellInfoList(
                        classLoader,
                        lat,
                        lng,
                        freshConfig
                    )
                    innerChain.args[0] = fakeCells
                    XposedBridge.logOpenCellId("TelephonyCallback.onCellInfoChanged injected fakeCells=${fakeCells.size}")
                    return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                }
            }

            if (isTelephonyCallbackListener(
                    classLoader,
                    callback,
                    "ServiceStateListener"
                )
            ) {
                XposedBridge.logOpenCellIdEvery(
                    "registerTelephonyCallback:ServiceStateListener:${callbackClass.name}",
                    "registerTelephonyCallback installing ServiceStateListener hook on ${callbackClass.name}",
                    60_000L
                )
                XposedHelpers.hookAllMethods(
                    callbackClass,
                    "onServiceStateChanged"
                ) { innerChain, _ ->
                    val freshConfig = readConfig() ?: return@hookAllMethods innerChain.proceed(
                        innerChain.args.toTypedArray()
                    )
                    if (!freshConfig.optBoolean(
                            "active",
                            false
                        )
                    ) return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                    buildFakeServiceState(
                        classLoader,
                        freshConfig.optJSONArray("cell_json")
                    )
                        ?.let {
                            innerChain.args[0] = it
                            XposedBridge.logOpenCellId("TelephonyCallback.onServiceStateChanged injected fake ServiceState")
                        }
                    return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                }
            }

            if (isTelephonyCallbackListener(
                    classLoader,
                    callback,
                    "SignalStrengthsListener"
                )
            ) {
                XposedBridge.logOpenCellIdEvery(
                    "registerTelephonyCallback:SignalStrengthsListener:${callbackClass.name}",
                    "registerTelephonyCallback installing SignalStrengthsListener hook on ${callbackClass.name}",
                    60_000L
                )
                XposedHelpers.hookAllMethods(
                    callbackClass,
                    "onSignalStrengthsChanged"
                ) { innerChain, _ ->
                    val freshConfig = readConfig() ?: return@hookAllMethods innerChain.proceed(
                        innerChain.args.toTypedArray()
                    )
                    if (!freshConfig.optBoolean(
                            "active",
                            false
                        )
                    ) return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                    buildFakeSignalStrength(classLoader, freshConfig)
                        ?.let {
                            innerChain.args[0] = it
                            XposedBridge.logOpenCellId("TelephonyCallback.onSignalStrengthsChanged injected fake SignalStrength")
                        }
                    return@hookAllMethods innerChain.proceed(innerChain.args.toTypedArray())
                }
            }
            return@hookAllMethods chain.proceed(chain.args.toTypedArray())
        }
        XposedBridge.logOpenCellId("Installed TelephonyManager.registerTelephonyCallback hook")
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("Install TelephonyManager.registerTelephonyCallback hook failed: $e")
    }

    XposedBridge.logOpenCellId("Cell environment hooks installed")
}

/**
 * 通过反射+Parcel机制构造CellInfoLte对象列表
 *
 * CellInfoLte/CellIdentityLte等类的构造器在Android各版本中签名不同,
 * 直接new会因API版本差异崩溃。通过反射调用内部构造器并设置字段值,
 * 兼容Android 7.0~14。
 *
 * 参数生成策略:
 * - MCC=460(中国), MNC=01(中国移动)或11(中国电信): 使用中国运营商真实前缀
 * - TAC(Tracking Area Code): 基于经纬度hash生成,范围1-65534
 * - CI(Cell Identity): 基于坐标生成,范围1-268435455(28bit)
 * - 生成2-3个基站: 第一个为服务小区(isRegistered=true),其余为邻区
 *
 * @param classLoader 目标App的ClassLoader
 * @param lat 目标纬度(GCJ-02)
 * @param lng 目标经度(GCJ-02)
 * @return 包含2-3个CellInfoLte对象的ArrayList
 */

internal fun LocationHooker.buildFakeCellInfoList(
    classLoader: ClassLoader, lat: Double, lng: Double, config: org.json.JSONObject?
): java.util.ArrayList<Any> {
    val result = java.util.ArrayList<Any>()

    val cellArray = config?.optJSONArray("cell_json")
    XposedBridge.logOpenCellIdEvery(
        "buildFakeCellInfoList:called:${cellArray?.length() ?: 0}",
        "buildFakeCellInfoList called cellJsonCount=${cellArray?.length() ?: 0}"
    )
    if (cellArray != null && cellArray.length() > 0) {
        var hasLteOrNr = false
        var gsmCount = 0
        var wcdmaCount = 0
        var lteCount = 0
        var nrCount = 0
        var firstSummary: String? = null
        for (i in 0 until cellArray.length()) {
            try {
                val obj = cellArray.getJSONObject(i)
                val type =
                    normalizeCellType(obj.optString("type", obj.optString("radio", "LTE")))
                val isRegistered = obj.optBoolean("isRegistered", i == 0)
                if (type == "LTE" || type == "NR") {
                    hasLteOrNr = true
                }
                when (type) {
                    "GSM" -> gsmCount++
                    "WCDMA", "UMTS" -> wcdmaCount++
                    "NR" -> nrCount++
                    else -> lteCount++
                }

                val mcc = positiveJsonInt(obj, "mcc", default = 460)
                val mnc = positiveJsonInt(obj, "mnc", "net", default = 0)
                val tacOrLac = cellAreaCode(obj, 10000)
                val ciOrCid = cellIdentityCode(obj, 100000)
                val pci = positiveJsonInt(
                    obj,
                    "pci",
                    "psc",
                    default = (ciOrCid % 504).coerceIn(0, 503)
                )
                val dbm = signalDbm(obj, i)
                if (firstSummary == null) {
                    firstSummary =
                        "$type/$mcc-$mnc area=$tacOrLac identity=$ciOrCid dbm=$dbm registered=$isRegistered"
                }
                if (LocationHooker.VERBOSE_CELL_BUILD_LOGS) {
                    XposedBridge.logOpenCellId(
                        "buildFakeCellInfoList source[$i] radio=${
                            obj.optString(
                                "radio",
                                ""
                            )
                        } type=$type registered=$isRegistered mcc=$mcc mnc=$mnc area=$tacOrLac identity=$ciOrCid pci=$pci dbm=$dbm"
                    )
                }

                // 1. 寻找并构造具体的 CellInfo 派生类
                val cellInfoClass = when (type) {
                    "GSM" -> XposedHelpers.findClass(
                        "android.telephony.CellInfoGsm",
                        classLoader
                    )

                    "WCDMA", "UMTS" -> XposedHelpers.findClass(
                        "android.telephony.CellInfoWcdma",
                        classLoader
                    )

                    "NR" -> try {
                        XposedHelpers.findClass("android.telephony.CellInfoNr", classLoader)
                    } catch (e: Throwable) {
                        XposedHelpers.findClass("android.telephony.CellInfoLte", classLoader)
                    }

                    else -> XposedHelpers.findClass(
                        "android.telephony.CellInfoLte",
                        classLoader
                    )
                }
                val cellInfo = XposedHelpers.newInstance(cellInfoClass)

                // 设置注册标志（Android 9 及以下用 mRegistered；Android 10+ 用 mCellConnectionStatus）
                // 连接状态: 0=无连接, 1=主服务, 2=次服务
                val connectionStatus = if (isRegistered) 1 else 0
                try {
                    XposedHelpers.setBooleanField(cellInfo, "mRegistered", isRegistered)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(
                        cellInfo,
                        "mCellConnectionStatus",
                        connectionStatus
                    )
                } catch (_: Throwable) {
                }
                try {
                    if (isRegistered) XposedHelpers.callMethod(cellInfo, "setRegistered", true)
                } catch (_: Throwable) {
                }

                try {
                    XposedHelpers.setLongField(
                        cellInfo,
                        "mTimeStamp",
                        android.os.SystemClock.elapsedRealtimeNanos()
                    )
                } catch (e: Throwable) {
                }

                // 2. 构造 CellIdentity（尝试多种有参构造器，避免 final 字段反射问题）
                val cellIdentityClass = when (type) {
                    "GSM" -> XposedHelpers.findClass(
                        "android.telephony.CellIdentityGsm",
                        classLoader
                    )

                    "WCDMA", "UMTS" -> XposedHelpers.findClass(
                        "android.telephony.CellIdentityWcdma",
                        classLoader
                    )

                    "NR" -> try {
                        XposedHelpers.findClass("android.telephony.CellIdentityNr", classLoader)
                    } catch (e: Throwable) {
                        XposedHelpers.findClass(
                            "android.telephony.CellIdentityLte",
                            classLoader
                        )
                    }

                    else -> XposedHelpers.findClass(
                        "android.telephony.CellIdentityLte",
                        classLoader
                    )
                }
                val mccStr = mcc.toString()
                val mncStr = if (mnc < 10) "0$mnc" else mnc.toString()
                val cellIdentity = constructCellIdentityByType(
                    type, cellIdentityClass, mcc, mccStr, mnc, mncStr, tacOrLac, ciOrCid, pci
                )
                if (LocationHooker.VERBOSE_CELL_BUILD_LOGS) {
                    XposedBridge.logOpenCellId("Built $type identity: MCC=$mcc MNC=$mnc TAC/LAC=$tacOrLac CI/CID=$ciOrCid PCI=$pci -> ${cellIdentity.javaClass.simpleName}")
                }

                // 验证注入是否成功（如果 getCi()/getLac() 返回 Integer.MAX_VALUE 说明注入失败）
                try {
                    val verifyMethod = when (type) {
                        "LTE" -> "getCi"
                        "GSM" -> "getLac"
                        "WCDMA", "UMTS" -> "getLac"
                        "NR" -> "getPci"
                        else -> "getCi"
                    }
                    val readBack = XposedHelpers.callMethod(cellIdentity, verifyMethod) as? Int
                    if (readBack == Int.MAX_VALUE || readBack == -1) {
                        XposedBridge.logOpenCellId("WARNING: $type.$verifyMethod()=$readBack, identity injection may have failed")
                    } else if (LocationHooker.VERBOSE_CELL_BUILD_LOGS) {
                        XposedBridge.logOpenCellId("VERIFY OK: $type.$verifyMethod()=$readBack")
                    }
                } catch (_: Throwable) {
                }

                // 将 CellIdentity 存入 CellInfo (兼容新老版本字段名)
                val identityField = when (type) {
                    "GSM" -> "mCellIdentityGsm"
                    "WCDMA", "UMTS" -> "mCellIdentityWcdma"
                    "NR" -> "mCellIdentityNr"
                    else -> "mCellIdentityLte"
                }
                try {
                    XposedHelpers.setObjectField(cellInfo, identityField, cellIdentity)
                } catch (e: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(cellInfo, "mCellIdentity", cellIdentity)
                } catch (e: Throwable) {
                }

                // 3. 构造并配置对应的 CellSignalStrength
                val cssClass = when (type) {
                    "GSM" -> XposedHelpers.findClass(
                        "android.telephony.CellSignalStrengthGsm",
                        classLoader
                    )

                    "WCDMA", "UMTS" -> XposedHelpers.findClass(
                        "android.telephony.CellSignalStrengthWcdma",
                        classLoader
                    )

                    "NR" -> try {
                        XposedHelpers.findClass(
                            "android.telephony.CellSignalStrengthNr",
                            classLoader
                        )
                    } catch (e: Throwable) {
                        XposedHelpers.findClass(
                            "android.telephony.CellSignalStrengthLte",
                            classLoader
                        )
                    }

                    else -> XposedHelpers.findClass(
                        "android.telephony.CellSignalStrengthLte",
                        classLoader
                    )
                }
                val css = XposedHelpers.newInstance(cssClass)

                when (type) {
                    "GSM" -> {
                        val asu = ((dbm + 113) / 2).coerceIn(0, 31)
                        try {
                            XposedHelpers.setIntField(css, "mRssi", dbm)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mGsmSignalStrength", asu)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mSignalStrength", dbm)
                        } catch (e: Throwable) {
                        }
                    }

                    "WCDMA", "UMTS" -> {
                        val asu = (dbm + 116).coerceIn(0, 95)
                        try {
                            XposedHelpers.setIntField(css, "mRscp", dbm)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mSignalStrength", dbm)
                        } catch (e: Throwable) {
                        }
                    }

                    "NR" -> {
                        try {
                            XposedHelpers.setIntField(css, "mCsiRsrp", dbm)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mCsiRsrq", -10)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mCsiSinr", 15)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mSsRsrp", dbm)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mSsRsrq", -10)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mSsSinr", 15)
                        } catch (e: Throwable) {
                        }
                    }

                    else -> { // LTE
                        try {
                            XposedHelpers.setIntField(css, "mRsrp", dbm)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mRsrq", -10)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mRssnr", 300)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(css, "mSignalStrength", dbm + 113)
                        } catch (e: Throwable) {
                        }
                    }
                }

                // 将 CellSignalStrength 存入 CellInfo (兼容新老版本字段名)
                val cssField = when (type) {
                    "GSM" -> "mCellSignalStrengthGsm"
                    "WCDMA", "UMTS" -> "mCellSignalStrengthWcdma"
                    "NR" -> "mCellSignalStrengthNr"
                    else -> "mCellSignalStrengthLte"
                }
                try {
                    XposedHelpers.setObjectField(cellInfo, cssField, css)
                } catch (e: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(cellInfo, "mCellSignalStrength", css)
                } catch (e: Throwable) {
                }

                result.add(cellInfo)
            } catch (e: Throwable) {
                XposedBridge.logOpenCellId(
                    "buildFakeCellInfoList failed to parse/build cell_json[$i]",
                    e
                )
            }
        }
        if (result.isNotEmpty() && !hasLteOrNr) {
            try {
                val seed = org.json.JSONObject(cellArray.getJSONObject(0).toString()).apply {
                    put("type", "LTE")
                    put("radio", "LTE")
                    put("isRegistered", true)
                }
                val syntheticConfig = org.json.JSONObject().put(
                    "cell_json",
                    org.json.JSONArray().put(seed)
                )
                val syntheticLte = buildFakeCellInfoList(classLoader, lat, lng, syntheticConfig)
                if (syntheticLte.isNotEmpty()) {
                    result.add(0, syntheticLte[0])
                    XposedBridge.logOpenCellIdEvery(
                        "buildFakeCellInfoList:synthetic-lte",
                        "OpenCellID data has no LTE/NR cells; prepended synthetic LTE primary cell for 4G-only readers",
                        60_000L
                    )
                }
            } catch (e: Throwable) {
                XposedBridge.logOpenCellId("Failed to prepend synthetic LTE primary cell", e)
            }
        }
        XposedBridge.logOpenCellIdEvery(
            "buildFakeCellInfoList:return:$lteCount:$nrCount:$wcdmaCount:$gsmCount:${result.size}:$firstSummary",
            "buildFakeCellInfoList returning ${result.size} cells from cell_json types=LTE:$lteCount NR:$nrCount WCDMA:$wcdmaCount GSM:$gsmCount first=$firstSummary"
        )
        return result
    }

    val coordSeed = ((lat * 1e5).toLong() xor (lng * 1e5).toLong())
    XposedBridge.logOpenCellIdEvery(
        "buildFakeCellInfoList:fallback:$coordSeed",
        "buildFakeCellInfoList has no cell_json; generating deterministic LTE fallback cells seed=$coordSeed",
        60_000L
    )

    // 中国运营商MCC/MNC组合
    val operators = listOf(
        Pair(460, 0),  // 中国移动
        Pair(460, 1),  // 中国联通
        Pair(460, 11)  // 中国电信
    )

    // 生成2-3个基站(1个服务小区+1-2个邻区)
    val cellCount = 2 + (coordSeed and 1).toInt()
    for (i in 0 until cellCount) {
        try {
            val mcc = operators[i % operators.size].first
            val mnc = operators[i % operators.size].second
            // 每个基站的TAC/CI基于坐标+索引偏移,确保同一位置的多个基站参数不同但确定
            val tac = (10000 + ((coordSeed + i * 7919) and 0xFFFF).toInt() % 50000)
                .coerceIn(1, 65534)
            val ci = (100000 + (((coordSeed shr 8) + i * 104729) and 0xFFFFFF).toInt() % 900000)
                .coerceIn(1, 268435455)
            val pci = (coordSeed + i * 31).toInt() and 0x1FF // 物理小区ID, 0-503

            // 方案A: 通过反射CellIdentityLte构造器(Android 9+有多参数版本)
            val cellIdentityLteClass = XposedHelpers.findClass(
                "android.telephony.CellIdentityLte", classLoader
            )
            val cellInfoLteClass = XposedHelpers.findClass(
                "android.telephony.CellInfoLte", classLoader
            )

            val cellInfo = XposedHelpers.newInstance(cellInfoLteClass)

            // 设置isRegistered: 第一个为服务小区
            try {
                XposedHelpers.setBooleanField(cellInfo, "mRegistered", i == 0)
            } catch (e: Throwable) {
                try {
                    XposedHelpers.callMethod(cellInfo, "setRegistered", i == 0)
                } catch (e2: Throwable) { /* 忽略 */
                }
            }

            // 设置时间戳
            try {
                XposedHelpers.setLongField(
                    cellInfo, "mTimeStamp",
                    android.os.SystemClock.elapsedRealtimeNanos()
                )
            } catch (e: Throwable) { /* 忽略 */
            }

            // 构造CellIdentityLte并注入字段
            val cellIdentity = try {
                // Android 9+ 构造器: (int ci, int pci, int tac, int earfcn, ...mcc, mnc...)
                XposedHelpers.newInstance(
                    cellIdentityLteClass,
                    mcc, mnc, ci, pci, tac
                )
            } catch (e: Throwable) {
                // 降级: 用空构造器+反射写字段
                val identity = XposedHelpers.newInstance(cellIdentityLteClass)
                try {
                    XposedHelpers.setIntField(identity, "mMcc", mcc)
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(identity, "mMnc", mnc)
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(identity, "mMccStr", mcc.toString())
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(
                        identity,
                        "mMncStr",
                        if (mnc < 10) "0$mnc" else mnc.toString()
                    )
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(identity, "mCi", ci)
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(identity, "mPci", pci)
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(identity, "mTac", tac)
                } catch (e2: Throwable) {
                }
                identity
            }

            // 将CellIdentityLte写入CellInfoLte
            try {
                XposedHelpers.setObjectField(cellInfo, "mCellIdentityLte", cellIdentity)
            } catch (e: Throwable) { /* 忽略 */
            }

            // 构造CellSignalStrengthLte
            try {
                val cssClass = XposedHelpers.findClass(
                    "android.telephony.CellSignalStrengthLte", classLoader
                )
                val css = XposedHelpers.newInstance(cssClass)
                // RSRP: -140~-44 dBm, 典型值-80~-100
                val rsrp = -80 - rng.nextInt(20)
                // RSRQ: -20~-3 dB
                val rsrq = -10 - rng.nextInt(7)
                // RSSI: -113~-51 dBm
                val rssi = -70 - rng.nextInt(20)
                try {
                    XposedHelpers.setIntField(css, "mRsrp", rsrp)
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(css, "mRsrq", rsrq)
                } catch (e2: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(css, "mSignalStrength", rssi)
                } catch (e2: Throwable) {
                }
                XposedHelpers.setObjectField(cellInfo, "mCellSignalStrengthLte", css)
            } catch (e: Throwable) { /* 忽略 */
            }

            result.add(cellInfo)
            if (LocationHooker.VERBOSE_CELL_BUILD_LOGS) {
                XposedBridge.logOpenCellId("Fallback LTE cell[$i] built mcc=$mcc mnc=$mnc tac=$tac ci=$ci pci=$pci registered=${i == 0}")
            }
        } catch (e: Throwable) {
            XposedBridge.logOpenCellId("Fallback LTE cell[$i] build failed", e)
        }
    }
    XposedBridge.logOpenCellIdEvery(
        "buildFakeCellInfoList:fallback-return:${result.size}",
        "buildFakeCellInfoList returning ${result.size} fallback LTE cells",
        60_000L
    )
    return result
}

internal fun LocationHooker.normalizeCellType(rawType: String): String {
    return when (rawType.uppercase(java.util.Locale.US)) {
        "GSM" -> "GSM"
        "UMTS", "WCDMA" -> "WCDMA"
        "NR", "NR5G", "5G" -> "NR"
        else -> "LTE"
    }
}

internal fun LocationHooker.cellAreaCode(cell: org.json.JSONObject, default: Int): Int =
    positiveJsonInt(cell, "tac", "lac", "area", default = default)

internal fun LocationHooker.cellIdentityCode(cell: org.json.JSONObject, default: Int): Int =
    positiveJsonInt(cell, "ci", "cid", "cellid", "cell", default = default)

internal fun LocationHooker.fallbackAreaCode(lat: Double, lng: Double): Int {
    val coordSeed = ((lat * 1e5).toLong() xor (lng * 1e5).toLong())
    return (10000 + (coordSeed and 0xFFFF).toInt() % 50000).coerceIn(1, 65534)
}

internal fun LocationHooker.fallbackCellIdentity(lat: Double, lng: Double): Int {
    val coordSeed = ((lat * 1e5).toLong() xor (lng * 1e5).toLong())
    return (100000 + ((coordSeed shr 8) and 0xFFFFFF).toInt() % 900000)
        .coerceIn(1, 268435455)
}

internal fun LocationHooker.positiveJsonInt(
    cell: org.json.JSONObject,
    vararg keys: String,
    default: Int
): Int {
    for (key in keys) {
        if (!cell.has(key) || cell.isNull(key)) continue
        val value = cell.optInt(key, Int.MIN_VALUE)
        if (value > 0) return value
        val parsed = cell.optString(key).toIntOrNull()
        if (parsed != null && parsed > 0) return parsed
    }
    return default
}

internal fun LocationHooker.signalDbm(cell: org.json.JSONObject, index: Int): Int {
    val direct = cell.optInt("dbm", Int.MIN_VALUE)
    if (direct in -140..-40) return direct

    val average = cell.optInt("averageSignalStrength", Int.MIN_VALUE)
    if (average in -140..-40) return average

    val signal = cell.optInt("signal", Int.MIN_VALUE)
    if (signal in -140..-40) return signal

    return (-70 - index * 3).coerceAtLeast(-110)
}

internal fun LocationHooker.firstCell(config: org.json.JSONObject?): org.json.JSONObject? {
    val cells = config?.optJSONArray("cell_json") ?: return null
    return if (cells.length() > 0) cells.optJSONObject(0) else null
}

internal fun LocationHooker.isTelephonyCallbackListener(
    classLoader: ClassLoader,
    callback: Any,
    listenerName: String
): Boolean {
    return LocationHooker.hasTypeByName(
        callback.javaClass,
        "android.telephony.TelephonyCallback\$$listenerName"
    )
}

internal fun LocationHooker.buildFakeServiceState(
    classLoader: ClassLoader,
    cellArray: org.json.JSONArray?
): Any? {
    XposedBridge.logOpenCellIdEvery(
        "buildFakeServiceState:called:${cellArray?.length() ?: 0}",
        "buildFakeServiceState called cellJsonCount=${cellArray?.length() ?: 0}"
    )
    return try {
        val clazz = XposedHelpers.findClass("android.telephony.ServiceState", classLoader)
        val state = XposedHelpers.newInstance(clazz)
        val cell =
            if (cellArray != null && cellArray.length() > 0) cellArray.optJSONObject(0) else null
        val operator = if (cell != null) {
            val mcc = positiveJsonInt(cell, "mcc", default = 460)
            val mnc = positiveJsonInt(cell, "mnc", "net", default = 0)
            String.format(java.util.Locale.US, "%d%02d", mcc, mnc)
        } else {
            "46000"
        }
        val operatorName = when (operator.takeLast(2).toIntOrNull() ?: 0) {
            1, 6, 9 -> "中国联通"
            3, 5, 11 -> "中国电信"
            else -> "中国移动"
        }

        try {
            XposedHelpers.callMethod(state, "setState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(state, "setVoiceRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(state, "setDataRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(
                state,
                "setOperatorName",
                operatorName,
                operatorName,
                operator
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(state, "mVoiceRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(state, "mDataRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaLong", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaShort", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mVoiceOperatorNumeric", operator)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mDataOperatorAlphaLong", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mDataOperatorAlphaShort", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mDataOperatorNumeric", operator)
        } catch (_: Throwable) {
        }
        XposedBridge.logOpenCellIdEvery(
            "buildFakeServiceState:success:$operator:$operatorName",
            "buildFakeServiceState success operator=$operator operatorName=$operatorName"
        )
        state
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("buildFakeServiceState failed", e)
        null
    }
}

internal fun LocationHooker.buildFakeSignalStrength(
    classLoader: ClassLoader,
    config: org.json.JSONObject?
): Any? {
    val cellCount = config?.optJSONArray("cell_json")?.length() ?: 0
    XposedBridge.logOpenCellIdEvery(
        "buildFakeSignalStrength:called:$cellCount",
        "buildFakeSignalStrength called cellJsonCount=$cellCount"
    )
    return try {
        val clazz = XposedHelpers.findClass("android.telephony.SignalStrength", classLoader)
        val signalStrength = XposedHelpers.newInstance(clazz)
        val dbm = signalDbm(firstCell(config) ?: org.json.JSONObject(), 0)
        val lteSignalClass =
            XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)
        val lteSignal = XposedHelpers.newInstance(lteSignalClass)
        try {
            XposedHelpers.setIntField(lteSignal, "mRsrp", dbm)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(lteSignal, "mRsrq", -10)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(lteSignal, "mRssnr", 300)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(lteSignal, "mSignalStrength", dbm + 113)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(signalStrength, "mLte", lteSignal)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(
                signalStrength,
                "mCellSignalStrengths",
                listOf(lteSignal)
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setBooleanField(signalStrength, "mLteAsPrimaryInNrNsa", true)
        } catch (_: Throwable) {
        }
        XposedBridge.logOpenCellIdEvery(
            "buildFakeSignalStrength:success:$dbm",
            "buildFakeSignalStrength success dbm=$dbm"
        )
        signalStrength
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("buildFakeSignalStrength failed", e)
        null
    }
}

/**
 * 构造 CellIdentity 派生类，完整兼容 Android 9 ~ Android 14+。
 * 按以下优先级尝试：
 *   1. 各 Android 版本已知的有参构造器（最优，字段由构造器写入）
 *   2. sun.misc.Unsafe.allocateInstance + 反射写字段
 *      （字段初始值为 0 而非 MAX_VALUE，避免 JIT 内联问题）
 *   3. 最小参数构造器 + 默认值填充（最后手段）
 */
internal fun LocationHooker.constructCellIdentityByType(
    type: String,
    clazz: Class<*>,
    mcc: Int, mccStr: String,
    mnc: Int, mncStr: String,
    tacOrLac: Int, ciOrCid: Int, pci: Int
): Any {
    val ctors = clazz.declaredConstructors.onEach { it.isAccessible = true }

    // 按参数个数匹配构造器，调用失败则返回 null
    fun tryNewInstance(vararg args: Any?): Any? = ctors
        .firstOrNull { it.parameterCount == args.size }
        ?.runCatching { newInstance(*args) }
        ?.getOrNull()

    // 阶段一：尝试各版本有参构造器
    val identity: Any? = when (type) {
        "LTE" -> {
            // Android 9 / API 28: (int mcc, int mnc, int ci, int pci, int tac) — 5 参数
            tryNewInstance(mcc, mnc, ciOrCid, pci, tacOrLac)
            // Android 10 / API 29: (int ci, int pci, int tac, int earfcn, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort) — 9 参数
                ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: (int ci, int pci, int tac, int earfcn, int[] bands, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort, Collection, ClosedSubscriberGroupInfo) — 12 参数
                ?: tryNewInstance(
                    ciOrCid,
                    pci,
                    tacOrLac,
                    0,
                    IntArray(0),
                    0,
                    mccStr,
                    mncStr,
                    "",
                    "",
                    emptyList<Any>(),
                    null
                )
        }

        "GSM" -> {
            // Android 9 / API 28: (int mcc, int mnc, int lac, int cid) — 4 参数
            tryNewInstance(mcc, mnc, tacOrLac, ciOrCid)
            // Android 9 / API 28: (int mcc, int mnc, int lac, int cid, int arfcn, int bsic) — 6 参数
                ?: tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, 0, 0)
                // Android 10 / API 29: (int lac, int cid, int arfcn, int bsic, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: 10 参数
                ?: tryNewInstance(
                    tacOrLac,
                    ciOrCid,
                    0,
                    0,
                    mccStr,
                    mncStr,
                    "",
                    "",
                    emptyList<Any>(),
                    null
                )
        }

        "WCDMA", "UMTS" -> {
            // Android 9: (int mcc, int mnc, int lac, int cid, int psc, int uarfcn) — 6 参数
            tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, pci, 0)
            // Android 10: (int lac, int cid, int psc, int uarfcn, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "")
                // Android 11+: 10 参数
                ?: tryNewInstance(
                    tacOrLac,
                    ciOrCid,
                    pci,
                    0,
                    mccStr,
                    mncStr,
                    "",
                    "",
                    emptyList<Any>(),
                    null
                )
        }

        "NR" -> {
            // Android 11+: (int pci, int tac, long nci, int[] bands, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
            tryNewInstance(pci, tacOrLac, ciOrCid.toLong(), IntArray(0), mccStr, mncStr, "", "")
            // Android 12+: 10 参数
                ?: tryNewInstance(
                    pci,
                    tacOrLac,
                    ciOrCid.toLong(),
                    IntArray(0),
                    mccStr,
                    mncStr,
                    "",
                    "",
                    emptyList<Any>(),
                    null
                )
        }

        else -> null
    }

    if (identity != null) return identity

    // 阶段二：Unsafe.allocateInstance + 反射写字段
    // 字段初始值为 0（非 MAX_VALUE），避免 JIT 内联问题
    try {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val obj = allocate.invoke(unsafe, clazz) as Any

        // 设置类型标识（CellIdentity.mType）
        val typeInt = when (type) {
            "GSM" -> 1; "LTE" -> 3; "WCDMA", "UMTS" -> 4; "NR" -> 6; else -> 3
        }
        try {
            XposedHelpers.setIntField(obj, "mType", typeInt)
        } catch (_: Throwable) {
        }
        // MCC/MNC（Int 版 Android 9，String 版 Android 10+）
        try {
            XposedHelpers.setIntField(obj, "mMcc", mcc)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(obj, "mMnc", mnc)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mMccStr", mccStr)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mMncStr", mncStr)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mAlphaLong", "")
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mAlphaShort", "")
        } catch (_: Throwable) {
        }

        when (type) {
            "LTE" -> {
                try {
                    XposedHelpers.setIntField(obj, "mCi", ciOrCid)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mTac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mPci", pci)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(obj, "mBands", IntArray(0))
                } catch (_: Throwable) {
                }
            }

            "GSM" -> {
                try {
                    XposedHelpers.setIntField(obj, "mLac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mCid", ciOrCid)
                } catch (_: Throwable) {
                }
            }

            "WCDMA", "UMTS" -> {
                try {
                    XposedHelpers.setIntField(obj, "mLac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mCid", ciOrCid)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mPsc", pci)
                } catch (_: Throwable) {
                }
            }

            "NR" -> {
                try {
                    XposedHelpers.setIntField(obj, "mTac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setLongField(obj, "mNci", ciOrCid.toLong())
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mPci", pci)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(obj, "mBands", IntArray(0))
                } catch (_: Throwable) {
                }
            }
        }
        XposedBridge.log("[LocationSpoofer][CellMock] Unsafe.allocateInstance succeeded for $type: CI=$ciOrCid, TAC=$tacOrLac")
        return obj
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer][CellMock] Unsafe failed for $type: $e")
    }

    // 阶段三：最小参数构造器 + 安全默认值填充（绝对保底）
    val minCtor = ctors.minByOrNull { it.parameterCount }
        ?: throw IllegalStateException("No constructors for ${clazz.name}")
    val safeArgs = minCtor.parameterTypes.map { t ->
        when {
            t == Int::class.javaPrimitiveType -> 0
            t == Long::class.javaPrimitiveType -> 0L
            t == Boolean::class.javaPrimitiveType -> false
            t == Float::class.javaPrimitiveType -> 0f
            t == Double::class.javaPrimitiveType -> 0.0
            t == IntArray::class.java -> IntArray(0)
            t == java.util.Collection::class.java || t.isAssignableFrom(java.util.ArrayList::class.java) -> emptyList<Any>()
            else -> null
        }
    }.toTypedArray()
    val fallbackObj = try {
        minCtor.newInstance(*safeArgs)
    } catch (e: Throwable) {
        throw IllegalStateException("Cannot construct ${clazz.name}: $e")
    }
    // 写字段
    try {
        XposedHelpers.setIntField(fallbackObj, "mMcc", mcc)
    } catch (_: Throwable) {
    }
    try {
        XposedHelpers.setIntField(fallbackObj, "mMnc", mnc)
    } catch (_: Throwable) {
    }
    try {
        XposedHelpers.setObjectField(fallbackObj, "mMccStr", mccStr)
    } catch (_: Throwable) {
    }
    try {
        XposedHelpers.setObjectField(fallbackObj, "mMncStr", mncStr)
    } catch (_: Throwable) {
    }
    when (type) {
        "LTE" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mCi", ciOrCid)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mPci", pci)
            } catch (_: Throwable) {
            }
        }

        "GSM" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid)
            } catch (_: Throwable) {
            }
        }

        "WCDMA", "UMTS" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mPsc", pci)
            } catch (_: Throwable) {
            }
        }

        "NR" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setLongField(fallbackObj, "mNci", ciOrCid.toLong())
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mPci", pci)
            } catch (_: Throwable) {
            }
        }
    }
    XposedBridge.log("[LocationSpoofer][CellMock] MinCtor fallback used for $type identity")
    return fallbackObj
}
