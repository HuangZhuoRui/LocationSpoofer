package com.suseoaa.locationspoofer.xposed.utils

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class SpoofedMotion(
    val lat: Double,
    val lng: Double,
    val bearing: Float,
    val speed: Float
)

/**
 * 高性能路线轨迹连续插值引擎 (High-Performance Smooth Route Interpolation Engine)
 *
 * 解决路线模拟跳跃/不连续问题:
 * - 纯内存微秒级数学插值，基于当前系统时钟毫秒戳连续推导沿线坐标与实时航向角。
 * - 彻底告别对磁盘文件定时刷新的依赖，在任意高频调用下实现绝对平滑连续运动。
 * - 自动计算瞬时物理速度 (m/s) 与航向角度 (Bearing)，全面适配运动/跑步/骑行软件判定。
 */
object RouteEngine {

    data class Point(val lat: Double, val lng: Double)

    private var cachedRouteSignature: String = ""
    private var cachedPoints: List<Point> = emptyList()
    private var cachedCumulativeDistances: DoubleArray = DoubleArray(0)
    private var cachedTotalDistance: Double = 0.0

    private fun haversine(p1: Point, p2: Point): Double {
        val r = 6378137.0
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(p1.lat)) * cos(Math.toRadians(p2.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calculateBearing(from: Point, to: Point): Float {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return bearing.toFloat()
    }

    @Synchronized
    private fun updateRouteCache(routeArray: JSONArray) {
        val count = routeArray.length()
        val signature = "$count#${routeArray.optJSONObject(0)?.optDouble("lat", 0.0)}#${routeArray.optJSONObject(count - 1)?.optDouble("lat", 0.0)}"
        if (signature == cachedRouteSignature && cachedPoints.size == count && cachedPoints.isNotEmpty()) {
            return
        }
        val list = ArrayList<Point>(count)
        for (i in 0 until count) {
            val obj = routeArray.optJSONObject(i) ?: continue
            list.add(Point(obj.optDouble("lat", 0.0), obj.optDouble("lng", 0.0)))
        }
        cachedPoints = list
        if (list.size >= 2) {
            val cumDist = DoubleArray(list.size)
            var total = 0.0
            cumDist[0] = 0.0
            for (i in 0 until list.size - 1) {
                val d = haversine(list[i], list[i + 1])
                total += d
                cumDist[i + 1] = total
            }
            cachedCumulativeDistances = cumDist
            cachedTotalDistance = total
        } else {
            cachedCumulativeDistances = DoubleArray(0)
            cachedTotalDistance = 0.0
        }
        cachedRouteSignature = signature
    }

    fun calculateCurrentPosition(config: JSONObject, now: Long = System.currentTimeMillis()): SpoofedMotion {
        val isRouteMode = config.optBoolean("is_route_mode", false)
        val routeArray = config.optJSONArray("route_points")
        val baseLat = config.optDouble("lat", 0.0)
        val baseLng = config.optDouble("lng", 0.0)
        val baseBearing = config.optDouble("sim_bearing", 0.0).toFloat()

        if (!isRouteMode || routeArray == null || routeArray.length() < 2) {
            return SpoofedMotion(baseLat, baseLng, baseBearing, 0f)
        }

        updateRouteCache(routeArray)
        val points = cachedPoints
        val totalDist = cachedTotalDistance
        if (points.size < 2 || totalDist <= 0.0) {
            return SpoofedMotion(baseLat, baseLng, baseBearing, 0f)
        }

        val stopAtDestination = config.optBoolean("stop_at_destination", false)
        val isClosedLoop = if (points.size >= 2) {
            haversine(points.first(), points.last()) <= 5.0
        } else false

        val speed = config.optDouble("speed_m_s", 3.0).coerceAtLeast(0.1)
        val rawStartTime = config.optLong("start_timestamp", 0L)
        val startTime = if (rawStartTime > 0L) rawStartTime else now
        val elapsedSec = ((now - startTime).coerceAtLeast(0L)) / 1000.0
        val distTraveled = elapsedSec * speed

        if (stopAtDestination && distTraveled >= totalDist) {
            val lastPt = points.last()
            val prevPt = points[points.size - 2]
            val lastBearing = calculateBearing(prevPt, lastPt)
            return SpoofedMotion(lastPt.lat, lastPt.lng, lastBearing, 0f)
        }

        val forward: Boolean
        val targetDist: Double

        if (isClosedLoop) {
            val cycleDist = totalDist
            val distInCycle = if (cycleDist > 0.0) distTraveled % cycleDist else 0.0
            forward = true
            targetDist = distInCycle
        } else {
            val cycleDist = totalDist * 2.0
            val distInCycle = if (cycleDist > 0.0) distTraveled % cycleDist else 0.0
            forward = distInCycle <= totalDist
            targetDist = if (forward) distInCycle else cycleDist - distInCycle
        }

        val cum = cachedCumulativeDistances
        var segIndex = 0
        while (segIndex < points.size - 2 && cum[segIndex + 1] < targetDist) {
            segIndex++
        }

        val fromPt = points[segIndex]
        val toPt = points[segIndex + 1]
        val segStartDist = cum[segIndex]
        val segEndDist = cum[segIndex + 1]
        val segLen = (segEndDist - segStartDist).coerceAtLeast(0.0001)
        val ratio = ((targetDist - segStartDist) / segLen).coerceIn(0.0, 1.0)

        val curLat = fromPt.lat + (toPt.lat - fromPt.lat) * ratio
        val curLng = fromPt.lng + (toPt.lng - fromPt.lng) * ratio
        val bearing = if (forward) calculateBearing(fromPt, toPt) else calculateBearing(toPt, fromPt)

        return SpoofedMotion(curLat, curLng, bearing, speed.toFloat())
    }
}
