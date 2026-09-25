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

import com.vincenthzr.locationspoofer.xposed.LocationHooker
import com.vincenthzr.locationspoofer.xposed.utils.*
import com.vincenthzr.locationspoofer.xposed.hooks.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.*
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * NMEA $GPGGA/$GPRMC/$GPGSA/$GPGSV 语句动态拼装与劫持
 */
internal fun LocationHooker.createOnNmeaMessageListenerProxy(
    original: Any,
    classLoader: ClassLoader
): Any {
    val interfaceClass = classLoader.loadClass("android.location.OnNmeaMessageListener")
    val proxy = Proxy.newProxyInstance(
        classLoader,
        arrayOf(interfaceClass),
        object : InvocationHandler {
            override fun invoke(
                proxy: Any,
                method: Method,
                args: Array<out Any>?
            ): Any? {
                if (method.name == "onNmeaMessage" && args != null && args.size >= 1) {
                    val originalMsg = args[0] as? String
                    if (originalMsg != null) {
                        val spoofedMsg = spoofNmeaMessage(originalMsg)
                        if (spoofedMsg == null) return null // 允许丢弃消息
                        val newArgs = arrayOfNulls<Any>(args.size)
                        for (i in args.indices) {
                            newArgs[i] = if (i == 0) spoofedMsg else args[i]
                        }
                        return method.invoke(original, *newArgs)
                    }
                }
                val methodArgs =
                    if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                return method.invoke(original, *methodArgs)
            }
        }
    )
    // startNmeaGsvInjector(original, "onNmeaMessage", classLoader)
    return proxy
}

internal fun LocationHooker.createGpsStatusNmeaListenerProxy(
    original: Any,
    classLoader: ClassLoader
): Any {
    val interfaceClass = classLoader.loadClass("android.location.GpsStatus\$NmeaListener")
    val proxy = Proxy.newProxyInstance(
        classLoader,
        arrayOf(interfaceClass),
        object : InvocationHandler {
            override fun invoke(
                proxy: Any,
                method: Method,
                args: Array<out Any>?
            ): Any? {
                if (method.name == "onNmeaReceived" && args != null && args.size >= 2) {
                    val originalMsg = args[1] as? String
                    if (originalMsg != null) {
                        val spoofedMsg = spoofNmeaMessage(originalMsg)
                        if (spoofedMsg == null) return null // 允许丢弃消息
                        val newArgs = arrayOfNulls<Any>(args.size)
                        for (i in args.indices) {
                            newArgs[i] = if (i == 1) spoofedMsg else args[i]
                        }
                        return method.invoke(original, *newArgs)
                    }
                }
                val methodArgs =
                    if (args == null) emptyArray<Any>() else Array(args.size) { i -> args[i] }
                return method.invoke(original, *methodArgs)
            }
        }
    )
    // startNmeaGsvInjector(original, "onNmeaReceived", classLoader)
    return proxy
}

internal fun LocationHooker.spoofNmeaMessage(sentence: String): String? {
    try {
        val config = readConfig() ?: return sentence
        if (!config.optBoolean("active", false)) return sentence

        val motion = RouteEngine.calculateCurrentPosition(config)
        val targetCoords = getAppTargetCoordinate(motion.lat, motion.lng, config, "WGS-84")
        val targetLat = targetCoords.first
        val targetLng = targetCoords.second

        val parts = sentence.split("*")
        val mainPart = parts[0]
        val fields = mainPart.split(",").toMutableList()
        if (fields.isEmpty()) return sentence

        val type = fields[0]
        var modified = false

        if (type.endsWith("RMC") && fields.size >= 7) {
            val (latStr, latDir) = convertToNmeaLatitude(targetLat)
            val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
            fields[2] = "A" // Status Active / Valid
            fields[3] = latStr
            fields[4] = latDir
            fields[5] = lngStr
            fields[6] = lngDir
            if (fields.size > 7) {
                val knots = motion.speed * 1.943844f
                fields[7] = String.format(java.util.Locale.US, "%.2f", knots)
            }
            if (fields.size > 8) {
                fields[8] = String.format(java.util.Locale.US, "%.1f", motion.bearing)
            }
            modified = true
        } else if (type.endsWith("GGA") && fields.size >= 6) {
            val (latStr, latDir) = convertToNmeaLatitude(targetLat)
            val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
            fields[2] = latStr
            fields[3] = latDir
            fields[4] = lngStr
            fields[5] = lngDir
            if (fields.size > 6) fields[6] = "1" // GPS Fix (Quality indicator)
            if (fields.size > 7) fields[7] = config.optInt("satellite_count", 18).toString() // Number of satellites
            if (fields.size > 8) fields[8] = "0.8" // HDOP (High accuracy)
            if (fields.size > 9) fields[9] = String.format(java.util.Locale.US, "%.1f", RouteEngine.realisticAltitude(config))
            modified = true
        } else if (type.endsWith("GLL") && fields.size >= 5) {
            val (latStr, latDir) = convertToNmeaLatitude(targetLat)
            val (lngStr, lngDir) = convertToNmeaLongitude(targetLng)
            fields[1] = latStr
            fields[2] = latDir
            fields[3] = lngStr
            fields[4] = lngDir
            if (fields.size > 6) fields[6] = "A"
            modified = true
        }

        if (!modified) return sentence

        val newMainPart = fields.joinToString(",")
        val newChecksum = calculateNmeaChecksum(newMainPart)

        val tail = if (parts.size > 1) {
            val rawTail = parts[1]
            val lineEnding = rawTail.substring(Math.min(2, rawTail.length))
            "*$newChecksum$lineEnding"
        } else {
            "*$newChecksum"
        }
        return newMainPart + tail
    } catch (e: Exception) {
        XposedBridge.log(e)
        return sentence
    }
}

internal fun LocationHooker.convertToNmeaLatitude(lat: Double): Pair<String, String> {
    val absLat = Math.abs(lat)
    val degrees = absLat.toInt()
    val minutes = (absLat - degrees) * 60.0
    val latStr = String.format(java.util.Locale.US, "%02d%08.5f", degrees, minutes)
    val dir = if (lat >= 0) "N" else "S"
    return Pair(latStr, dir)
}

internal fun LocationHooker.convertToNmeaLongitude(lng: Double): Pair<String, String> {
    val absLng = Math.abs(lng)
    val degrees = absLng.toInt()
    val minutes = (absLng - degrees) * 60.0
    val lngStr = String.format(java.util.Locale.US, "%03d%08.5f", degrees, minutes)
    val dir = if (lng >= 0) "E" else "W"
    return Pair(lngStr, dir)
}

internal fun LocationHooker.calculateNmeaChecksum(sentence: String): String {
    var checksum = 0
    val startIndex = if (sentence.startsWith("$")) 1 else 0
    val endIndex = sentence.indexOf('*')
    val limit = if (endIndex != -1) endIndex else sentence.length
    for (i in startIndex until limit) {
        checksum = checksum xor sentence[i].code
    }
    return String.format(java.util.Locale.US, "%02X", checksum)
}
