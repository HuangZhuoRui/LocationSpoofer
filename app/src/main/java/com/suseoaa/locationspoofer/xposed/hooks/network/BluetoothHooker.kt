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
 * 蓝牙环境伪造模块 (Bluetooth LE Hooker)
 * 
 * 上下文:
 * 蓝牙信标 (Bluetooth Beacons) 也是室内定位的重要手段之一。
 * 
 * 作用:
 * 拦截低功耗蓝牙扫描 `BluetoothAdapter.startLeScan` 等接口，清空或篡改扫描结果，
 * 防止真实的蓝牙信标暴露物理位置。
 */

internal fun LocationHooker.hookBluetoothLE(
    classLoader: ClassLoader,
    isCoreSystemProcess: Boolean = false
) {
    if (isCoreSystemProcess) {
        XposedBridge.log("[LocationSpoofer] Skipping Bluetooth LE hooks in core system process")
        return
    }

    // BLE 扫描结果伪造的核心逻辑（复用于不同 startScan 重载）
    val buildAndDeliverBleResults = fun(config: JSONObject, callbackObj: Any, cl: ClassLoader) {
        if (!config.optBoolean("mock_bluetooth", true)) return
        try {
            val bluetoothArray = config.optJSONArray("bluetooth_json")
            if (bluetoothArray != null && bluetoothArray.length() > 0) {
                val results = java.util.ArrayList<Any>()
                val scanResultClass =
                    XposedHelpers.findClass("android.bluetooth.le.ScanResult", cl)
                val bluetoothDeviceClass =
                    XposedHelpers.findClass("android.bluetooth.BluetoothDevice", cl)
                val scanRecordClass =
                    XposedHelpers.findClass("android.bluetooth.le.ScanRecord", cl)

                for (i in 0 until bluetoothArray.length()) {
                    try {
                        val obj = bluetoothArray.getJSONObject(i)
                        val address = obj.optString("address", "00:11:22:33:44:55")
                        val rssi = obj.optInt("rssi", -60)
                        val hexRecord = obj.optString("scanRecordHex", "")

                        // 1. 构造 BluetoothDevice
                        val device = XposedHelpers.newInstance(bluetoothDeviceClass, address)

                        // 2. 构造 ScanRecord
                        var scanRecord: Any? = null
                        if (hexRecord.isNotEmpty()) {
                            try {
                                val bytes = hexStringToByteArray(hexRecord)
                                scanRecord = XposedHelpers.callStaticMethod(
                                    scanRecordClass,
                                    "parseFromBytes",
                                    bytes
                                )
                            } catch (e: Throwable) { /* 忽略 */
                            }
                        }

                        // 3. 构造 ScanResult（兼容新旧构造器）
                        val timestampNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        var scanResultObj: Any? = null
                        try {
                            // Android 8.0+ 构造器
                            scanResultObj = XposedHelpers.newInstance(
                                scanResultClass, device,
                                0x001B, 1, 0, 255, 127, rssi, 0, scanRecord, timestampNanos
                            )
                        } catch (e: Throwable) {
                            try {
                                // 旧版本构造器
                                scanResultObj = XposedHelpers.newInstance(
                                    scanResultClass, device, scanRecord, rssi, timestampNanos
                                )
                            } catch (e2: Throwable) { /* 忽略 */
                            }
                        }

                        if (scanResultObj != null) {
                            results.add(scanResultObj)
                            try {
                                XposedHelpers.callMethod(
                                    callbackObj,
                                    "onScanResult",
                                    1,
                                    scanResultObj
                                )
                            } catch (e: Throwable) {
                            }
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("[LocationSpoofer] 构建虚拟BLE失败: $e")
                    }
                }

                // 批量触发回调
                if (results.isNotEmpty()) {
                    try {
                        XposedHelpers.callMethod(callbackObj, "onBatchScanResults", results)
                    } catch (e: Throwable) {
                    }
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
        }
    }

    // 1. startScan(List<ScanFilter>, ScanSettings, ScanCallback) — 3参数重载
    try {
        XposedHelpers.hookMethod(
            "android.bluetooth.le.BluetoothLeScanner", classLoader, "startScan",
            java.util.List::class.java,
            android.bluetooth.le.ScanSettings::class.java,
            android.bluetooth.le.ScanCallback::class.java
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            val callback = chain.args[2] ?: return@hookMethod null
            buildAndDeliverBleResults(config, callback, classLoader)
            return@hookMethod null
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }

    // 2. startScan(ScanCallback) — 1参数重载
    // 部分 App（如微信）使用无 filter 的简化版 startScan
    try {
        XposedHelpers.hookMethod(
            "android.bluetooth.le.BluetoothLeScanner", classLoader, "startScan",
            android.bluetooth.le.ScanCallback::class.java
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            val callback = chain.args[0] ?: return@hookMethod null
            buildAndDeliverBleResults(config, callback, classLoader)
            return@hookMethod null
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }


    try {
        XposedHelpers.hookMethod(
            "android.bluetooth.BluetoothAdapter", classLoader, "isEnabled"
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean("mock_bluetooth", true)) {
                return@hookMethod false
            }
            return@hookMethod chain.proceed(chain.args.toTypedArray())
        }
        XposedHelpers.hookMethod(
            "android.bluetooth.BluetoothAdapter", classLoader, "getState"
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean("mock_bluetooth", true)) {
                return@hookMethod 10 // 关闭状态 (STATE_OFF)
            }
            return@hookMethod chain.proceed(chain.args.toTypedArray())
        }
    } catch (e: Throwable) { /* 忽略 */
    }

    // 3. BluetoothAdapter.getBondedDevices() → 空集合
    // 防止通过已配对蓝牙设备列表进行指纹识别
    try {
        XposedHelpers.hookMethod(
            "android.bluetooth.BluetoothAdapter", classLoader, "getBondedDevices"
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            val bondedSet = java.util.HashSet<Any>()
            try {
                val bluetoothArray = config.optJSONArray("bluetooth_json")
                if (bluetoothArray != null && bluetoothArray.length() > 0) {
                    val bluetoothDeviceClass = XposedHelpers.findClass(
                        "android.bluetooth.BluetoothDevice",
                        classLoader
                    )
                    for (i in 0 until bluetoothArray.length()) {
                        val obj = bluetoothArray.getJSONObject(i)
                        if (obj.optBoolean("isConnected", false)) {
                            val address = obj.optString("address", "00:00:00:00:00:00")
                            val device =
                                XposedHelpers.newInstance(bluetoothDeviceClass, address)
                            bondedSet.add(device)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return@hookMethod bondedSet
        }
    } catch (e: Throwable) { /* 忽略 */
    }

    // 4. BluetoothAdapter.startDiscovery() → false
    // 阻止经典蓝牙扫描发现周围真实设备
    try {
        XposedHelpers.hookMethod(
            "android.bluetooth.BluetoothAdapter", classLoader, "startDiscovery"
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            return@hookMethod false
        }
    } catch (e: Throwable) { /* 忽略 */
    }

    // 5. 老接口 BluetoothAdapter.startLeScan（Android 4.x）
    try {
        XposedHelpers.hookMethod(
            "android.bluetooth.BluetoothAdapter", classLoader, "startLeScan",
            android.bluetooth.BluetoothAdapter.LeScanCallback::class.java
        ) { chain, method ->
            val config = readConfig() ?: return@hookMethod chain.proceed(chain.args.toTypedArray())
            if (!config.optBoolean(
                    "active",
                    false
                )
            ) return@hookMethod chain.proceed(chain.args.toTypedArray())
            // 老接口不具备很好的伪造性，直接返回启动失败
            return@hookMethod false
        }
    } catch (e: Throwable) {
        XposedBridge.log(e)
    }

    XposedBridge.log("[LocationSpoofer] Bluetooth LE hooks installed")
}

internal fun LocationHooker.hexStringToByteArray(s: String): ByteArray {
    val len = s.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] =
            ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
