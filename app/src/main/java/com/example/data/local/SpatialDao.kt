package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpatialDao {
    @Query("SELECT * FROM spatial_layers ORDER BY isEditable DESC, createdAt DESC")
    fun observeAllLayers(): Flow<List<SpatialLayerEntity>>

    @Query("SELECT * FROM spatial_layers WHERE id = :layerId LIMIT 1")
    suspend fun getLayerById(layerId: Long): SpatialLayerEntity?

    @Query("SELECT * FROM spatial_layers WHERE isEditable = 1 ORDER BY createdAt ASC LIMIT 1")
    suspend fun getDefaultEditableLayer(): SpatialLayerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLayer(layer: SpatialLayerEntity): Long

    @Update
    suspend fun updateLayer(layer: SpatialLayerEntity)

    @Query("UPDATE spatial_layers SET isVisible = :visible WHERE id = :layerId")
    suspend fun setLayerVisibility(layerId: Long, visible: Boolean)

    @Query("UPDATE spatial_layers SET isVisible = :visible WHERE id IN (:layerIds)")
    suspend fun setLayersVisibility(layerIds: List<Long>, visible: Boolean)

    @Query("UPDATE spatial_layers SET colorHex = :colorHex WHERE id = :layerId")
    suspend fun setLayerColor(layerId: Long, colorHex: String)

    @Query("DELETE FROM spatial_layers WHERE id = :layerId")
    suspend fun deleteLayerById(layerId: Long)

    @Query("SELECT * FROM spatial_features ORDER BY createdAt DESC")
    fun observeAllFeatures(): Flow<List<SpatialFeatureEntity>>

    @Query("SELECT * FROM spatial_features WHERE layerId = :layerId ORDER BY createdAt DESC")
    suspend fun getFeaturesForLayer(layerId: Long): List<SpatialFeatureEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeature(feature: SpatialFeatureEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeatures(features: List<SpatialFeatureEntity>)

    @Update
    suspend fun updateFeature(feature: SpatialFeatureEntity)

    @Query("DELETE FROM spatial_features WHERE id = :featureId")
    suspend fun deleteFeatureById(featureId: Long)

    @Transaction
    suspend fun insertLayerWithFeatures(
        layer: SpatialLayerEntity,
        features: List<SpatialFeatureEntity>
    ): Long {
        val layerId = insertLayer(layer)
        if (features.isNotEmpty()) {
            insertFeatures(features.map { it.copy(layerId = layerId) })
        }
        return layerId
    }
}

@Dao
interface MapTileDao {
    @Query("SELECT tileData FROM map_tiles WHERE layerId = :layerId AND zoom = :zoom AND tileX = :tileX AND tileY = :tileY LIMIT 1")
    suspend fun getTileData(layerId: Long, zoom: Int, tileX: Int, tileY: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTile(tile: MapTileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTiles(tiles: List<MapTileEntity>)

    @Query("SELECT layerId, COUNT(*) AS tileCount FROM map_tiles GROUP BY layerId")
    fun observeTileCountsByLayer(): Flow<List<LayerTileCount>>

    @Query("SELECT COUNT(*) FROM map_tiles WHERE layerId = :layerId")
    suspend fun getTileCount(layerId: Long): Int

    @Query("SELECT DISTINCT zoom FROM map_tiles WHERE layerId = :layerId ORDER BY zoom ASC")
    suspend fun getAvailableZooms(layerId: Long): List<Int>

    @Query("DELETE FROM map_tiles WHERE layerId = :layerId")
    suspend fun deleteTilesForLayer(layerId: Long)
}
