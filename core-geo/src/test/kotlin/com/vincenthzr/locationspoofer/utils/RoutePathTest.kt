package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePathTest {

    private val a = LatLng(30.0, 120.0)
    private val b = GeoMath.destination(a, 90.0, 1000.0) // 正东 1000 米

    @Test
    fun `destination and distance are consistent`() {
        assertEquals(1000.0, GeoMath.distance(a, b), 0.01)
        assertEquals(90.0, GeoMath.bearing(a, b).toDouble(), 0.01)
    }

    @Test
    fun `open route goes back and forth`() {
        val path = RoutePath(listOf(a, b))
        assertFalse(path.isClosedLoop)
        val out = path.positionAt(400.0, stopAtDestination = false)
        assertEquals(400.0, GeoMath.distance(a, LatLng(out.lat, out.lng)), 0.5)
        assertEquals(90.0, out.bearing.toDouble(), 0.5)
        val back = path.positionAt(1600.0, stopAtDestination = false) // 折返途中，距起点 400 米
        assertEquals(400.0, GeoMath.distance(a, LatLng(back.lat, back.lng)), 0.5)
        assertEquals(270.0, back.bearing.toDouble(), 0.5)
    }

    @Test
    fun `closed loop keeps going forward`() {
        val c = GeoMath.destination(b, 0.0, 1000.0)
        val path = RoutePath(listOf(a, b, c, a))
        assertTrue(path.isClosedLoop)
        val lap = path.positionAt(path.totalDistance + 300.0, stopAtDestination = false)
        assertEquals(300.0, GeoMath.distance(a, LatLng(lap.lat, lap.lng)), 0.5)
    }

    @Test
    fun `stops at the destination when requested`() {
        val path = RoutePath(listOf(a, b))
        val end = path.positionAt(5000.0, stopAtDestination = true)
        assertTrue(end.arrived)
        assertEquals(b.lat, end.lat, 1e-9)
        assertEquals(b.lng, end.lng, 1e-9)
    }
}
