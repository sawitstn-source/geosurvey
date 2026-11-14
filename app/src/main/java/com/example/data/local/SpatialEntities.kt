package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "spatial_layers")
data class SpatialLayerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceType: String,
    val colorHex: String,
    val isVisible: Boolean = true,
    val isEditable: Boolean = false,
    val minLat: Double = -85.0,
    val maxLat: Double = 85.0,
    val minLon: Double = -180.0,
    val maxLon: Double = 180.0,
    val localFilePath: String? = null,
    val drawableResName: String? = null,
    val minZoom: Int = 2,
    val maxZoom: Int = 20,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "spatial_features",
    foreignKeys = [
        ForeignKey(
            entity = SpatialLayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["layerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["layerId"])]
)
data class SpatialFeatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val layerId: Long,
    val geometryType: String,
    val name: String,
    val description: String = "",
    val category: String = SurveyCategory.UMUM.name,
    val coordinatesJson: String,
    val attributesJson: String = "{}",
    val photoUri: String? = null,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val accuracyMeters: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "map_tiles",
    primaryKeys = ["layerId", "zoom", "tileX", "tileY"],
    foreignKeys = [
        ForeignKey(
            entity = SpatialLayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["layerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["layerId", "zoom"])]
)
data class MapTileEntity(
    val layerId: Long,
    val zoom: Int,
    val tileX: Int,
    val tileY: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val tileData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapTileEntity) return false
        return layerId == other.layerId &&
            zoom == other.zoom &&
            tileX == other.tileX &&
            tileY == other.tileY &&
            tileData.contentEquals(other.tileData)
    }

    override fun hashCode(): Int {
        var result = layerId.hashCode()
        result = 31 * result + zoom
        result = 31 * result + tileX
        result = 31 * result + tileY
        result = 31 * result + tileData.contentHashCode()
        return result
    }
}

data class LayerTileCount(
    val layerId: Long,
    val tileCount: Int
)
