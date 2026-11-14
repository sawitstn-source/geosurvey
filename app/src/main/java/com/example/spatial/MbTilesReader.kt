package com.example.spatial

import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.local.MapTileDao
import com.example.data.local.MapTileEntity
import com.example.data.local.SpatialLayerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MbTilesReader(private val mapTileDao: MapTileDao) {
    private val dbCache = mutableMapOf<String, SQLiteDatabase>()
    private val tileBitmapCache = LruCache<String, ImageBitmap>(220)
    private val missingTiles = mutableSetOf<String>()

    @Synchronized
    private fun getDatabase(path: String): SQLiteDatabase? {
        val existing = dbCache[path]
        if (existing != null && existing.isOpen) return existing
        if (!File(path).exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).also {
                dbCache[path] = it
            }
        }.getOrNull()
    }

    fun getCachedTile(layerId: Long, z: Int, x: Int, y: Int): ImageBitmap? {
        return tileBitmapCache.get("$layerId:$z:$x:$y")
    }

    fun isKnownMissing(layerId: Long, z: Int, x: Int, y: Int): Boolean {
        return synchronized(missingTiles) {
            missingTiles.contains("$layerId:$z:$x:$y")
        }
    }

    fun clearMissingForLayer(layerId: Long) {
        synchronized(missingTiles) {
            missingTiles.removeAll { it.startsWith("$layerId:") }
        }
    }

    /**
     * Ingests tiles from an imported .mbtiles SQLite container directly into Room's `map_tiles` table.
     */
    suspend fun ingestMbTilesIntoRoom(
        mbtilesPath: String,
        layerId: Long,
        maxBulkTiles: Int = 3500
    ): Int = withContext(Dispatchers.IO) {
        val db = getDatabase(mbtilesPath) ?: return@withContext 0
        var totalInserted = 0
        val batch = mutableListOf<MapTileEntity>()

        runCatching {
            db.rawQuery(
                "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles ORDER BY zoom_level ASC LIMIT ?",
                arrayOf(maxBulkTiles.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val z = cursor.getInt(0).coerceIn(0, 22)
                    val x = cursor.getInt(1)
                    val rawRow = cursor.getInt(2)
                    val data = cursor.getBlob(3) ?: continue
                    val maxTileIndex = (1 shl z) - 1
                    // Standard MBTiles stores TMS row; normalize to XYZ tileY for Room & Canvas
                    val xyzY = (maxTileIndex - rawRow).coerceIn(0, maxTileIndex)
                    batch.add(
                        MapTileEntity(
                            layerId = layerId,
                            zoom = z,
                            tileX = x,
                            tileY = xyzY,
                            tileData = data
                        )
                    )
                    totalInserted++
                    if (batch.size >= 150) {
                        mapTileDao.insertTiles(batch.toList())
                        batch.clear()
                    }
                }
            }
            if (batch.isNotEmpty()) {
                mapTileDao.insertTiles(batch)
            }
        }
        clearMissingForLayer(layerId)
        totalInserted
    }

    /**
     * Loads a tile for canvas rendering: memory LRU cache -> Room `map_tiles` -> optional SQLite fallback (auto-cached into Room).
     */
    suspend fun loadTile(layer: SpatialLayerEntity, z: Int, x: Int, xyzY: Int): ImageBitmap? {
        val key = "${layer.id}:$z:$x:$xyzY"
        tileBitmapCache.get(key)?.let { return it }
        if (isKnownMissing(layer.id, z, x, xyzY)) return null

        return withContext(Dispatchers.IO) {
            // 1. Primary lookup from Room database
            var bytes = mapTileDao.getTileData(layer.id, z, x, xyzY)

            // 2. Fallback to external .mbtiles SQLite if present (and persist into Room)
            if (bytes == null && !layer.localFilePath.isNullOrBlank() && layer.localFilePath.endsWith(".mbtiles", ignoreCase = true)) {
                val db = getDatabase(layer.localFilePath)
                if (db != null) {
                    val maxTileIndex = (1 shl z) - 1
                    val tmsY = maxTileIndex - xyzY
                    bytes = queryTileBytes(db, z, x, tmsY) ?: queryTileBytes(db, z, x, xyzY)
                    if (bytes != null) {
                        mapTileDao.insertTile(
                            MapTileEntity(
                                layerId = layer.id,
                                zoom = z,
                                tileX = x,
                                tileY = xyzY,
                                tileData = bytes
                            )
                        )
                    }
                }
            }

            if (bytes == null) {
                synchronized(missingTiles) {
                    if (missingTiles.size > 2000) missingTiles.clear()
                    missingTiles.add(key)
                }
                return@withContext null
            }

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            if (bitmap != null) {
                tileBitmapCache.put(key, bitmap)
            }
            bitmap
        }
    }

    private fun queryTileBytes(db: SQLiteDatabase, z: Int, x: Int, y: Int): ByteArray? {
        return runCatching {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ? LIMIT 1",
                arrayOf(z.toString(), x.toString(), y.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else null
            }
        }.getOrNull()
    }

    @Synchronized
    fun closeAll() {
        dbCache.values.forEach { runCatching { it.close() } }
        dbCache.clear()
        tileBitmapCache.evictAll()
    }
}
