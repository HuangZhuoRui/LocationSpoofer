package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import kotlin.math.cos
import kotlin.math.hypot

/** 路线覆盖是线段两侧的范围，包含端点圆帽，而不是整条路线的外接矩形。 */
object PolylineCoverage {
    fun distanceMeters(position: LatLng, points: List<LatLng>): Double {
        if (points.isEmpty()) return Double.POSITIVE_INFINITY
        if (points.size == 1) return GeoMath.distance(position, points.first())
        val scale = GeoMath.EARTH_RADIUS_M * Math.PI / 180.0
        fun project(point: LatLng): Pair<Double, Double> {
            val longitude = ((point.lng - position.lng + 540.0) % 360.0) - 180.0
            return longitude * scale * cos(Math.toRadians(position.lat)) to (point.lat - position.lat) * scale
        }
        return points.zipWithNext().minOf { (start, end) ->
            val (ax, ay) = project(start)
            val by = (end.lat - position.lat) * scale
            // 保持整段经度连续；跨日期变更线的短路线不能误覆盖另一侧半球。
            val longitudeDelta = ((end.lng - start.lng + 540.0) % 360.0) - 180.0
            val dx = longitudeDelta * scale * cos(Math.toRadians(position.lat))
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq <= 0.000001) 0.0 else ((-ax * dx - ay * dy) / lengthSq).coerceIn(0.0, 1.0)
            hypot(ax + t * dx, ay + t * dy)
        }
    }
}
