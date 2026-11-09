package com.vincenthzr.locationspoofer.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import kotlin.math.abs
import kotlin.math.cos

@Dao
interface EnvironmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(record: LocationRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectedWifi(wifi: LocationConnectedWifi)

    @Upsert
    suspend fun insertWifiDevice(device: WifiDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationWifi(record: LocationWifi)

    @Upsert
    suspend fun insertBluetoothDevice(device: BluetoothDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationBluetooth(record: LocationBluetooth)

    @Upsert
    suspend fun insertCellDevice(device: CellDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationCell(record: LocationCell)

    @Transaction
    @Query(
        """
        SELECT * FROM location_records 
        ORDER BY ((lat - :targetLat)*(lat - :targetLat) + (lng - :targetLng)*(lng - :targetLng)) ASC 
        LIMIT :limit
    """
    )
    suspend fun getNearestLocations(
        targetLat: Double,
        targetLng: Double,
        limit: Int = 3
    ): List<CompleteLocation>

    @Transaction
    @Query(
        """
        SELECT * FROM location_records 
        WHERE lat BETWEEN :minLat AND :maxLat 
          AND lng BETWEEN :minLng AND :maxLng 
        LIMIT :limit
    """
    )
    suspend fun getCompleteLocationsInBounds(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        limit: Int = 10
    ): List<CompleteLocation>

    @Transaction
    @Query("SELECT * FROM location_records WHERE id = :id LIMIT 1")
    suspend fun getCompleteLocationById(id: Long): CompleteLocation?

    @Query("SELECT * FROM location_records WHERE collectionRouteId IS NULL AND abs(lat - :lat) < :tolerance AND abs(lng - :lng) < :tolerance ORDER BY timestamp DESC LIMIT 1")
    suspend fun findLocationByCoordinates(lat: Double, lng: Double, tolerance: Double = 0.0001): LocationRecord?

    @Query("""
        SELECT * FROM location_records
        WHERE lat BETWEEN :minLat AND :maxLat AND (
            (:minLng <= :maxLng AND lng BETWEEN :minLng AND :maxLng) OR
            (:minLng > :maxLng AND (lng >= :minLng OR lng <= :maxLng))
        ) ORDER BY id
    """)
    suspend fun getLocationsInBounds(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<LocationRecord>

    /** 复用最近的 20 米内点位；保持锚点坐标不变，避免移动采集串联成一个大点位。 */
    @Transaction
    suspend fun findOrCreateCollectionLocation(lat: Double, lng: Double, collectionRouteId: Long? = null): Long {
        val radiusM = 20.0
        val latDelta = radiusM / 110_000.0
        val lngDelta = latDelta / maxOf(0.000001, abs(cos(Math.toRadians(lat))))
        fun normalizeLongitude(value: Double) = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val candidates = getLocationsInBounds(
            lat - latDelta, lat + latDelta,
            if (lngDelta >= 180.0) -180.0 else normalizeLongitude(lng - lngDelta),
            if (lngDelta >= 180.0) 180.0 else normalizeLongitude(lng + lngDelta)
        )
        val position = LatLng(lat, lng)
        val nearest = candidates.filter { it.collectionRouteId == collectionRouteId }
            .minByOrNull { GeoMath.distance(position, LatLng(it.lat, it.lng)) }
        if (nearest != null && GeoMath.distance(position, LatLng(nearest.lat, nearest.lng)) <= radiusM + 0.000001) {
            updateCollectionTimestamp(nearest.id, System.currentTimeMillis())
            return nearest.id
        }
        return insertLocation(LocationRecord(lat = lat, lng = lng, collectionRouteId = collectionRouteId))
    }

    @Insert
    suspend fun insertCollectionRoute(route: CollectionRouteRecord): Long

    @Transaction
    @Query("SELECT * FROM collection_routes ORDER BY timestamp DESC")
    fun observeCollectionRoutes(): Flow<List<CompleteCollectionRoute>>

    @Transaction
    @Query("SELECT * FROM collection_routes ORDER BY timestamp DESC")
    suspend fun getCollectionRoutes(): List<CompleteCollectionRoute>

    @Transaction
    @Query("SELECT * FROM collection_routes WHERE id = :id")
    suspend fun getCollectionRoute(id: Long): CompleteCollectionRoute?

    @Query("UPDATE collection_routes SET name = :name, remark = :remark WHERE id = :id")
    suspend fun updateCollectionRouteInfo(id: Long, name: String, remark: String)

    @Query("DELETE FROM collection_routes WHERE id = :id")
    suspend fun deleteCollectionRoute(id: Long)

    @Transaction
    suspend fun insertCompleteLocationData(value: CompleteLocation, routeId: Long? = null) {
        val id = insertLocation(value.location.copy(id = 0, collectionRouteId = routeId))
        value.connectedWifi?.let { insertConnectedWifi(it.copy(locationId = id)) }
        value.wifis.forEach { insertWifiDevice(it.device); insertLocationWifi(it.locationWifi.copy(locationId = id)) }
        value.cells.forEach { insertCellDevice(it.device); insertLocationCell(it.locationCell.copy(locationId = id)) }
        value.bluetooths.forEach { insertBluetoothDevice(it.device); insertLocationBluetooth(it.locationBluetooth.copy(locationId = id)) }
    }

    @Transaction
    suspend fun insertCompleteCollectionRoute(value: CompleteCollectionRoute) {
        val id = insertCollectionRoute(value.route.copy(id = 0))
        value.samples.forEach { insertCompleteLocationData(it, id) }
    }

    @Query("UPDATE location_records SET timestamp = :timestamp WHERE id = :id")
    suspend fun updateCollectionTimestamp(id: Long, timestamp: Long)

    @Query("UPDATE location_records SET placeName = :placeName, remark = :remark WHERE id = :id")
    suspend fun updateBasicInfo(id: Long, placeName: String, remark: String)

    @Query("SELECT * FROM location_records")
    suspend fun getAllLocations(): List<LocationRecord>

    @Transaction
    @Query("SELECT * FROM location_records")
    suspend fun getAllCompleteLocations(): List<CompleteLocation>

    @Transaction
    @Query("SELECT * FROM location_records")
    fun observeAllCompleteLocations(): Flow<List<CompleteLocation>>

    @Query("SELECT (SELECT COUNT(*) FROM location_records WHERE collectionRouteId IS NULL) + (SELECT COUNT(*) FROM collection_routes)")
    suspend fun getRecordCount(): Int

    @Query("DELETE FROM location_records")
    suspend fun clearAll()

    @Query("DELETE FROM collection_routes")
    suspend fun clearCollectionRoutes()

    @Query("DELETE FROM location_records WHERE id = :id")
    suspend fun deleteLocation(id: Long)

    @Query("DELETE FROM location_records WHERE id IN (:ids)")
    suspend fun deleteLocations(ids: List<Long>)

    @Query("DELETE FROM location_wifi WHERE locationId = :locationId AND bssid = :bssid")
    suspend fun deleteLocationWifi(locationId: Long, bssid: String)

    @Query("DELETE FROM location_connected_wifi WHERE locationId = :locationId")
    suspend fun deleteConnectedWifi(locationId: Long)

    @Query("DELETE FROM location_cells WHERE locationId = :locationId AND cellKey = :cellKey")
    suspend fun deleteLocationCell(locationId: Long, cellKey: String)

    @Query("DELETE FROM location_bluetooth WHERE locationId = :locationId AND address = :address")
    suspend fun deleteLocationBluetooth(locationId: Long, address: String)

    @Query("UPDATE location_records SET selectedWifiBssid = :selectedWifiBssid WHERE id = :id")
    suspend fun updateSelectedWifi(id: Long, selectedWifiBssid: String?)

    @Query("UPDATE location_records SET selectedCellKey = :selectedCellKey WHERE id = :id")
    suspend fun updateSelectedCell(id: Long, selectedCellKey: String?)

    @Query("UPDATE location_records SET selectedBluetoothAddress = :selectedBluetoothAddress WHERE id = :id")
    suspend fun updateSelectedBluetooth(id: Long, selectedBluetoothAddress: String?)

    @Query("UPDATE location_records SET lat = :lat, lng = :lng, placeName = :placeName, remark = :remark, selectedWifiBssid = :selectedWifiBssid, selectedBluetoothAddress = :selectedBluetoothAddress, selectedCellKey = :selectedCellKey WHERE id = :id")
    suspend fun updateMetadata(id: Long, lat: Double, lng: Double, placeName: String, remark: String, selectedWifiBssid: String?, selectedBluetoothAddress: String?, selectedCellKey: String?)

    @Query("UPDATE location_records SET lat = :lat, lng = :lng WHERE id = :id")
    suspend fun updateCoordinates(id: Long, lat: Double, lng: Double)
}
