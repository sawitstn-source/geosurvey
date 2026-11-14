package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SpatialLayerEntity::class, SpatialFeatureEntity::class, MapTileEntity::class],
    version = 2,
    exportSchema = false
)
abstract class GeoSurveiDatabase : RoomDatabase() {
    abstract fun spatialDao(): SpatialDao
    abstract fun mapTileDao(): MapTileDao

    companion object {
        @Volatile
        private var INSTANCE: GeoSurveiDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `map_tiles` (
                        `layerId` INTEGER NOT NULL,
                        `zoom` INTEGER NOT NULL,
                        `tileX` INTEGER NOT NULL,
                        `tileY` INTEGER NOT NULL,
                        `tileData` BLOB NOT NULL,
                        PRIMARY KEY(`layerId`, `zoom`, `tileX`, `tileY`),
                        FOREIGN KEY(`layerId`) REFERENCES `spatial_layers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_map_tiles_layerId_zoom` ON `map_tiles` (`layerId`, `zoom`)"
                )
            }
        }

        fun getInstance(context: Context): GeoSurveiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GeoSurveiDatabase::class.java,
                    "geosurvei_offline.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
