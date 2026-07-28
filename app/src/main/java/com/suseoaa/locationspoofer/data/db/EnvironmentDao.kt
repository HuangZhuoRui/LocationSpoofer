package com.suseoaa.locationspoofer.data.db

import androidx.room.*

@Dao
interface EnvironmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocation(record: LocationRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnectedWifi(wifi: LocationConnectedWifi)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWifiDevice(device: WifiDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationWifi(record: LocationWifi)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBluetoothDevice(device: BluetoothDevice)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationBluetooth(record: LocationBluetooth)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
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

    @Query("UPDATE location_records SET placeName = :placeName, remark = :remark, selectedWifiBssid = :selectedWifiBssid, selectedBluetoothAddress = :selectedBluetoothAddress, selectedCellKey = :selectedCellKey WHERE id = :id")
    suspend fun updateMetadata(id: Long, placeName: String, remark: String, selectedWifiBssid: String?, selectedBluetoothAddress: String?, selectedCellKey: String?)
}
