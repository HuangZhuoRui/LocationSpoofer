package com.suseoaa.locationspoofer.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRouteDao {
    @Query("SELECT * FROM saved_routes ORDER BY timestamp DESC")
    fun getAllSavedRoutes(): Flow<List<SavedRouteEntity>>

    @Query("SELECT * FROM saved_routes ORDER BY timestamp DESC")
    suspend fun getAllSavedRoutesList(): List<SavedRouteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedRoute(route: SavedRouteEntity)

    @Delete
    suspend fun deleteSavedRoute(route: SavedRouteEntity)
}
