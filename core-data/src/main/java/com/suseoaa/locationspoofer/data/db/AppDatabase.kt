package com.suseoaa.locationspoofer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocationRecord::class,
        LocationConnectedWifi::class,
        WifiDevice::class, LocationWifi::class,
        BluetoothDevice::class, LocationBluetooth::class,
        CellDevice::class, LocationCell::class,
        SavedRouteEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun environmentDao(): EnvironmentDao
    abstract fun savedRouteDao(): SavedRouteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_records ADD COLUMN placeName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE location_records ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE location_records ADD COLUMN selectedWifiBssid TEXT")
                db.execSQL("ALTER TABLE location_records ADD COLUMN selectedBluetoothAddress TEXT")
                db.execSQL("ALTER TABLE location_records ADD COLUMN selectedCellKey TEXT")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_records_lat_lng ON location_records(lat, lng)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_records_timestamp ON location_records(timestamp)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "environment_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
