package com.suseoaa.locationspoofer.utils

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 国家测绘局标准坐标偏移算法（GCJ-02 ↔ WGS-84 ↔ BD-09）
 */
object CoordinateUtils {

    private const val PI = Math.PI
    private const val A = 6378245.0           // 克拉索夫斯基椭球体长半轴
    private const val EE = 0.00669342162296594 // 偏心率平方
    private const val BD_PI = PI * 3000.0 / 180.0

    data class LatLng(val lat: Double, val lng: Double)

    /**
     * WGS-84 → GCJ-02（正向加偏）
     */
    fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): LatLng {
        if (outOfChina(wgsLat, wgsLng)) return LatLng(wgsLat, wgsLng)
        val dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        val dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val newLat = wgsLat + (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val newLng = wgsLng + (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return LatLng(newLat, newLng)
    }

    /**
     * GCJ-02 → WGS-84（高精度逆向逼近迭代算法，误差小于 0.5 毫米）
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): LatLng {
        if (outOfChina(gcjLat, gcjLng)) return LatLng(gcjLat, gcjLng)
        var wgsLat = gcjLat
        var wgsLng = gcjLng
        for (i in 0 until 3) {
            val curr = wgs84ToGcj02(wgsLat, wgsLng)
            wgsLat -= (curr.lat - gcjLat)
            wgsLng -= (curr.lng - gcjLng)
        }
        return LatLng(wgsLat, wgsLng)
    }

    /**
     * GCJ-02 → BD-09(百度坐标系)
     */
    fun gcj02ToBd09(gcjLat: Double, gcjLng: Double): LatLng {
        val x = gcjLng
        val y = gcjLat
        val z = sqrt(x * x + y * y) + 0.00002 * sin(y * BD_PI)
        val theta = Math.atan2(y, x) + 0.000003 * cos(x * BD_PI)
        val bdLng = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return LatLng(bdLat, bdLng)
    }

    /**
     * BD-09 → GCJ-02
     */
    fun bd09ToGcj02(bdLat: Double, bdLng: Double): LatLng {
        val x = bdLng - 0.0065
        val y = bdLat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * BD_PI)
        val theta = Math.atan2(y, x) - 0.000003 * cos(x * BD_PI)
        val gcjLng = z * cos(theta)
        val gcjLat = z * sin(theta)
        return LatLng(gcjLat, gcjLng)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }
}
