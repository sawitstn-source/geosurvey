package com.example.spatial

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.data.local.LatLon
import com.example.data.local.MapBounds
import com.example.data.local.MapTileDao
import com.example.data.local.MapTileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PdfInspectionResult(
    val pageCount: Int,
    val detectedGeoPdfBounds: MapBounds?
)

object PdfMapProcessor {

    suspend fun inspectPdf(pdfFile: File): PdfInspectionResult = withContext(Dispatchers.IO) {
        val pageCount = runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            }
        }.getOrDefault(1)

        val detectedBounds = extractGeoPdfBounds(pdfFile)
        PdfInspectionResult(pageCount = pageCount.coerceAtLeast(1), detectedGeoPdfBounds = detectedBounds)
    }

    suspend fun renderPdfPageToPng(
        pdfFile: File,
        pageIndex: Int,
        outPngFile: File,
        maxEdgePx: Int = 2048
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val safePage = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                    renderer.openPage(safePage).use { page ->
                        val scale = min(
                            maxEdgePx.toFloat() / max(1, page.width),
                            maxEdgePx.toFloat() / max(1, page.height)
                        ).coerceIn(0.5f, 4.0f)
                        val w = (page.width * scale).roundToInt().coerceIn(256, maxEdgePx)
                        val h = (page.height * scale).roundToInt().coerceIn(256, maxEdgePx)

                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        outPngFile.outputStream().use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                        }
                        bmp.recycle()
                        true
                    }
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Scans a PDF file for ISO 32000-1 GeoPDF /GPTS [lat1 lon1 lat2 lon2 ...] geographic coordinates.
     */
    private fun extractGeoPdfBounds(pdfFile: File): MapBounds? {
        return runCatching {
            val maxBytes = min(pdfFile.length(), 2L * 1024L * 1024L).toInt()
            val bytes = ByteArray(maxBytes)
            pdfFile.inputStream().use { it.read(bytes) }
            val text = String(bytes, Charsets.ISO_8859_1)

            val gptsRegex = Regex("/GPTS\\s*\\[([^\\]]+)\\]")
            val match = gptsRegex.find(text) ?: return@runCatching null
            val numbers = Regex("[-+]?\\d+(?:\\.\\d+)?")
                .findAll(match.groupValues[1])
                .mapNotNull { it.value.toDoubleOrNull() }
                .toList()

            if (numbers.size < 4) return@runCatching null
            val pts = mutableListOf<LatLon>()
            var i = 0
            while (i + 1 < numbers.size) {
                val lat = numbers[i]
                val lon = numbers[i + 1]
                if (lat in -85.0..85.0 && lon in -180.0..180.0) {
                    pts.add(LatLon(lat, lon))
                }
                i += 2
            }
            GeoMath.computeBounds(pts)?.takeIf { it.isValid && (it.maxLat > it.minLat) && (it.maxLon > it.minLon) }
        }.getOrNull()
    }

    /**
     * Slices a georeferenced bitmap (such as a rendered PDF map) into 256x256 Web Mercator PNG tiles
     * stored in Room `map_tiles` for unified offline basemap tile viewing.
     */
    suspend fun generateRoomTilesFromBitmap(
        bitmap: Bitmap,
        bounds: MapBounds,
        layerId: Long,
        mapTileDao: MapTileDao,
        maxTotalTiles: Int = 240
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (!bounds.isValid) return@withContext 12 to 15

        val minNormX = GeoMath.lonToNormX(bounds.minLon)
        val maxNormX = GeoMath.lonToNormX(bounds.maxLon)
        val minNormY = GeoMath.latToNormY(bounds.maxLat) // top
        val maxNormY = GeoMath.latToNormY(bounds.minLat) // bottom

        val lonSpan = (maxNormX - minNormX).coerceAtLeast(1e-6)
        val idealZoom = (ln(2.0 / lonSpan) / ln(2.0)).roundToInt().coerceIn(8, 16)
        val minZ = (idealZoom - 1).coerceIn(6, 17)
        val maxZ = (idealZoom + 2).coerceIn(minZ, 18)

        val tileSize = 256
        val tilePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val batch = mutableListOf<MapTileEntity>()
        var created = 0
        var highestZoomReached = minZ

        for (z in minZ..maxZ) {
            val nTiles = 1 shl z
            val xStart = floor(minNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
            val xEnd = floor(maxNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
            val yStart = floor(minNormY * nTiles).toInt().coerceIn(0, nTiles - 1)
            val yEnd = floor(maxNormY * nTiles).toInt().coerceIn(0, nTiles - 1)

            val levelCount = (xEnd - xStart + 1) * (yEnd - yStart + 1)
            if (created > 0 && created + levelCount > maxTotalTiles) break

            highestZoomReached = z
            for (tx in xStart..xEnd) {
                for (ty in yStart..yEnd) {
                    val tileLeftNorm = tx.toDouble() / nTiles
                    val tileRightNorm = (tx + 1).toDouble() / nTiles
                    val tileTopNorm = ty.toDouble() / nTiles
                    val tileBottomNorm = (ty + 1).toDouble() / nTiles

                    val interLeftNorm = max(tileLeftNorm, minNormX)
                    val interRightNorm = min(tileRightNorm, maxNormX)
                    val interTopNorm = max(tileTopNorm, minNormY)
                    val interBottomNorm = min(tileBottomNorm, maxNormY)
                    if (interRightNorm <= interLeftNorm || interBottomNorm <= interTopNorm) continue

                    val srcLeft = (((interLeftNorm - minNormX) / (maxNormX - minNormX)) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val srcRight = (((interRightNorm - minNormX) / (maxNormX - minNormX)) * bitmap.width).roundToInt().coerceIn(srcLeft + 1, bitmap.width)
                    val srcTop = (((interTopNorm - minNormY) / (maxNormY - minNormY)) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val srcBottom = (((interBottomNorm - minNormY) / (maxNormY - minNormY)) * bitmap.height).roundToInt().coerceIn(srcTop + 1, bitmap.height)

                    val dstLeft = (((interLeftNorm - tileLeftNorm) / (tileRightNorm - tileLeftNorm)) * tileSize).toFloat()
                    val dstRight = (((interRightNorm - tileLeftNorm) / (tileRightNorm - tileLeftNorm)) * tileSize).toFloat()
                    val dstTop = (((interTopNorm - tileTopNorm) / (tileBottomNorm - tileTopNorm)) * tileSize).toFloat()
                    val dstBottom = (((interBottomNorm - tileTopNorm) / (tileBottomNorm - tileTopNorm)) * tileSize).toFloat()

                    val tileBmp = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(tileBmp)
                    canvas.drawBitmap(
                        bitmap,
                        Rect(srcLeft, srcTop, srcRight, srcBottom),
                        RectF(dstLeft, dstTop, dstRight, dstBottom),
                        tilePaint
                    )
                    val baos = ByteArrayOutputStream()
                    tileBmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
                    tileBmp.recycle()

                    batch.add(
                        MapTileEntity(
                            layerId = layerId,
                            zoom = z,
                            tileX = tx,
                            tileY = ty,
                            tileData = baos.toByteArray()
                        )
                    )
                    created++
                    if (batch.size >= 80) {
                        mapTileDao.insertTiles(batch.toList())
                        batch.clear()
                    }
                }
            }
        }
        if (batch.isNotEmpty()) {
            mapTileDao.insertTiles(batch)
        }
        minZ to highestZoomReached
    }
}
