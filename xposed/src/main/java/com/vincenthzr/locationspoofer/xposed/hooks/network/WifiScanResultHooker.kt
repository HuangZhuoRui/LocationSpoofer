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

package com.vincenthzr.locationspoofer.xposed.hooks.network

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.*
import com.vincenthzr.locationspoofer.xposed.hooks.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Array as ReflectArray
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * Wi-Fi 环境伪造模块入口，以及 WifiManager.getScanResults() 扫描结果伪造。
 */
internal fun LocationHooker.hookWifiEnvironment(
    classLoader: ClassLoader,
    isCoreSystemProcess: Boolean = false
) {
    hookWifiScanResults(classLoader)
    hookWifiConnectionInfo(classLoader, isCoreSystemProcess)
    hookWifiState(classLoader, isCoreSystemProcess)
}

internal fun LocationHooker.hookWifiScanResults(classLoader: ClassLoader) {
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

    // Wi-Fi 扫描结果伪造 (getScanResults)
    try {
        XposedHelpers.hookMethod(
            "android.net.wifi.WifiManager", classLoader, "getScanResults"
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            val fakeList = java.util.ArrayList<Any>()
            val mockWifi = config.optBoolean("mock_wifi", true)
            val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
            if (mockWifi) {
                try {
                    val scanResultClass =
                        XposedHelpers.findClass("android.net.wifi.ScanResult", classLoader)
                    val baseTimestamp = android.os.SystemClock.elapsedRealtimeNanos()

                    fun addFakeScanResult(wifi: org.json.JSONObject) {
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
                        XposedHelpers.setIntField(
                            fakeScanResult, "frequency",
                            wifi.optInt("frequency", 2412)
                        )
                        XposedHelpers.setObjectField(
                            fakeScanResult, "capabilities",
                            wifi.optString("capabilities", realCapabilities.random())
                        )
                        // 适配 ColorOS / OxygenOS / HyperOS: 必须初始化 informationElements 和 radioChainInfos，否则 ColorOS OplusWifiScanStatistics 会报 NPE 导致 system_server 崩溃重启进入安全模式
                        try {
                            val ieClass = XposedHelpers.findClassIfExists(
                                "android.net.wifi.ScanResult\$InformationElement",
                                classLoader
                            )
                            if (ieClass != null) {
                                val emptyIeArray = ReflectArray.newInstance(ieClass, 0)
                                XposedHelpers.setObjectField(fakeScanResult, "informationElements", emptyIeArray)
                            }
                        } catch (e: Throwable) {
                        }
                        try {
                            val rciClass = XposedHelpers.findClassIfExists(
                                "android.net.wifi.ScanResult\$RadioChainInfo",
                                classLoader
                            )
                            if (rciClass != null) {
                                val emptyRciArray = ReflectArray.newInstance(rciClass, 0)
                                XposedHelpers.setObjectField(fakeScanResult, "radioChainInfos", emptyRciArray)
                            }
                        } catch (e: Throwable) {
                        }
                        try {
                            val offsetNanos = (rng.nextInt(200_000) * 1000L)
                            XposedHelpers.setLongField(
                                fakeScanResult, "timestamp",
                                (baseTimestamp - offsetNanos) / 1000
                            )
                        } catch (e: Throwable) {
                        }
                        fakeList.add(fakeScanResult)
                    }

                    if (wifiObj != null) {
                        val isConnected = wifiObj.optBoolean("isConnected", false)
                        val connectedWifi =
                            if (isConnected) wifiObj.optJSONObject("connectedWifi") else null
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

                    if (fakeList.isEmpty()) {
                        val lat = config.optDouble("lat", 0.0)
                        val lng = config.optDouble("lng", 0.0)
                        val seed = ((lat * 100000).toLong() xor (lng * 100000).toLong())
                        val random = java.util.Random(seed)
                        for (i in 0 until 5) {
                            val fakeWifi = org.json.JSONObject()
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
                } catch (e: Throwable) { /* 忽略 */
                }
            }
            return@hookMethod fakeList
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }
}
