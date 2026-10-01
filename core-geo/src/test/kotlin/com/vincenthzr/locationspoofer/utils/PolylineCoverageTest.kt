package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.*
import org.junit.Test

class PolylineCoverageTest {
    private val start = LatLng(30.0, 104.0)
    private val middle = GeoMath.destination(start, 0.0, 500.0)
    private val end = GeoMath.destination(start, 0.0, 1000.0)
    private val path = listOf(start, end)

    @Test fun corridorIncludesBothSidesButNotOutside() {
        for (bearing in listOf(90.0, 270.0)) {
            assertTrue(PolylineCoverage.distanceMeters(GeoMath.destination(middle, bearing, 49.9), path) < 50.0)
            assertTrue(PolylineCoverage.distanceMeters(GeoMath.destination(middle, bearing, 50.1), path) > 50.0)
        }
    }

    @Test fun endpointsUseRoundCaps() {
        assertEquals(49.0, PolylineCoverage.distanceMeters(GeoMath.destination(start, 180.0, 49.0), path), 0.1)
        assertTrue(PolylineCoverage.distanceMeters(GeoMath.destination(end, 0.0, 51.0), path) > 50.0)
    }

    @Test fun boundingBoxInteriorDoesNotCoverBendInterior() {
        val east = GeoMath.destination(end, 90.0, 1000.0)
        val insideBox = GeoMath.destination(middle, 90.0, 500.0)
        assertTrue(PolylineCoverage.distanceMeters(insideBox, listOf(start, end, east)) > 400.0)
    }

    @Test fun emptyAndDuplicatePathsStayWellDefined() {
        assertEquals(Double.POSITIVE_INFINITY, PolylineCoverage.distanceMeters(start, emptyList()), 0.0)
        assertEquals(0.0, PolylineCoverage.distanceMeters(start, listOf(start, start)), 0.0)
        assertEquals(500.0, PolylineCoverage.distanceMeters(middle, listOf(start)), 0.1)
    }

    @Test fun dateLineCrossingUsesShortSegment() {
        val cross = listOf(LatLng(10.0, 179.999), LatLng(10.0, -179.999))
        assertEquals(0.0, PolylineCoverage.distanceMeters(LatLng(10.0, 180.0), cross), 0.01)
        assertTrue(PolylineCoverage.distanceMeters(LatLng(10.001, 180.0), cross) > 100.0)
        assertTrue(PolylineCoverage.distanceMeters(LatLng(10.0, 0.0), cross) > 1_000_000.0)
    }
}
