package com.suseoaa.locationspoofer.data.db

import androidx.room.*

@Dao
interface EnvironmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(record: LocationRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectedWifi(wifi: LocationConnectedWifi)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWifiDevice(device: WifiDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationWifi(record: LocationWifi)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBluetoothDevice(device: BluetoothDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationBluetooth(record: LocationBluetooth)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Query("SELECT * FROM location_records WHERE abs(lat - :lat) < :tolerance AND abs(lng - :lng) < :tolerance ORDER BY timestamp DESC LIMIT 1")
    suspend fun findLocationByCoordinates(lat: Double, lng: Double, tolerance: Double = 0.0001): LocationRecord?

    @Query("SELECT * FROM location_records")
    suspend fun getAllLocations(): List<LocationRecord>

    @Transaction
    @Query("SELECT * FROM location_records")
    suspend fun getAllCompleteLocations(): List<CompleteLocation>

    @Query("SELECT COUNT(*) FROM location_records")
    suspend fun getRecordCount(): Int

    @Query("DELETE FROM location_records")
    suspend fun clearAll()

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
