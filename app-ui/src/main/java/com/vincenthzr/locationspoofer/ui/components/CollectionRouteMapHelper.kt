package com.vincenthzr.locationspoofer.ui.components

import android.graphics.Color
import com.vincenthzr.locationspoofer.data.db.CollectionRouteRecord
import com.vincenthzr.locationspoofer.data.model.geometry
import com.vincenthzr.locationspoofer.utils.GeoMath

object CollectionRouteMapHelper {
    fun draw(controller: AppMapController, route: CollectionRouteRecord, maxCircles: Int = 80) {
        val points = route.geometry()
        if (points.size < 2) return
        controller.addPolyline(points.map { it.lat to it.lng }, Color.rgb(59, 130, 246), 9f)
        val lengths = points.zipWithNext().map { (a, b) -> GeoMath.distance(a, b) }
        val total = lengths.sum()
        val step = maxOf(25.0, total / maxOf(1, maxCircles - 1))
        var distance = 0.0
        var segmentStart = 0.0
        var index = 0
        var circles = 0
        while (distance <= total && circles < maxCircles) {
            while (index < lengths.lastIndex && distance > segmentStart + lengths[index]) {
                segmentStart += lengths[index++]
            }
            val point = GeoMath.destination(points[index], GeoMath.bearing(points[index], points[index + 1]).toDouble(), distance - segmentStart)
            controller.addCircle(point.lat, point.lng, route.halfWidthM, Color.argb(24, 59, 130, 246), Color.TRANSPARENT, 0f)
            distance += step
            circles++
        }
        val last = points.last()
        controller.addCircle(last.lat, last.lng, route.halfWidthM, Color.argb(24, 59, 130, 246), Color.TRANSPARENT, 0f)
        controller.addMarker(points.first().lat, points.first().lng, route.name, MarkerType.GREEN)
        controller.addMarker(last.lat, last.lng, route.name, MarkerType.RED)
    }

    fun show(controller: AppMapController, route: CollectionRouteRecord, bottomPadding: Int = 360) {
        controller.clear()
        draw(controller, route)
        controller.fitBounds(route.geometry().map { it.lat to it.lng }, 80, 120, 80, bottomPadding)
    }
}
