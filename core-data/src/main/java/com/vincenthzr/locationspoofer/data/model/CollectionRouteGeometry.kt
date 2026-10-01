package com.vincenthzr.locationspoofer.data.model

import com.vincenthzr.locationspoofer.data.db.CollectionRouteRecord
import com.vincenthzr.locationspoofer.data.db.CompleteCollectionRoute
import com.vincenthzr.locationspoofer.data.db.CompleteLocation
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.PolylineCoverage
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

private val collectionRouteJson = Json { ignoreUnknownKeys = true }

// 路径不可变；模拟每 100 ms 更新位置时复用已解析路径，并限制缓存数量。
private val geometryCache = object : LinkedHashMap<String, List<LatLng>>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LatLng>>): Boolean = size > 64
}

fun CollectionRouteRecord.geometry(): List<LatLng> = synchronized(geometryCache) {
    geometryCache.getOrPut(pointsJson) {
        runCatching {
            collectionRouteJson.decodeFromString<List<RoutePoint>>(pointsJson)
                .map { LatLng(it.lat, it.lng) }
                .takeIf { points -> points.all { it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 } }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }
}

object CollectionRouteMatcher {
    fun routeAt(routes: List<CompleteCollectionRoute>, position: LatLng, preferredId: Long? = null): CompleteCollectionRoute? {
        val covered = routes.filter {
            val geometry = it.route.geometry()
            it.samples.isNotEmpty() && geometry.size >= 2 && it.route.halfWidthM.isFinite() &&
                PolylineCoverage.distanceMeters(position, geometry) <= it.route.halfWidthM
        }
        return covered.firstOrNull { it.route.id == preferredId } ?: covered.minByOrNull { route ->
            route.samples.minOf { GeoMath.distance(position, LatLng(it.location.lat, it.location.lng)) }
        }
    }

    fun samplesAt(route: CompleteCollectionRoute, position: LatLng): List<CompleteLocation> = route.samples
        .sortedBy { GeoMath.distance(position, LatLng(it.location.lat, it.location.lng)) }
        .take(3)
}
