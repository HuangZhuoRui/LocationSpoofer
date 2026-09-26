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

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.*
import org.json.JSONObject
import kotlin.random.Random
import io.github.libxposed.api.*

/**
 * 蓝牙 LE 扫描环境伪造模块入口，以及扫描结果的回调派发/定时器。
 */
internal fun LocationHooker.hookBluetoothLE(
    classLoader: ClassLoader,
    isCoreSystemProcess: Boolean = false
) {
    if (isCoreSystemProcess) {
        XposedBridge.log("[LocationSpoofer] Skipping Bluetooth LE hooks in core system process")
        return
    }

    val mainHandler = Handler(Looper.getMainLooper())
    val scanRecordClass = XposedHelpers.findClassIfExists("android.bluetooth.le.ScanRecord", classLoader)


    // 1. Hook BluetoothLeScanner 的所有 startScan / startScanFromSource 重载
    val leScannerClass = XposedHelpers.findClassIfExists("android.bluetooth.le.BluetoothLeScanner", classLoader)
    if (leScannerClass != null) {
        val scanMethodNames = arrayOf("startScan", "startScanFromSource")
        for (scanName in scanMethodNames) {
            try {
                XposedHelpers.hookAllMethods(leScannerClass, scanName) { chain, method ->
                    XposedBridge.log("[LocationSpoofer] 捕获到 BluetoothLeScanner.$scanName: args=${chain.args.map { it?.javaClass?.simpleName }}")
                    var config = readConfig()
                    if (config == null) {
                        config = loadConfigFromDisk("startScan_direct")
                    }
                    if (config == null || !config.optBoolean("active", false) || !config.optBoolean("mock_bluetooth", true)) {
                        XposedBridge.log("[LocationSpoofer] BluetoothLeScanner.$scanName 放行原生系统 (active/mock_bt 为 false)")
                        return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                    }
                    val callback = chain.args.firstOrNull { isScanCallback(it) }
                        ?: chain.args.lastOrNull {
                            it != null && it !is List<*> && it !is java.util.Collection<*> &&
                                    !it.javaClass.name.contains("ScanFilter") &&
                                    !it.javaClass.name.contains("ScanSettings") &&
                                    !it.javaClass.name.contains("PendingIntent") &&
                                    !it.javaClass.name.contains("WorkSource")
                        }
                    if (callback != null) {
                        XposedBridge.log("[LocationSpoofer] 成功拦截 $scanName, 目标回调=${callback.javaClass.name}, 启动心跳分发")
                        startBleTimer(callback, classLoader)
                    } else {
                        XposedBridge.log("[LocationSpoofer] $scanName 未找到匹配的 ScanCallback, args=${chain.args.map { it?.javaClass?.name }}")
                    }
                    if (method is java.lang.reflect.Method && method.returnType == Int::class.javaPrimitiveType) {
                        return@hookAllMethods 0
                    }
                    return@hookAllMethods null
                }
            } catch (e: Throwable) {
                XposedBridge.log("[LocationSpoofer] Hook BluetoothLeScanner.$scanName 异常: $e")
            }
        }

        // 2. Hook BluetoothLeScanner 的所有 stopScan 重载
        try {
            XposedHelpers.hookAllMethods(leScannerClass, "stopScan") { chain, _ ->
                val callback = chain.args.firstOrNull { isScanCallback(it) }
                    ?: chain.args.lastOrNull {
                        it != null && it !is List<*> && !it.javaClass.name.contains("PendingIntent")
                    }
                if (callback != null) {
                    stopBleTimer(callback)
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // 3. 旧版 startLeScan 分发逻辑

    // 4. Hook BluetoothAdapter 的所有 startLeScan / stopLeScan 重载
    val bluetoothAdapterClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothAdapter", classLoader)
    if (bluetoothAdapterClass != null) {
        try {
            XposedHelpers.hookAllMethods(bluetoothAdapterClass, "startLeScan") { chain, _ ->
                XposedBridge.log("[LocationSpoofer] 捕获到 BluetoothAdapter.startLeScan: args=${chain.args.map { it?.javaClass?.simpleName }}")
                var config = readConfig()
                if (config == null) {
                    config = loadConfigFromDisk("startLeScan_direct")
                }
                if (config == null || !config.optBoolean("active", false) || !config.optBoolean("mock_bluetooth", true)) {
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                val callback = chain.args.firstOrNull { isLeScanCallback(it) }
                    ?: chain.args.lastOrNull { it != null && it !is Array<*> }
                if (callback != null) {
                    XposedBridge.log("[LocationSpoofer] 成功拦截 startLeScan, 目标回调=${callback.javaClass.name}")
                    startOldLeScanTimer(callback, classLoader)
                }
                return@hookAllMethods true
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothAdapterClass, "stopLeScan") { chain, _ ->
                val callback = chain.args.firstOrNull { isLeScanCallback(it) }
                    ?: chain.args.lastOrNull { it != null && it !is Array<*> }
                if (callback != null) {
                    stopBleTimer(callback)
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothAdapterClass, "getRemoteDevice") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                if (result != null) return@hookAllMethods result
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val mac = chain.args.firstOrNull { it is String } as? String
                    if (mac != null) {
                        val dev = createBluetoothDevice(classLoader, mac)
                        if (dev != null) return@hookAllMethods dev
                    }
                }
                return@hookAllMethods null
            }
        } catch (_: Throwable) {}

        // 5. Hook BluetoothAdapter 状态查询与特性支持
        val booleanTrueMethods = arrayOf(
            "isEnabled",
            "isLeEnabled",
            "getLeAccess",
            "isOffloadedFilteringSupported",
            "isOffloadedScanBatchingSupported",
            "isMultipleAdvertisementSupported",
            "isLe2MPhySupported",
            "isLeCodedPhySupported",
            "isLeExtendedAdvertisingSupported",
            "isLePeriodicAdvertisingSupported"
        )
        for (mName in booleanTrueMethods) {
            try {
                XposedHelpers.hookAllMethods(bluetoothAdapterClass, mName) { chain, _ ->
                    val config = readConfig() ?: loadConfigFromDisk(mName)
                    if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                        return@hookAllMethods true
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }

        val stateMethods = arrayOf("getState", "getLeState")
        for (mName in stateMethods) {
            try {
                XposedHelpers.hookAllMethods(bluetoothAdapterClass, mName) { chain, _ ->
                    val config = readConfig() ?: loadConfigFromDisk(mName)
                    if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                        return@hookAllMethods 12 // BluetoothAdapter.STATE_ON
                    }
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
            } catch (_: Throwable) {}
        }

        // 6. Hook BluetoothAdapter.getBluetoothLeScanner
        try {
            XposedHelpers.hookAllMethods(bluetoothAdapterClass, "getBluetoothLeScanner") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                val config = readConfig()
                if (result == null && config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val adapter = chain.thisObject
                    try {
                        var scanner = XposedHelpers.getObjectField(adapter, "mBluetoothLeScanner")
                        if (scanner == null && leScannerClass != null) {
                            for (c in leScannerClass.declaredConstructors) {
                                try {
                                    c.isAccessible = true
                                    val dummyArgs = arrayOfNulls<Any>(c.parameterCount)
                                    scanner = c.newInstance(*dummyArgs)
                                    if (scanner != null) break
                                } catch (_: Throwable) {}
                            }
                            if (scanner != null) {
                                try {
                                    XposedHelpers.setObjectField(adapter, "mBluetoothLeScanner", scanner)
                                } catch (_: Throwable) {}
                            }
                        }
                        if (scanner != null) return@hookAllMethods scanner
                    } catch (_: Throwable) {}
                }
                return@hookAllMethods result
            }
        } catch (_: Throwable) {}

        // 7. Hook BluetoothAdapter.getBondedDevices
        try {
            XposedHelpers.hookAllMethods(bluetoothAdapterClass, "getBondedDevices") { chain, _ ->
                val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                if (!config.optBoolean("active", false) || !config.optBoolean("mock_bluetooth", true)) {
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                val bondedSet = java.util.HashSet<Any>()
                try {
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (bluetoothArray != null && bluetoothArray.length() > 0) {
                        for (i in 0 until bluetoothArray.length()) {
                            val obj = bluetoothArray.getJSONObject(i)
                            if (obj.optBoolean("isConnected", false)) {
                                val address = obj.optString("address", "00:00:00:00:00:00")
                                val dev = createBluetoothDevice(classLoader, address)
                                if (dev != null) bondedSet.add(dev)
                            }
                        }
                    }
                } catch (_: Throwable) {}
                return@hookAllMethods bondedSet
            }
        } catch (_: Throwable) {}

        // 8. Hook BluetoothAdapter.startDiscovery 模拟经典蓝牙扫描并分发 ACTION_FOUND 广播
        try {
            XposedHelpers.hookAllMethods(bluetoothAdapterClass, "startDiscovery") { chain, _ ->
                val config = readConfig() ?: return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                if (!config.optBoolean("active", false) || !config.optBoolean("mock_bluetooth", true)) {
                    return@hookAllMethods chain.proceed(chain.args.toTypedArray())
                }
                val bluetoothArray = config.optJSONArray("bluetooth_json")
                if (bluetoothArray != null && bluetoothArray.length() > 0) {
                    try {
                        val activityThreadClass = XposedHelpers.findClassIfExists("android.app.ActivityThread", classLoader)
                        val app = if (activityThreadClass != null) {
                            XposedHelpers.callStaticMethod(activityThreadClass, "currentApplication") as? android.content.Context
                        } else null

                        if (app != null) {
                            mainHandler.postDelayed({
                                try {
                                    app.sendBroadcast(android.content.Intent("android.bluetooth.adapter.action.DISCOVERY_STARTED"))
                                } catch (_: Throwable) {}
                            }, 50)
                            for (i in 0 until bluetoothArray.length()) {
                                val obj = bluetoothArray.getJSONObject(i)
                                val address = obj.optString("address", "00:11:22:33:44:55")
                                val name = obj.optString("name", "")
                                val rssi = obj.optInt("rssi", -60)
                                val dev = createBluetoothDevice(classLoader, address) ?: continue
                                mainHandler.postDelayed({
                                    try {
                                        val foundIntent = android.content.Intent("android.bluetooth.device.action.FOUND").apply {
                                            putExtra("android.bluetooth.device.extra.DEVICE", dev as android.os.Parcelable)
                                            putExtra("android.bluetooth.device.extra.NAME", name)
                                            putExtra("android.bluetooth.device.extra.RSSI", rssi.toShort())
                                        }
                                        app.sendBroadcast(foundIntent)
                                    } catch (_: Throwable) {}
                                }, (100 + i * 150).toLong())
                            }
                        }
                    } catch (_: Throwable) {}
                }
                return@hookAllMethods true
            }
        } catch (_: Throwable) {}
    }

    // 9. Hook BluetoothDevice.getName / getAlias / getType / getBondState / getUuids / getAddress
    val bluetoothDeviceClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothDevice", classLoader)
    if (bluetoothDeviceClass != null) {
        try {
            XposedHelpers.hookAllMethods(bluetoothDeviceClass, "getAddress") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray()) as? String
                if (!result.isNullOrEmpty() && result != "00:00:00:00:00:00") return@hookAllMethods result
                val config = readConfig() ?: loadConfigFromDisk("getAddress")
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (bluetoothArray != null && bluetoothArray.length() > 0) {
                        val addr = bluetoothArray.getJSONObject(0).optString("address", "")
                        if (addr.isNotEmpty()) return@hookAllMethods normalizeMacAddress(addr)
                    }
                }
                return@hookAllMethods result
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothDeviceClass, "getName") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val thisDevice = chain.thisObject
                    val address = (XposedHelpers.callMethod(thisDevice, "getAddress") as? String)?.uppercase()
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (address != null && bluetoothArray != null) {
                        for (i in 0 until bluetoothArray.length()) {
                            val obj = bluetoothArray.getJSONObject(i)
                            if (obj.optString("address").uppercase() == address) {
                                val mockName = obj.optString("name")
                                if (mockName.isNotEmpty()) return@hookAllMethods mockName
                            }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothDeviceClass, "getAlias") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val thisDevice = chain.thisObject
                    val address = (XposedHelpers.callMethod(thisDevice, "getAddress") as? String)?.uppercase()
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (address != null && bluetoothArray != null) {
                        for (i in 0 until bluetoothArray.length()) {
                            val obj = bluetoothArray.getJSONObject(i)
                            if (obj.optString("address").uppercase() == address) {
                                val mockName = obj.optString("name")
                                if (mockName.isNotEmpty()) return@hookAllMethods mockName
                            }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothDeviceClass, "getType") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    return@hookAllMethods 2 // DEVICE_TYPE_LE
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothDeviceClass, "getBondState") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val thisDevice = chain.thisObject
                    val address = (XposedHelpers.callMethod(thisDevice, "getAddress") as? String)?.uppercase()
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (address != null && bluetoothArray != null) {
                        for (i in 0 until bluetoothArray.length()) {
                            val obj = bluetoothArray.getJSONObject(i)
                            if (obj.optString("address").uppercase() == address) {
                                if (obj.optBoolean("isConnected", false)) {
                                    return@hookAllMethods 12 // BOND_BONDED
                                }
                            }
                        }
                    }
                    return@hookAllMethods 10 // BOND_NONE
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothDeviceClass, "getUuids") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val parcelUuidClass = XposedHelpers.findClassIfExists("android.os.ParcelUuid", classLoader)
                    if (parcelUuidClass != null) {
                        try {
                            val uuidObj = XposedHelpers.callStaticMethod(
                                parcelUuidClass,
                                "fromString",
                                "0000fe3c-0000-1000-8000-00805f9b34fb"
                            )
                            if (uuidObj != null) {
                                val array = java.lang.reflect.Array.newInstance(parcelUuidClass, 1)
                                java.lang.reflect.Array.set(array, 0, uuidObj)
                                return@hookAllMethods array
                            }
                        } catch (_: Throwable) {}
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothDeviceClass, "connectGatt") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val gattCallback = chain.args.firstOrNull { it != null && it.javaClass.name.contains("GattCallback") }
                    val result = chain.proceed(chain.args.toTypedArray())
                    if (gattCallback != null && result != null) {
                        mainHandler.postDelayed({
                            try {
                                XposedHelpers.callMethod(gattCallback, "onConnectionStateChange", result, 0, 2 /* STATE_CONNECTED */)
                            } catch (_: Throwable) {}
                        }, 200)
                    }
                    return@hookAllMethods result
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // 10. Hook ScanRecord (getDeviceName, getServiceUuids, getManufacturerSpecificData)
    if (scanRecordClass != null) {
        try {
            XposedHelpers.hookAllMethods(scanRecordClass, "getDeviceName") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                if (result != null && (result as? String)?.isNotEmpty() == true) {
                    return@hookAllMethods result
                }
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (bluetoothArray != null && bluetoothArray.length() > 0) {
                        val name = bluetoothArray.getJSONObject(0).optString("name", "")
                        if (name.isNotEmpty()) return@hookAllMethods name
                    }
                }
                return@hookAllMethods result
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(scanRecordClass, "getServiceUuids") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray()) as? List<*>
                if (!result.isNullOrEmpty()) return@hookAllMethods result
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val parcelUuidClass = XposedHelpers.findClassIfExists("android.os.ParcelUuid", classLoader)
                    if (parcelUuidClass != null) {
                        try {
                            val uuidObj = XposedHelpers.callStaticMethod(parcelUuidClass, "fromString", "0000fe3c-0000-1000-8000-00805f9b34fb")
                            if (uuidObj != null) return@hookAllMethods listOf(uuidObj)
                        } catch (_: Throwable) {}
                    }
                }
                return@hookAllMethods result
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(scanRecordClass, "getManufacturerSpecificData") { chain, _ ->
                val config = readConfig() ?: loadConfigFromDisk("mfr_data")
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (bluetoothArray != null && bluetoothArray.length() > 0) {
                        val hex = bluetoothArray.getJSONObject(0).optString("scanRecordHex", bluetoothArray.getJSONObject(0).optString("rawBytes", ""))
                        val rawBytes = if (hex.isNotEmpty()) hexStringToByteArray(hex) else ByteArray(0)
                        if (rawBytes.isNotEmpty()) {
                            if (chain.args.isEmpty()) {
                                val sparseArray = android.util.SparseArray<ByteArray>()
                                sparseArray.put(0x0100, rawBytes)
                                sparseArray.put(0x0001, rawBytes)
                                sparseArray.put(256, rawBytes)
                                sparseArray.put(1, rawBytes)
                                sparseArray.put(0, rawBytes)
                                return@hookAllMethods sparseArray
                            } else {
                                return@hookAllMethods rawBytes
                            }
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(scanRecordClass, "getBytes") { chain, _ ->
                val config = readConfig() ?: loadConfigFromDisk("getBytes")
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (bluetoothArray != null && bluetoothArray.length() > 0) {
                        val obj = bluetoothArray.getJSONObject(0)
                        val hex = obj.optString("scanRecordHex", obj.optString("rawBytes", ""))
                        val name = obj.optString("name", "")
                        val address = obj.optString("address", "")
                        val rawBytes = buildScanRecordBytes(name, hex, address)
                        if (rawBytes.isNotEmpty()) {
                            return@hookAllMethods rawBytes
                        }
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // 11. Hook ScanFilter.matches (确保客户端过滤恒通过)
    val scanFilterClass = XposedHelpers.findClassIfExists("android.bluetooth.le.ScanFilter", classLoader)
    if (scanFilterClass != null) {
        try {
            XposedHelpers.hookAllMethods(scanFilterClass, "matches") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    return@hookAllMethods true
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    // 12. Hook BluetoothManager.getConnectedDevices & getDevicesMatchingConnectionStates & getAdapter
    val bluetoothManagerClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothManager", classLoader)
    if (bluetoothManagerClass != null) {
        try {
            XposedHelpers.hookAllMethods(bluetoothManagerClass, "getAdapter") { chain, _ ->
                val result = chain.proceed(chain.args.toTypedArray())
                if (result != null) return@hookAllMethods result
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val adapterClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothAdapter", classLoader)
                    if (adapterClass != null) {
                        return@hookAllMethods XposedHelpers.callStaticMethod(adapterClass, "getDefaultAdapter")
                    }
                }
                return@hookAllMethods null
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothManagerClass, "getConnectedDevices") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (bluetoothArray != null && bluetoothArray.length() > 0) {
                        val list = java.util.ArrayList<Any>()
                        for (i in 0 until bluetoothArray.length()) {
                            val obj = bluetoothArray.getJSONObject(i)
                            if (obj.optBoolean("isConnected", false) || obj.optBoolean("isDesignated", false)) {
                                val address = obj.optString("address", "00:00:00:00:00:00")
                                val dev = createBluetoothDevice(classLoader, address)
                                if (dev != null) list.add(dev)
                            }
                        }
                        if (list.isNotEmpty()) return@hookAllMethods list
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}

        try {
            XposedHelpers.hookAllMethods(bluetoothManagerClass, "getDevicesMatchingConnectionStates") { chain, _ ->
                val config = readConfig()
                if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
                    val bluetoothArray = config.optJSONArray("bluetooth_json")
                    if (bluetoothArray != null && bluetoothArray.length() > 0) {
                        val list = java.util.ArrayList<Any>()
                        for (i in 0 until bluetoothArray.length()) {
                            val obj = bluetoothArray.getJSONObject(i)
                            if (obj.optBoolean("isConnected", false) || obj.optBoolean("isDesignated", false)) {
                                val address = obj.optString("address", "00:00:00:00:00:00")
                                val dev = createBluetoothDevice(classLoader, address)
                                if (dev != null) list.add(dev)
                            }
                        }
                        if (list.isNotEmpty()) return@hookAllMethods list
                    }
                }
                return@hookAllMethods chain.proceed(chain.args.toTypedArray())
            }
        } catch (_: Throwable) {}
    }

    XposedBridge.log("[LocationSpoofer] Bluetooth LE hooks installed")
}

// 辅助函数：安全反射调用 onScanResult
internal fun LocationHooker.dispatchScanResultToCallback(callback: Any, scanResultObj: Any) {
        var dispatched = false
        var c: Class<*>? = callback.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == "onScanResult") {
                    if (m.parameterCount == 2) {
                        try {
                            m.isAccessible = true
                            m.invoke(callback, 1 /* CALLBACK_TYPE_ALL_MATCHES */, scanResultObj)
                            dispatched = true
                            break
                        } catch (e: Throwable) {
                            XposedBridge.log("[LocationSpoofer] dispatch onScanResult (2-args) direct error: ${e.cause ?: e}")
                        }
                    } else if (m.parameterCount == 1) {
                        try {
                            m.isAccessible = true
                            m.invoke(callback, scanResultObj)
                            dispatched = true
                            break
                        } catch (e: Throwable) {
                            XposedBridge.log("[LocationSpoofer] dispatch onScanResult (1-arg) direct error: ${e.cause ?: e}")
                        }
                    }
                }
            }
            if (dispatched) break
            c = c.superclass
        }
        if (!dispatched) {
            try {
                XposedHelpers.callMethod(callback, "onScanResult", 1, scanResultObj)
                dispatched = true
            } catch (_: Throwable) {
                try {
                    XposedHelpers.callMethod(callback, "onScanResult", scanResultObj)
                    dispatched = true
                } catch (_: Throwable) {}
            }
        }
        if (!dispatched) {
            try {
                val inner = XposedHelpers.getObjectField(callback, "mScanCallback") ?:
                            XposedHelpers.getObjectField(callback, "mCallback")
                if (inner != null) {
                    dispatchScanResultToCallback(inner, scanResultObj)
                }
            } catch (_: Throwable) {}
        }
    }

// 辅助函数：安全反射调用 onBatchScanResults
internal fun LocationHooker.dispatchBatchResultsToCallback(callback: Any, results: List<Any>) {
        var dispatched = false
        var c: Class<*>? = callback.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == "onBatchScanResults" && m.parameterCount == 1) {
                    try {
                        m.isAccessible = true
                        m.invoke(callback, results)
                        dispatched = true
                        break
                    } catch (_: Throwable) {}
                }
            }
            if (dispatched) break
            c = c.superclass
        }
        if (!dispatched) {
            try {
                XposedHelpers.callMethod(callback, "onBatchScanResults", results)
            } catch (_: Throwable) {}
        }
    }

// BLE 扫描结果伪造的核心分发逻辑
internal val syntheticBleDelivery = com.vincenthzr.locationspoofer.xposed.utils.CallScope<Boolean>()

internal fun LocationHooker.deliverBleScanResults(config: JSONObject, callback: Any, cl: ClassLoader) {
        val mainHandler = Handler(Looper.getMainLooper())
        val scanRecordClass = XposedHelpers.findClassIfExists("android.bluetooth.le.ScanRecord", cl)
        if (!config.optBoolean("mock_bluetooth", true)) return
        val bluetoothArray = config.optJSONArray("bluetooth_json") ?: return
        if (bluetoothArray.length() == 0) return

        val results = java.util.ArrayList<Any>()
        val targetScanRecordClass = XposedHelpers.findClassIfExists("android.bluetooth.le.ScanRecord", cl) ?: scanRecordClass
        val timestampNanos = SystemClock.elapsedRealtimeNanos()

        for (i in 0 until bluetoothArray.length()) {
            try {
                val obj = bluetoothArray.getJSONObject(i)
                val address = obj.optString("address", "00:11:22:33:44:55")
                val name = obj.optString("name", "")
                val baseRssi = obj.optInt("rssi", -60)
                val rssi = baseRssi + (if (config.optBoolean("enable_jitter", true)) Random.nextInt(-2, 3) else 0)
                val hexRecord = obj.optString("scanRecordHex", "")
                val rawBytes = buildScanRecordBytes(name, hexRecord, address)

                val device = createBluetoothDevice(cl, address)
                if (device == null) {
                    XposedBridge.log("[LocationSpoofer] 创建虚拟蓝牙设备失败 address=$address")
                    continue
                }

                var scanRecord: Any? = null
                if (targetScanRecordClass != null && rawBytes.isNotEmpty()) {
                    try {
                        scanRecord = XposedHelpers.callStaticMethod(targetScanRecordClass, "parseFromBytes", rawBytes)
                    } catch (e: Throwable) {
                        XposedBridge.log("[LocationSpoofer] ScanRecord.parseFromBytes 异常: $e")
                    }
                }

                val scanResultObj = createScanResult(cl, device, scanRecord, rssi, timestampNanos)
                if (scanResultObj != null) {
                    results.add(scanResultObj)
                    mainHandler.post {
                        try {
                            syntheticBleDelivery.withValue(true) { dispatchScanResultToCallback(callback, scanResultObj) }
                            XposedBridge.log("[LocationSpoofer] 成功派发 onScanResult 给 ${callback.javaClass.name} | MAC=$address RSSI=$rssi 广播包长=${rawBytes.size}")
                        } catch (e: Throwable) {
                            XposedBridge.log("[LocationSpoofer] 派发 onScanResult 异常: $e")
                        }
                    }
                } else {
                    XposedBridge.log("[LocationSpoofer] 创建 ScanResult 失败 address=$address")
                }
            } catch (e: Throwable) {
                XposedBridge.log("[LocationSpoofer] 构造虚拟BLE失败: $e")
            }
        }
        if (results.isNotEmpty()) {
            mainHandler.post {
                try {
                    syntheticBleDelivery.withValue(true) { dispatchBatchResultsToCallback(callback, results) }
                } catch (_: Throwable) {}
            }
        }
    }

// 启动周期性定时广播模拟真实信标心跳 (约每 200ms 发送一次)
internal fun LocationHooker.startBleTimer(callback: Any, cl: ClassLoader) {
        bleScanTimers.remove(callback)?.cancel()
        val timer = java.util.Timer("LocationSpoofer-BleTimer", true)
        bleScanTimers[callback] = timer

        // 立即执行第一次
        var config = readConfig()
        if (config == null) {
            config = loadConfigFromDisk("startBleTimer_init")
        }
        if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
            deliverBleScanResults(config, callback, cl)
        }

        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                val cfg = readConfig() ?: loadConfigFromDisk("bleTimer")
                if (cfg == null || !cfg.optBoolean("active", false) || !cfg.optBoolean("mock_bluetooth", true)) {
                    cancel()
                    bleScanTimers.remove(callback)
                    return
                }
                deliverBleScanResults(cfg, callback, cl)
            }
        }, 200L, 200L)
    }

internal fun LocationHooker.stopBleTimer(callback: Any) {
        bleScanTimers.remove(callback)?.cancel()
    }

internal fun LocationHooker.deliverOldLeScanResults(config: JSONObject, callback: Any, cl: ClassLoader) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (!config.optBoolean("mock_bluetooth", true)) return
        val bluetoothArray = config.optJSONArray("bluetooth_json") ?: return
        if (bluetoothArray.length() == 0) return

        for (i in 0 until bluetoothArray.length()) {
            try {
                val obj = bluetoothArray.getJSONObject(i)
                val address = obj.optString("address", "00:11:22:33:44:55")
                val name = obj.optString("name", "")
                val baseRssi = obj.optInt("rssi", -60)
                val rssi = baseRssi + (if (config.optBoolean("enable_jitter", true)) Random.nextInt(-2, 3) else 0)
                val hexRecord = obj.optString("scanRecordHex", "")
                val rawBytes = buildScanRecordBytes(name, hexRecord, address)

                val device = createBluetoothDevice(cl, address) ?: continue
                mainHandler.post {
                    try {
                        XposedHelpers.callMethod(callback, "onLeScan", device, rssi, rawBytes)
                    } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                XposedBridge.log("[LocationSpoofer] 构造旧版LeScan失败: $e")
            }
        }
    }

internal fun LocationHooker.startOldLeScanTimer(callback: Any, cl: ClassLoader) {
        bleScanTimers.remove(callback)?.cancel()
        val timer = java.util.Timer("LocationSpoofer-OldBleTimer", true)
        bleScanTimers[callback] = timer

        var config = readConfig()
        if (config == null) {
            config = loadConfigFromDisk("startOldLeScanTimer")
        }
        if (config != null && config.optBoolean("active", false) && config.optBoolean("mock_bluetooth", true)) {
            deliverOldLeScanResults(config, callback, cl)
        }

        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                val cfg = readConfig() ?: loadConfigFromDisk("oldTimer")
                if (cfg == null || !cfg.optBoolean("active", false) || !cfg.optBoolean("mock_bluetooth", true)) {
                    cancel()
                    bleScanTimers.remove(callback)
                    return
                }
                deliverOldLeScanResults(cfg, callback, cl)
            }
        }, 200L, 200L)
    }
