package com.vincenthzr.locationspoofer.data.model

import com.vincenthzr.locationspoofer.data.db.*
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class CollectionRouteMatcherTest {
    private val start = LatLng(30.0, 104.0)
    private val end = GeoMath.destination(start, 0.0, 1000.0)
    private fun route(id: Long = 1) = CompleteCollectionRoute(
        CollectionRouteRecord(id = id, name = "Route", pointsJson = Json.encodeToString(listOf(RoutePoint(start.lat, start.lng), RoutePoint(end.lat, end.lng)))),
        listOf(start, end).mapIndexed { index, p -> CompleteLocation(LocationRecord(id = index + id * 10, lat = p.lat, lng = p.lng, collectionRouteId = id)) }
    )

    @Test fun sparseRouteMiddleUsesActualSamplesDespiteDistanceOver50m() {
        val position = GeoMath.destination(start, 0.0, 600.0)
        val route = route()
        assertEquals(route, CollectionRouteMatcher.routeAt(listOf(route), position))
        assertEquals(end.lat, CollectionRouteMatcher.samplesAt(route, position).first().location.lat, 0.000001)
        assertEquals(start.lat, CollectionRouteMatcher.samplesAt(route, start).first().location.lat, 0.000001)
    }

    @Test fun outsideCorridorDoesNotBorrowSamples() {
        assertNull(CollectionRouteMatcher.routeAt(listOf(route()), GeoMath.destination(start, 90.0, 51.0)))
    }

    @Test fun explicitlySelectedOverlappingRouteWins() {
        val routes = listOf(route(1), route(2))
        assertEquals(2L, CollectionRouteMatcher.routeAt(routes, start, 2)?.route?.id)
    }

    @Test fun malformedOrEmptyRoutesCannotMatch() {
        val route = route()
        val invalid = listOf(
            route.copy(samples = emptyList()),
            route.copy(route = route.route.copy(pointsJson = "broken")),
            route.copy(route = route.route.copy(pointsJson = "[{\"lat\":100.0,\"lng\":104.0},{\"lat\":30.0,\"lng\":104.0}]")),
            route.copy(route = route.route.copy(halfWidthM = -1.0))
        )
        assertNull(CollectionRouteMatcher.routeAt(invalid, start))
    }

    @Test fun backupRetainsPathNameSamplesOwnershipAndOldDefaults() {
        val original = LocationSpooferDataPackage(collectionRoutes = listOf(route().copy(route = route().route.copy(remark = "Note"))))
        val restored = Json.decodeFromString<LocationSpooferDataPackage>(Json { encodeDefaults = true }.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(2, restored.collectionRoutes.single().route.geometry().size)
        val old = Json.decodeFromString<LocationSpooferDataPackage>("{\"version\":3,\"locations\":[]}")
        assertTrue(old.collectionRoutes.isEmpty())
    }
}
