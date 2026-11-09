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
 * 蓝牙 LE 扫描结果/设备对象构造辅助函数（纯计算，不依赖 LocationHooker 状态）。
 */
internal fun hexStringToByteArray(s: String): ByteArray {
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

    // 辅助函数：MAC 地址标准化（补齐冒号以防底层抛出 IllegalArgumentException）
internal fun normalizeMacAddress(mac: String): String {
        val clean = mac.replace(":", "").replace("-", "").trim().uppercase()
        if (clean.length == 12 && clean.all { it in "0123456789ABCDEF" }) {
            return clean.chunked(2).joinToString(":")
        }
        return mac.trim().uppercase()
    }

    // 检查字节数组是否为合法的 BLE 广播 AD 结构 (TLV 格式: [Length, Type, Value...])
internal fun isValidAdStructure(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var pos = 0
        var foundValidAd = false
        while (pos < bytes.size) {
            val len = bytes[pos].toInt() and 0xFF
            if (len == 0) {
                // BLE 广播包末尾填充 00 是标准行为
                return foundValidAd
            }
            if (pos + 1 + len > bytes.size) {
                return false
            }
            foundValidAd = true
            pos += 1 + len
        }
        return foundValidAd
    }

    // 检查字节数组是否已包含名称 AD (0x08 简称 或 0x09 全称)
internal fun containsAdName(bytes: ByteArray): Boolean {
        var pos = 0
        while (pos < bytes.size) {
            val len = bytes[pos].toInt() and 0xFF
            if (len == 0 || pos + 1 + len > bytes.size) break
            val type = bytes[pos + 1].toInt() and 0xFF
            if (type == 0x08 || type == 0x09) return true
            pos += 1 + len
        }
        return false
    }

    // 辅助函数：智能构造并规范化 BLE 广播包字节数组
internal fun buildScanRecordBytes(name: String, hexRecord: String, address: String = ""): ByteArray {
        val cleanHex = hexRecord.replace("0x", "", ignoreCase = true)
            .replace("0X", "")
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
            .replace(",", "")
            .replace(";", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
            .uppercase()

        var rawPayload: ByteArray? = null

        if (cleanHex.isNotEmpty() && cleanHex.length >= 2) {
            try {
                val validHex = if (cleanHex.length % 2 != 0) cleanHex.substring(0, cleanHex.length - 1) else cleanHex
                val inputBytes = hexStringToByteArray(validHex)

                if (isValidAdStructure(inputBytes)) {
                    // 格式 1: 完整的标准 TLV 结构广播包（例如从系统/抓包工具导出的 0201061AFF...）
                    rawPayload = inputBytes
                } else if (validHex.startsWith("1AFF4C00") || validHex.startsWith("1EFF4C00")) {
                    // 格式 2: 带有长度的厂商数据段，前面缺少 020106 Flags
                    val flags = byteArrayOf(0x02, 0x01, 0x06)
                    rawPayload = flags + inputBytes
                } else if (validHex.startsWith("FF4C00") || validHex.startsWith("FF")) {
                    // 格式 3: 带有 Type(0xFF) 但缺少 Length 的厂商数据段
                    val flags = byteArrayOf(0x02, 0x01, 0x06)
                    val lenByte = byteArrayOf(inputBytes.size.toByte())
                    rawPayload = flags + lenByte + inputBytes
                } else if (validHex.startsWith("4C000215") || validHex.startsWith("004C0215") || validHex.startsWith("4C00")) {
                    // 格式 4: 纯 Apple iBeacon 厂商数据段（从 nRF Connect 等工具复制的 Manufacturer Data）
                    val flags = byteArrayOf(0x02, 0x01, 0x06)
                    val mfrHeader = byteArrayOf((inputBytes.size + 1).toByte(), 0xFF.toByte())
                    rawPayload = flags + mfrHeader + inputBytes
                } else if (validHex.startsWith("0215") && validHex.length >= 46) {
                    // 格式 5: 缺少 Apple 厂商 ID 的 iBeacon 数据（0215 + UUID + Major + Minor + TxPower）
                    val flags = byteArrayOf(0x02, 0x01, 0x06)
                    val mfrHeader = byteArrayOf(0x1A, 0xFF.toByte(), 0x4C, 0x00)
                    rawPayload = flags + mfrHeader + inputBytes
                } else if (validHex.length == 32) {
                    // 格式 6: 纯 16 字节 UUID
                    val flags = byteArrayOf(0x02, 0x01, 0x06)
                    val mfrData = byteArrayOf(
                        0x1A, 0xFF.toByte(), 0x4C, 0x00, 0x02, 0x15
                    ) + inputBytes + byteArrayOf(0x00, 0x01, 0x00, 0x01, 0xC5.toByte())
                    rawPayload = flags + mfrData
                } else {
                    // 格式 7: 其他厂商自定义数据段，自动封装 Flags + 0xFF 结构
                    val flags = byteArrayOf(0x02, 0x01, 0x06)
                    val mfrHeader = byteArrayOf((inputBytes.size + 1).toByte(), 0xFF.toByte())
                    rawPayload = flags + mfrHeader + inputBytes
                }
            } catch (_: Throwable) {}
        }

        if (rawPayload != null && rawPayload.isNotEmpty()) {
            return rawPayload
        }

        // 若用户未填写，自动合成标准的 iBeacon 考勤广播包与名称结构
        val flags = byteArrayOf(0x02, 0x01, 0x06)
        val defaultIBeacon = byteArrayOf(
            0x1A, 0xFF.toByte(), 0x4C, 0x00, 0x02, 0x15,
            0xFD.toByte(), 0xA5.toByte(), 0x06, 0x93.toByte(),
            0xA4.toByte(), 0xE2.toByte(), 0x4F, 0xB1.toByte(),
            0xAF.toByte(), 0xCF.toByte(), 0xC6.toByte(), 0xEB.toByte(),
            0x07, 0x64, 0x78, 0x25,
            0x00, 0x01, 0x00, 0x01, 0xC5.toByte()
        )
        var synthetic = flags + defaultIBeacon
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.isNotEmpty() && synthetic.size + nameBytes.size + 2 <= 31) {
            val nameAd = byteArrayOf((nameBytes.size + 1).toByte(), 0x09) + nameBytes
            synthetic = synthetic + nameAd
        }

        return synthetic
    }

    // 辅助函数：安全创建 BluetoothDevice
internal fun createBluetoothDevice(cl: ClassLoader, address: String): Any? {
        val cleanAddress = normalizeMacAddress(address)
        val devClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothDevice", cl) ?: return null
        
        // 尝试 1: BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        try {
            val adapterClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothAdapter", cl)
            if (adapterClass != null) {
                val adapter = XposedHelpers.callStaticMethod(adapterClass, "getDefaultAdapter")
                if (adapter != null) {
                    val dev = XposedHelpers.callMethod(adapter, "getRemoteDevice", cleanAddress)
                    if (dev != null) return dev
                }
            }
        } catch (_: Throwable) {}

        // 尝试 2: 显式构造器 BluetoothDevice(String)
        try {
            val ctor = devClass.getDeclaredConstructor(String::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(cleanAddress)
        } catch (_: Throwable) {}

        // 尝试 3: 反射所有构造器兜底
        for (c in devClass.declaredConstructors) {
            try {
                if (c.parameterTypes.size == 1 && c.parameterTypes[0] == String::class.java) {
                    c.isAccessible = true
                    return c.newInstance(cleanAddress)
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    // 辅助函数：智能判断对象是否为 BLE 扫描回调 (兼容匿名内部类、多层继承与动态代理)
internal fun isScanCallback(obj: Any?): Boolean {
        if (obj == null) return false
        if (obj is android.os.Parcelable || obj is String || obj is Number || obj is Boolean) return false
        val cls = obj.javaClass
        val name = cls.name
        if (name.contains("ScanFilter") || name.contains("ScanSettings") || name.contains("PendingIntent")) return false

        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val cName = c.name
            if (cName == "android.bluetooth.le.ScanCallback" || cName.endsWith("ScanCallback")) {
                return true
            }
            for (iface in c.interfaces) {
                if (iface.name.contains("ScanCallback") || iface.name.contains("LeScanCallback")) {
                    return true
                }
            }
            c = c.superclass
        }
        // 兜底：通过方法名反射检测 (包含 onScanResult 或 onBatchScanResults)
        try {
            if (cls.methods.any { it.name == "onScanResult" || it.name == "onBatchScanResults" } ||
                cls.declaredMethods.any { it.name == "onScanResult" || it.name == "onBatchScanResults" }) {
                return true
            }
        } catch (_: Throwable) {}

        return obj is android.bluetooth.le.ScanCallback || obj is android.bluetooth.BluetoothAdapter.LeScanCallback
    }

    // 辅助函数：智能判断对象是否为旧版 LeScanCallback
internal fun isLeScanCallback(obj: Any?): Boolean {
        if (obj == null) return false
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            val name = cls.name
            val sName = cls.simpleName
            if (name == "android.bluetooth.BluetoothAdapter\$LeScanCallback" || sName == "LeScanCallback" || name.endsWith("\$LeScanCallback")) {
                return true
            }
            for (iface in cls.interfaces) {
                if (iface.name.contains("LeScanCallback")) {
                    return true
                }
            }
            cls = cls.superclass
        }
        return obj is android.bluetooth.BluetoothAdapter.LeScanCallback
    }

    // 辅助函数：安全创建 ScanResult
internal fun createScanResult(
        cl: ClassLoader,
        device: Any,
        scanRecord: Any?,
        rssi: Int,
        timestampNanos: Long
    ): Any? {
        val scanResultClass = XposedHelpers.findClassIfExists("android.bluetooth.le.ScanResult", cl) ?: return null
        val devClass = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothDevice", cl) ?: return null
        val recordClass = XposedHelpers.findClassIfExists("android.bluetooth.le.ScanRecord", cl)

        var result: Any? = null

        // 尝试 1: 10 参数扩展构造器 (Android 8.0 ~ Android 17 标准)
        // ScanResult(BluetoothDevice, int eventType, int primaryPhy, int secondaryPhy,
        //            int advertisingSid, int txPower, int rssi, int periodicAdvertisingInterval,
        //            ScanRecord, long timestampNanos)
        if (recordClass != null) {
            try {
                val ctor10 = scanResultClass.getDeclaredConstructor(
                    devClass,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    recordClass,
                    Long::class.javaPrimitiveType
                )
                result = ctor10.newInstance(
                    device,
                    0x001B /* DATA_COMPLETE | ET_CONNECTABLE | ET_SCANNABLE | ET_LEGACY_ADV */,
                    1 /* PHY_LE_1M */,
                    0 /* PHY_UNUSED */,
                    255 /* SID_NOT_PRESENT */,
                    127 /* TX_POWER_NOT_PRESENT */,
                    rssi,
                    0 /* periodicAdvertisingInterval */,
                    scanRecord,
                    timestampNanos
                )
            } catch (_: Throwable) {}
        }

        // 尝试 2: 4 参数标准构造器 (ScanResult(BluetoothDevice, ScanRecord, int, long))
        if (result == null && recordClass != null) {
            try {
                val ctor4 = scanResultClass.getDeclaredConstructor(
                    devClass, recordClass, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType
                )
                ctor4.isAccessible = true
                result = ctor4.newInstance(device, scanRecord, rssi, timestampNanos)
            } catch (_: Throwable) {}
        }

        // 尝试 3: 遍历所有可用构造器进行自适应匹配
        if (result == null) {
            for (c in scanResultClass.declaredConstructors) {
                try {
                    c.isAccessible = true
                    val args = arrayOfNulls<Any>(c.parameterCount)
                    for (idx in 0 until c.parameterCount) {
                        val pType = c.parameterTypes[idx]
                        when {
                            devClass.isAssignableFrom(pType) -> args[idx] = device
                            recordClass != null && recordClass.isAssignableFrom(pType) -> args[idx] = scanRecord
                            pType == Int::class.javaPrimitiveType || pType == java.lang.Integer::class.java -> {
                                when (idx) {
                                    1 -> args[idx] = 0x001B
                                    2 -> args[idx] = if (c.parameterCount == 4) rssi else 1
                                    3 -> args[idx] = 0
                                    4 -> args[idx] = 255
                                    5 -> args[idx] = 127
                                    6 -> args[idx] = rssi
                                    else -> args[idx] = if (idx == c.parameterCount - 2) rssi else 0
                                }
                            }
                            pType == Long::class.javaPrimitiveType || pType == java.lang.Long::class.java -> args[idx] = timestampNanos
                            pType == Boolean::class.javaPrimitiveType || pType == java.lang.Boolean::class.java -> args[idx] = false
                            else -> args[idx] = null
                        }
                    }
                    result = c.newInstance(*args)
                    if (result != null) break
                } catch (_: Throwable) {}
            }
        }

        // 强制通过反射回填内部关键属性，确保跨各系统版本属性 100% 完整与正确
        if (result != null) {
            try { XposedHelpers.setObjectField(result, "mDevice", device) } catch (_: Throwable) {}
            try { XposedHelpers.setObjectField(result, "mScanRecord", scanRecord) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(result, "mRssi", rssi) } catch (_: Throwable) {}
            try { XposedHelpers.setLongField(result, "mTimestampNanos", timestampNanos) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(result, "mEventType", 0x001B) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(result, "mPrimaryPhy", 1) } catch (_: Throwable) {}
            try { XposedHelpers.setIntField(result, "mTxPower", 127) } catch (_: Throwable) {}
        }

        return result
    }
