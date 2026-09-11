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
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Array as ReflectArray
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * WifiInfo 相关 getter（BSSID/SSID/RSSI 等）与 WifiManager.getConnectionInfo() 伪造。
 */
internal fun LocationHooker.hookWifiConnectionInfo(
    classLoader: ClassLoader,
    isCoreSystemProcess: Boolean = false
) {

    // 1. WifiInfo getter Hook
    try {
        val wifiInfoMethods = listOf(
            "getBSSID", "getMacAddress", "getSSID", "getNetworkId",
            "getRssi", "getLinkSpeed", "getFrequency", "getIpAddress"
        )
        for (method in wifiInfoMethods) {
            try {
                XposedHelpers.hookMethod(
                    "android.net.wifi.WifiInfo", classLoader, method
                ) { chain, method ->
                    val config =
                        readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
                    if (!config.optBoolean(
                            "active",
                            false
                        )
                    ) return@hookMethod chain.proceed(chain.args.toTypedArray())
                    val mockWifi = config.optBoolean("mock_wifi", true)
                    val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
                    val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
                    val connectedWifi =
                        if (isConnected) wifiObj!!.optJSONObject("connectedWifi") else null

                    when (method.name) {
                        "getBSSID" -> return@hookMethod connectedWifi?.optString("bssid")
                            ?: "02:00:00:00:00:00"

                        "getMacAddress" -> return@hookMethod connectedWifi?.optString("macAddress")
                            ?: "02:00:00:00:00:00"

                        "getSSID" -> {
                            val ssidVal = connectedWifi?.optString("ssid", "") ?: ""
                            val finalSsid =
                                if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") "" else ssidVal
                            return@hookMethod if (finalSsid.isEmpty()) "<unknown ssid>" else "\"$finalSsid\""
                        }

                        "getNetworkId" -> return@hookMethod connectedWifi?.optInt("networkId", -1)
                            ?: -1

                        "getRssi" -> return@hookMethod connectedWifi?.optInt("level", -127) ?: -127

                        "getLinkSpeed" -> return@hookMethod connectedWifi?.optInt("linkSpeed", -1)
                            ?: -1

                        "getFrequency" -> return@hookMethod connectedWifi?.optInt("frequency", -1)
                            ?: -1

                        "getIpAddress" -> return@hookMethod if (isConnected) 0x6401A8C0 else 0 // 192.168.1.100 小端序
                    }
                    return@hookMethod chain.proceed(chain.args.toTypedArray())
                }
            } catch (e: Throwable) { /* 部分方法在低版本可能不存在 */
            }
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }

    // 1b. WifiInfo.getSupplicantState()
    try {
        XposedHelpers.hookMethod(
            "android.net.wifi.WifiInfo", classLoader, "getSupplicantState"
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            val mockWifi = config.optBoolean("mock_wifi", true)
            val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
            val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
            try {
                val enumClass = XposedHelpers.findClass(
                    "android.net.wifi.SupplicantState", classLoader
                )
                val stateStr =
                    if (mockWifi && isConnected) "COMPLETED" else "DISCONNECTED"
                return@hookMethod enumClass.getField(stateStr).get(null)
            } catch (e: Throwable) { /* 忽略 */
            }
            return@hookMethod chain.proceed(chain.args.toTypedArray())
        }
    } catch (e: Throwable) { /* 忽略 */
    }

    // getConnectionInfo() — 返回伪造的 WifiInfo 对象
    try {
        XposedHelpers.hookMethod(
            "android.net.wifi.WifiManager", classLoader, "getConnectionInfo"
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            val mockWifi = config.optBoolean("mock_wifi", true)
            val wifiObj = if (mockWifi) config.optJSONObject("wifi_json") else null
            val isConnected = wifiObj?.optBoolean("isConnected", false) ?: false
            val connectedWifi =
                if (isConnected) wifiObj!!.optJSONObject("connectedWifi") else null

            val currentResult = chain.proceed(chain.args.toTypedArray())
            if (isConnected && connectedWifi != null) {
                try {
                    val ssidVal = connectedWifi.optString("ssid", "")
                    val finalSsid =
                        if (ssidVal.isEmpty() || ssidVal == "<unknown ssid>") "HOME_WIFI" else ssidVal
                    val bssidVal = connectedWifi.optString("bssid", "02:00:00:00:00:00")
                    val freqVal = connectedWifi.optInt("frequency", 2412)
                    val macAddressVal = connectedWifi.optString("macAddress", bssidVal)
                    val linkSpeedVal = connectedWifi.optInt("linkSpeed", 65)
                    val standardVal = connectedWifi.optInt("wifiStandard", 6)
                    val levelVal = connectedWifi.optInt("level", -65)
                    val networkIdVal = connectedWifi.optInt("networkId", 1)

                    if (currentResult != null) {
                        // 就地修改返回值以避免在 ColorOS 的 system_server 中发生 ClassCastException
                        try {
                            XposedHelpers.setObjectField(
                                currentResult,
                                "mSSID",
                                "\"$finalSsid\""
                            )
                        } catch (e: Throwable) {
                        }
                        try {
                            val wifiSsidClass = XposedHelpers.findClassIfExists(
                                "android.net.wifi.WifiSsid",
                                classLoader
                            )
                            if (wifiSsidClass != null) {
                                val createMethod = XposedHelpers.findMethodExact(
                                    wifiSsidClass,
                                    "createFromAsciiEncoded",
                                    String::class.java
                                )
                                val wifiSsid = createMethod.invoke(null, finalSsid)
                                XposedHelpers.setObjectField(
                                    currentResult,
                                    "mWifiSsid",
                                    wifiSsid
                                )
                            }
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setObjectField(currentResult, "mBSSID", bssidVal)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setObjectField(
                                currentResult,
                                "mMacAddress",
                                macAddressVal
                            )
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(currentResult, "mRssi", levelVal)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(currentResult, "mFrequency", freqVal)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(
                                currentResult,
                                "mLinkSpeed",
                                linkSpeedVal
                            )
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(
                                currentResult,
                                "mNetworkId",
                                networkIdVal
                            )
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(
                                currentResult,
                                "mWifiStandard",
                                standardVal
                            )
                        } catch (e: Throwable) {
                        }
                    } else {
                        if (isCoreSystemProcess) return@hookMethod currentResult

                        var builtWithBuilder = false
                        var builtInfo: Any? = null
                        try {
                            val builderClass = XposedHelpers.findClass(
                                "android.net.wifi.WifiInfo\$Builder",
                                classLoader
                            )
                            val builder = XposedHelpers.newInstance(builderClass)
                            XposedHelpers.callMethod(builder, "setSsid", finalSsid)
                            XposedHelpers.callMethod(builder, "setBssid", bssidVal)
                            XposedHelpers.callMethod(builder, "setRssi", levelVal)
                            XposedHelpers.callMethod(builder, "setFrequency", freqVal)
                            XposedHelpers.callMethod(builder, "setLinkSpeed", linkSpeedVal)
                            builtInfo = XposedHelpers.callMethod(builder, "build")
                            builtWithBuilder = true
                        } catch (e: Throwable) {
                        }

                        val fakeWifiInfo = if (builtWithBuilder) {
                            builtInfo!!
                        } else {
                            val wifiInfoClass = XposedHelpers.findClass(
                                "android.net.wifi.WifiInfo",
                                classLoader
                            )
                            val info = XposedHelpers.newInstance(wifiInfoClass)
                            try {
                                XposedHelpers.setObjectField(
                                    info,
                                    "mSSID",
                                    "\"$finalSsid\""
                                )
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setObjectField(info, "mBSSID", bssidVal)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setObjectField(
                                    info,
                                    "mMacAddress",
                                    macAddressVal
                                )
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setIntField(info, "mRssi", levelVal)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setIntField(info, "mFrequency", freqVal)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setIntField(info, "mLinkSpeed", linkSpeedVal)
                            } catch (e: Throwable) {
                            }
                            try {
                                XposedHelpers.setIntField(info, "mNetworkId", networkIdVal)
                            } catch (e: Throwable) {
                            }
                            info
                        }
                        return@hookMethod fakeWifiInfo
                    }
                } catch (e: Throwable) {
                }
            } else {
                if (currentResult != null) {
                    try {
                        XposedHelpers.setObjectField(
                            currentResult,
                            "mBSSID",
                            "02:00:00:00:00:00"
                        )
                    } catch (e: Throwable) {
                    }
                    try {
                        XposedHelpers.setObjectField(
                            currentResult,
                            "mMacAddress",
                            "02:00:00:00:00:00"
                        )
                    } catch (e: Throwable) {
                    }
                    try {
                        XposedHelpers.setIntField(currentResult, "mNetworkId", -1)
                    } catch (e: Throwable) {
                    }
                    try {
                        XposedHelpers.setIntField(currentResult, "mRssi", -127)
                    } catch (e: Throwable) {
                    }
                    try {
                        XposedHelpers.setIntField(currentResult, "mLinkSpeed", -1)
                    } catch (e: Throwable) {
                    }
                    try {
                        XposedHelpers.setIntField(currentResult, "mFrequency", -1)
                    } catch (e: Throwable) {
                    }
                } else {
                    if (isCoreSystemProcess) return@hookMethod currentResult
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
                                "02:00:00:00:00:00"
                            )
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setObjectField(
                                fakeWifiInfo,
                                "mMacAddress",
                                "02:00:00:00:00:00"
                            )
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(fakeWifiInfo, "mNetworkId", -1)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(fakeWifiInfo, "mRssi", -127)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(fakeWifiInfo, "mLinkSpeed", -1)
                        } catch (e: Throwable) {
                        }
                        try {
                            XposedHelpers.setIntField(fakeWifiInfo, "mFrequency", -1)
                        } catch (e: Throwable) {
                        }
                        return@hookMethod fakeWifiInfo
                    } catch (e: Throwable) {
                    }
                }
            }
            return@hookMethod currentResult
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }
}
