package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    const val EARTH_RADIUS_M = 6378137.0

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 从 from 指向 to 的方位角（度，正北为 0，顺时针） */
    fun bearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** 从 start 沿 bearing 方向前进 meters 米后的位置 */
    fun destination(start: LatLng, bearingDeg: Double, meters: Double): LatLng {
        val angular = meters / EARTH_RADIUS_M
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.lat)
        val lng1 = Math.toRadians(start.lng)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearingRad))
        val lng2 = lng1 + atan2(sin(bearingRad) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lng2))
    }
}

/**
 * 一条路线上"已行进距离 → 位置"的映射，App 和 Xposed 两端共用，保证两边对同一距离算出同一位置。
 *
 * 规则：首尾相距 5 米以内视为闭环，走到终点后从起点继续；否则走到终点后原路折返，来回往复。
 * stopAtDestination 为真时走到终点就停下。
 */
class RoutePath(val points: List<LatLng>) {

    data class Position(val lat: Double, val lng: Double, val bearing: Float, val arrived: Boolean)

    private val cumulative: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + GeoMath.distance(points[i - 1], points[i])
    }

    val totalDistance: Double = if (points.isEmpty()) 0.0 else cumulative.last()
    val isClosedLoop: Boolean = points.size >= 2 && GeoMath.distance(points.first(), points.last()) <= 5.0
    val isValid: Boolean get() = points.size >= 2 && totalDistance > 0.0

    fun positionAt(distanceTraveled: Double, stopAtDestination: Boolean): Position {
        require(isValid) { "route needs at least two distinct points" }
        val traveled = distanceTraveled.coerceAtLeast(0.0)

        if (stopAtDestination && traveled >= totalDistance) {
            val last = points.last()
            return Position(last.lat, last.lng, GeoMath.bearing(points[points.size - 2], last), arrived = true)
        }

        val forward: Boolean
        val along: Double
        if (isClosedLoop) {
            forward = true
            along = traveled % totalDistance
        } else {
            val cycle = totalDistance * 2
            val inCycle = traveled % cycle
            forward = inCycle <= totalDistance
            along = if (forward) inCycle else cycle - inCycle
        }

        var seg = 0
        while (seg < points.size - 2 && cumulative[seg + 1] < along) seg++
        val from = points[seg]
        val to = points[seg + 1]
        val segLen = (cumulative[seg + 1] - cumulative[seg]).coerceAtLeast(0.0001)
        val ratio = ((along - cumulative[seg]) / segLen).coerceIn(0.0, 1.0)
        val bearing = if (forward) GeoMath.bearing(from, to) else GeoMath.bearing(to, from)
        return Position(
            from.lat + (to.lat - from.lat) * ratio,
            from.lng + (to.lng - from.lng) * ratio,
            bearing,
            arrived = false
        )
    }
}
