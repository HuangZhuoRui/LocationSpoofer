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
import com.suseoaa.locationspoofer.xposed.utils.XposedBridge
import com.suseoaa.locationspoofer.xposed.utils.XposedHelpers
import org.json.JSONObject
import java.lang.reflect.Array as ReflectArray
import java.util.Random

/**
 * system_server 级 Wi-Fi 服务（WifiServiceImpl）拦截与伪造模块
 *
 * 核心原理：
 * 全设备所有应用调用 WifiManager.getScanResults() 与 WifiManager.getConnectionInfo()
 * 都会通过 IWifiManager 远程 IPC 调用至 system_server 中的 WifiServiceImpl。
 *
 * 在此处根据调用方包名拦截，向目标应用返回伪造的 Wi-Fi 扫描热点列表与虚假连接信息，
 * 同时安全初始化 HyperOS / ColorOS 厂商统计服务所需的内部结构，杜绝系统崩溃。
 */

internal fun LocationHooker.hookSystemWifiService(classLoader: ClassLoader) {
    val wifiServiceClass = XposedHelpers.findClassIfExists(
        "com.android.server.wifi.WifiServiceImpl", classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.server.WifiService", classLoader
    )

    if (wifiServiceClass == null) {
        XposedBridge.log("[LocationSpoofer][SysWifi] WifiServiceImpl not found, skipping system Wi-Fi hook")
        return
    }

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
        XposedHelpers.hookAllMethods(wifiServiceClass, "getScanResults") { chain, _ ->
            val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean("active", false)) {
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }

            val explicitPkg = SystemHookUtils.extractPackageName(chain.args)
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

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

                XposedBridge.logOpenCellIdEvery(
                    "sys_wifi_scan_${explicitPkg ?: "global"}",
                    "[SysWifi] Dispatched ${fakeList.size} fake scan results to $explicitPkg"
                )
                return@hookAllMethods fakeList
            } catch (e: Throwable) {
                XposedBridge.log("[LocationSpoofer][SysWifi] build fake scan results failed: $e")
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        }
        XposedBridge.log("[LocationSpoofer][SysWifi] WifiServiceImpl.getScanResults hooked")
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer][SysWifi] hook getScanResults failed: $e")
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
            val isTarget = SystemHookUtils.isTargetCaller(chain.thisObject, explicitPkg, config)

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

            val currentResult = chain.proceed(chain.args.toTypedArray())
            if (isConnected && connectedWifi != null && currentResult != null) {
                try {
                    val ssidVal = connectedWifi.optString("ssid", "")
                    val finalSsid = if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") "HOME_WIFI" else ssidVal
                    val bssidVal = connectedWifi.optString("bssid", "02:00:00:00:00:00")
                    val freqVal = connectedWifi.optInt("frequency", 2412)
                    val macAddressVal = connectedWifi.optString("macAddress", bssidVal)
                    val linkSpeedVal = connectedWifi.optInt("linkSpeed", 65)
                    val levelVal = connectedWifi.optInt("level", -65)
                    val networkIdVal = connectedWifi.optInt("networkId", 1)

                    try { XposedHelpers.setObjectField(currentResult, "mSSID", "\"$finalSsid\"") } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(currentResult, "mBSSID", bssidVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setObjectField(currentResult, "mMacAddress", macAddressVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mRssi", levelVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mLinkSpeed", linkSpeedVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mFrequency", freqVal) } catch (_: Throwable) {}
                    try { XposedHelpers.setIntField(currentResult, "mNetworkId", networkIdVal) } catch (_: Throwable) {}

                    XposedBridge.logOpenCellIdEvery(
                        "sys_wifi_conn_${explicitPkg ?: "global"}",
                        "[SysWifi] Injected fake connection info for $explicitPkg (SSID=$finalSsid, BSSID=$bssidVal)"
                    )
                } catch (e: Throwable) {
                    XposedBridge.log("[LocationSpoofer][SysWifi] modify WifiInfo in-place failed: $e")
                }
            }
            return@hookAllMethods currentResult
        }
        XposedBridge.log("[LocationSpoofer][SysWifi] WifiServiceImpl.getConnectionInfo hooked")
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer][SysWifi] hook getConnectionInfo failed: $e")
    }
}
