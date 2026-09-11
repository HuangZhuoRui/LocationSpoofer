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

package com.suseoaa.locationspoofer.xposed.utils

import com.suseoaa.locationspoofer.utils.CoordinateUtils
import com.suseoaa.locationspoofer.xposed.LocationHooker
import org.json.JSONObject
import kotlin.math.*

/**
 * 坐标系转换工具类 (Coordinate Converter)
 * 
 * 上下文:
 * - WGS-84: 国际标准 GPS 坐标。
 * - GCJ-02 (火星坐标系): 中国国家测绘局制定的强制加密坐标，中国大陆的所有地图(高德、腾讯)均使用此坐标。
 * - BD-09: 百度在 GCJ-02 的基础上再次加密的坐标系。
 * 
 * 作用:
 * 提供各个坐标系之间的精确数学转换。
 */

// 实际数学实现统一收敛到 com.suseoaa.locationspoofer.utils.CoordinateUtils（唯一 source of truth），
// 这里保留 LocationHooker 扩展函数签名只是为了不改动 Hook 层现有的调用写法。
internal fun LocationHooker.wgs84ToGcj02(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.wgs84ToGcj02(wgsLat, wgsLng)
    return Pair(r.lat, r.lng)
}

/**
 * GCJ-02 转 WGS-84（采用高精度反向迭代逼近算法，误差小于 0.5 毫米）
 */
internal fun LocationHooker.gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.gcj02ToWgs84(gcjLat, gcjLng)
    return Pair(r.lat, r.lng)
}

// GCJ-02 转 BD-09 转换(百度坐标系)
internal fun LocationHooker.gcj02ToBd09(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.gcj02ToBd09(gcjLat, gcjLng)
    return Pair(r.lat, r.lng)
}

internal fun LocationHooker.bd09ToGcj02(bdLat: Double, bdLng: Double): Pair<Double, Double> {
    val r = CoordinateUtils.bd09ToGcj02(bdLat, bdLng)
    return Pair(r.lat, r.lng)
}

/**
 * BD09ll (经纬度) 转 BD09mc (百度墨卡托米制平面坐标)
 * 百度地图引擎在进行底层瓦片渲染与路径规划时，常使用墨卡托投影米制单位。
 */
fun bd09llToBd09mc(bdLat: Double, bdLng: Double): Pair<Double, Double> {
    val x = bdLng * 20037508.342789244 / 180.0
    val latClamped = bdLat.coerceIn(-85.0511287798, 85.0511287798)
    var y = kotlin.math.ln(kotlin.math.tan((90.0 + latClamped) * Math.PI / 360.0)) / (Math.PI / 180.0)
    y = y * 20037508.342789244 / 180.0
    return Pair(y, x) // Pair(mcLat/Y, mcLng/X)
}

/**
 * 根据应用包名及用户自定义算法配置，动态获取目标坐标
 *
 * @param rawGcjLat 基础 GCJ-02 纬度
 * @param rawGcjLng 基础 GCJ-02 经度
 * @param config 全局配置 JSON
 * @param defaultSystem 当未配置自定义算法时的默认坐标系（如高德/腾讯默认 GCJ-02，百度默认 BD-09）
 */
internal fun LocationHooker.getAppTargetCoordinate(
    rawGcjLat: Double,
    rawGcjLng: Double,
    config: JSONObject?,
    defaultSystem: String = "GCJ-02"
): Pair<Double, Double> {
    if (config == null) return when (defaultSystem) {
        "WGS-84" -> gcj02ToWgs84(rawGcjLat, rawGcjLng)
        "BD-09" -> gcj02ToBd09(rawGcjLat, rawGcjLng)
        else -> Pair(rawGcjLat, rawGcjLng)
    }
    val appSystems = config.optJSONObject("app_coordinate_systems")
    val basePkg = currentPackageName.substringBefore(":")

    // 优先读取用户在“自定义坐标算法”页面针对当前包名的个性化配置
    val userConfigured = if (appSystems?.has(basePkg) == true) {
        appSystems.optString(basePkg, "AUTO")
    } else {
        "AUTO"
    }

    val targetSys = if (userConfigured == "AUTO" || userConfigured.isBlank()) {
        defaultSystem
    } else {
        userConfigured
    }

    return when (targetSys) {
        "WGS-84" -> gcj02ToWgs84(rawGcjLat, rawGcjLng)
        "BD-09" -> gcj02ToBd09(rawGcjLat, rawGcjLng)
        "GCJ-02" -> Pair(rawGcjLat, rawGcjLng)
        else -> when (defaultSystem) {
            "WGS-84" -> gcj02ToWgs84(rawGcjLat, rawGcjLng)
            "BD-09" -> gcj02ToBd09(rawGcjLat, rawGcjLng)
            else -> Pair(rawGcjLat, rawGcjLng)
        }
    }
}

internal fun LocationHooker.getJitteredLocation(
    baseLat: Double,
    baseLng: Double
): Pair<Double, Double> {
    val enableJitter = lastConfig?.optBoolean("enable_jitter", true) ?: true
    if (!enableJitter) return Pair(baseLat, baseLng)

    val now = System.currentTimeMillis()
    val dt = if (hookLastCallTime > 0) {
        ((now - hookLastCallTime) / 1000.0).coerceIn(0.01, 5.0)
    } else 1.0
    hookLastCallTime = now

    // sigma=0.000002度(约0.2米步长), alpha=0.05(均值回归)
    val sigma = 0.000002
    val alpha = 0.05

    // 使用 Ornstein-Uhlenbeck 过程生成自然偏移，并硬性限制在 4 米以内 (约 0.00004 度)
    hookDriftLat =
        (hookDriftLat + sigma * sqrt(dt) * rng.nextGaussian() - alpha * hookDriftLat * dt)
            .coerceIn(-0.00004, 0.00004)
    hookDriftLng =
        (hookDriftLng + sigma * sqrt(dt) * rng.nextGaussian() - alpha * hookDriftLng * dt)
            .coerceIn(-0.00004, 0.00004)

    return Pair(baseLat + hookDriftLat, baseLng + hookDriftLng)
}

internal fun LocationHooker.getJitteredAccuracy(): Float {
    // 精度值在真实优良室外环境(1.5m-3.5m)微弱起伏，运动与地图软件将判定为满格绿色强信号
    hookAccuracyDrift += 0.1 * rng.nextGaussian() - 0.05 * hookAccuracyDrift
    return (2.2 + hookAccuracyDrift).coerceIn(1.5, 3.5).toFloat()
}
