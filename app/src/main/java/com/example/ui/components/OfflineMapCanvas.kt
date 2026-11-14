package com.example.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.*
import com.example.spatial.GeoMath
import com.example.spatial.MbTilesReader
import com.example.ui.ActiveTrackState
import com.example.ui.MapViewportState
import com.example.ui.theme.AmberGold
import com.example.ui.theme.GpsBlue
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
fun OfflineMapCanvas(
    viewport: MapViewportState,
    layers: List<SpatialLayerEntity>,
    features: List<SpatialFeatureEntity>,
    gpsState: LiveGpsState,
    toolMode: MapToolMode,
    draftVertices: List<LatLon>,
    activeTrack: ActiveTrackState,
    selectedFeature: SpatialFeatureEntity?,
    distanceUnit: DistanceUnit,
    showGridLines: Boolean,
    showCrosshair: Boolean,
    isDarkMap: Boolean,
    mbTilesReader: MbTilesReader,
    getRasterBitmap: (SpatialLayerEntity) -> ImageBitmap?,
    onPanZoom: (dx: Float, dy: Float, zoomFactor: Float, width: Float, height: Float) -> Unit,
    onMapTap: (LatLon, Offset, Float, Float) -> Unit,
    onMapLongPress: (LatLon) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var tileTick by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val visibleLayerMap = remember(layers) {
        layers.filter { it.isVisible }.associateBy { it.id }
    }
    val visibleFeatures = remember(features, visibleLayerMap) {
        features.filter { visibleLayerMap.containsKey(it.layerId) }
    }
    val parsedGeometries = remember(visibleFeatures) {
        visibleFeatures.associate { it.id to GeoMath.jsonToRings(it.coordinatesJson) }
    }

    val tileLayers = remember(layers) {
        layers.filter {
            it.isVisible && (
                it.sourceType == LayerSourceType.MBTILES.name ||
                    it.sourceType == LayerSourceType.PDF.name ||
                    it.sourceType == LayerSourceType.RASTER.name
                )
        }
    }
    val rasterLayers = remember(layers) {
        layers.filter {
            it.isVisible && (
                it.sourceType == LayerSourceType.RASTER.name ||
                    it.sourceType == LayerSourceType.PDF.name
                )
        }
    }

    // Trigger async loading of visible Room / MBTiles tiles when viewport changes
    LaunchedEffect(viewport.centerLat, viewport.centerLon, viewport.zoom, canvasSize, tileLayers) {
        if (canvasSize.width == 0 || canvasSize.height == 0 || tileLayers.isEmpty()) return@LaunchedEffect
        val tileZ = viewport.zoom.roundToInt().coerceIn(0, 19)
        val nTiles = 1 shl tileZ
        val worldPx = 256.0 * 2.0.pow(viewport.zoom)
        val centerNormX = GeoMath.lonToNormX(viewport.centerLon)
        val centerNormY = GeoMath.latToNormY(viewport.centerLat)

        val minNormX = (centerNormX - (canvasSize.width / 2.0) / worldPx).coerceIn(0.0, 0.99999)
        val maxNormX = (centerNormX + (canvasSize.width / 2.0) / worldPx).coerceIn(0.0, 0.99999)
        val minNormY = (centerNormY - (canvasSize.height / 2.0) / worldPx).coerceIn(0.0, 0.99999)
        val maxNormY = (centerNormY + (canvasSize.height / 2.0) / worldPx).coerceIn(0.0, 0.99999)

        val minTileX = floor(minNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
        val maxTileX = floor(maxNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
        val minTileY = floor(minNormY * nTiles).toInt().coerceIn(0, nTiles - 1)
        val maxTileY = floor(maxNormY * nTiles).toInt().coerceIn(0, nTiles - 1)

        for (layer in tileLayers) {
            val z = tileZ.coerceIn(layer.minZoom, layer.maxZoom)
            val zTiles = 1 shl z
            val scaleShift = z - tileZ
            var xStart = if (scaleShift >= 0) minTileX shl scaleShift else minTileX shr -scaleShift
            var xEnd = if (scaleShift >= 0) maxTileX shl scaleShift else maxTileX shr -scaleShift
            var yStart = if (scaleShift >= 0) minTileY shl scaleShift else minTileY shr -scaleShift
            var yEnd = if (scaleShift >= 0) maxTileY shl scaleShift else maxTileY shr -scaleShift

            if (layer.maxLat > layer.minLat && layer.maxLon > layer.minLon) {
                val layerMinX = floor(GeoMath.lonToNormX(layer.minLon) * zTiles).toInt()
                val layerMaxX = floor(GeoMath.lonToNormX(layer.maxLon) * zTiles).toInt()
                val layerMinY = floor(GeoMath.latToNormY(layer.maxLat) * zTiles).toInt()
                val layerMaxY = floor(GeoMath.latToNormY(layer.minLat) * zTiles).toInt()
                xStart = maxOf(xStart, layerMinX)
                xEnd = minOf(xEnd, layerMaxX)
                yStart = maxOf(yStart, layerMinY)
                yEnd = minOf(yEnd, layerMaxY)
            }
            if (xStart > xEnd || yStart > yEnd) continue

            for (tx in xStart.coerceIn(0, zTiles - 1)..xEnd.coerceIn(0, zTiles - 1)) {
                for (ty in yStart.coerceIn(0, zTiles - 1)..yEnd.coerceIn(0, zTiles - 1)) {
                    if (mbTilesReader.getCachedTile(layer.id, z, tx, ty) == null &&
                        !mbTilesReader.isKnownMissing(layer.id, z, tx, ty)
                    ) {
                        coroutineScope.launch {
                            val loaded = mbTilesReader.loadTile(layer, z, tx, ty)
                            if (loaded != null) tileTick++
                        }
                    }
                }
            }
        }
    }

    val bgColor = if (isDarkMap) Color(0xFF0E1714) else Color(0xFFF1EEE4)
    val gridColor = if (isDarkMap) Color(0xFF283E37) else Color(0xFFD5D1C5)
    val gridTextColor = if (isDarkMap) android.graphics.Color.argb(175, 175, 200, 190) else android.graphics.Color.argb(180, 75, 88, 82)
    val labelBgColor = if (isDarkMap) android.graphics.Color.argb(215, 20, 34, 30) else android.graphics.Color.argb(225, 252, 251, 248)
    val labelTextColor = if (isDarkMap) android.graphics.Color.rgb(230, 240, 236) else android.graphics.Color.rgb(22, 29, 27)

    val labelPaint = remember(labelTextColor, density) {
        Paint().apply {
            isAntiAlias = true
            color = labelTextColor
            textSize = with(density) { 11.5.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val labelBgPaint = remember(labelBgColor) {
        Paint().apply {
            isAntiAlias = true
            color = labelBgColor
            style = Paint.Style.FILL
        }
    }
    val gridPaint = remember(gridTextColor, density) {
        Paint().apply {
            isAntiAlias = true
            color = gridTextColor
            textSize = with(density) { 9.5.sp.toPx() }
            typeface = Typeface.MONOSPACE
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged { canvasSize = it }
    ) {
        val currentViewport by rememberUpdatedState(viewport)
        val currentCanvasSize by rememberUpdatedState(canvasSize)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onPanZoom(
                            pan.x,
                            pan.y,
                            zoom,
                            currentCanvasSize.width.toFloat(),
                            currentCanvasSize.height.toFloat()
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            val w = currentCanvasSize.width.toFloat()
                            val h = currentCanvasSize.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectTapGestures
                            val vp = currentViewport
                            val latLon = screenToLatLon(offset.x, offset.y, vp, w, h)
                            onMapTap(latLon, offset, w, h)
                        },
                        onLongPress = { offset ->
                            val w = currentCanvasSize.width.toFloat()
                            val h = currentCanvasSize.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectTapGestures
                            val vp = currentViewport
                            val latLon = screenToLatLon(offset.x, offset.y, vp, w, h)
                            onMapLongPress(latLon)
                        }
                    )
                }
        ) {
            // Read tileTick to invalidate canvas when async MBTiles finish decoding
            @Suppress("UNUSED_EXPRESSION")
            tileTick

            val w = size.width
            val h = size.height
            val worldPx = 256.0 * 2.0.pow(viewport.zoom)
            val centerNormX = GeoMath.lonToNormX(viewport.centerLon)
            val centerNormY = GeoMath.latToNormY(viewport.centerLat)

            fun toScreen(lat: Double, lon: Double): Offset {
                val nx = GeoMath.lonToNormX(lon)
                val ny = GeoMath.latToNormY(lat)
                val sx = (w / 2.0 + (nx - centerNormX) * worldPx).toFloat()
                val sy = (h / 2.0 + (ny - centerNormY) * worldPx).toFloat()
                return Offset(sx, sy)
            }

            // 1. Georeferenced Raster Basemaps
            for (raster in rasterLayers) {
                val bmp = getRasterBitmap(raster) ?: continue
                val topLeft = toScreen(raster.maxLat, raster.minLon)
                val bottomRight = toScreen(raster.minLat, raster.maxLon)
                val dstW = (bottomRight.x - topLeft.x).roundToInt()
                val dstH = (bottomRight.y - topLeft.y).roundToInt()
                if (dstW > 4 && dstH > 4 &&
                    bottomRight.x >= 0f && topLeft.x <= w &&
                    bottomRight.y >= 0f && topLeft.y <= h
                ) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                        dstSize = IntSize(dstW, dstH),
                        alpha = 0.94f
                    )
                    // Subtle map sheet border
                    drawRect(
                        color = Color(0xFF1B6B52).copy(alpha = 0.45f),
                        topLeft = topLeft,
                        size = androidx.compose.ui.geometry.Size(dstW.toFloat(), dstH.toFloat()),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // 2. Room & MBTiles Offline Tile Layers
            for (tileLayer in tileLayers) {
                val z = viewport.zoom.roundToInt().coerceIn(tileLayer.minZoom, tileLayer.maxZoom)
                val nTiles = 1 shl z
                val minNormX = (centerNormX - (w / 2.0) / worldPx).coerceIn(0.0, 0.99999)
                val maxNormX = (centerNormX + (w / 2.0) / worldPx).coerceIn(0.0, 0.99999)
                val minNormY = (centerNormY - (h / 2.0) / worldPx).coerceIn(0.0, 0.99999)
                val maxNormY = (centerNormY + (h / 2.0) / worldPx).coerceIn(0.0, 0.99999)

                var minTileX = floor(minNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
                var maxTileX = floor(maxNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
                var minTileY = floor(minNormY * nTiles).toInt().coerceIn(0, nTiles - 1)
                var maxTileY = floor(maxNormY * nTiles).toInt().coerceIn(0, nTiles - 1)

                if (tileLayer.maxLat > tileLayer.minLat && tileLayer.maxLon > tileLayer.minLon) {
                    minTileX = maxOf(minTileX, floor(GeoMath.lonToNormX(tileLayer.minLon) * nTiles).toInt()).coerceIn(0, nTiles - 1)
                    maxTileX = minOf(maxTileX, floor(GeoMath.lonToNormX(tileLayer.maxLon) * nTiles).toInt()).coerceIn(0, nTiles - 1)
                    minTileY = maxOf(minTileY, floor(GeoMath.latToNormY(tileLayer.maxLat) * nTiles).toInt()).coerceIn(0, nTiles - 1)
                    maxTileY = minOf(maxTileY, floor(GeoMath.latToNormY(tileLayer.minLat) * nTiles).toInt()).coerceIn(0, nTiles - 1)
                }
                if (minTileX > maxTileX || minTileY > maxTileY) continue

                val tileScreenSize = (worldPx / nTiles).toFloat()
                for (tx in minTileX..maxTileX) {
                    for (ty in minTileY..maxTileY) {
                        val bmp = mbTilesReader.getCachedTile(tileLayer.id, z, tx, ty) ?: continue
                        val sx = (w / 2.0 + (tx.toDouble() / nTiles - centerNormX) * worldPx).toFloat()
                        val sy = (h / 2.0 + (ty.toDouble() / nTiles - centerNormY) * worldPx).toFloat()
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
                            dstSize = IntSize(ceil(tileScreenSize).toInt() + 1, ceil(tileScreenSize).toInt() + 1)
                        )
                    }
                }
            }

            // 3. Adaptive Graticule Coordinate Grid
            if (showGridLines) {
                drawGraticuleGrid(
                    viewport = viewport,
                    w = w,
                    h = h,
                    worldPx = worldPx,
                    centerNormX = centerNormX,
                    centerNormY = centerNormY,
                    gridColor = gridColor,
                    gridTextPaint = gridPaint,
                    toScreen = ::toScreen
                )
            }

            // 4. Vector Polygons first, then Polylines, then Points
            val polygons = visibleFeatures.filter { it.geometryType == GeometryType.POLYGON.name }
            val lines = visibleFeatures.filter { it.geometryType == GeometryType.LINESTRING.name }
            val points = visibleFeatures.filter { it.geometryType == GeometryType.POINT.name }

            for (feat in polygons) {
                val layerColor = parseHexColor(visibleLayerMap[feat.layerId]?.colorHex, Color(0xFF1B6B52))
                val isSelected = selectedFeature?.id == feat.id
                val rings = parsedGeometries[feat.id].orEmpty()
                for (ring in rings) {
                    if (ring.size < 3) continue
                    val path = Path()
                    ring.forEachIndexed { idx, pt ->
                        val p = toScreen(pt.latitude, pt.longitude)
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(
                        path = path,
                        color = if (isSelected) AmberGold.copy(alpha = 0.34f) else layerColor.copy(alpha = 0.22f)
                    )
                    drawPath(
                        path = path,
                        color = if (isSelected) AmberGold else layerColor,
                        style = Stroke(width = if (isSelected) 3.2.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                if (viewport.zoom >= 12.5) {
                    val c = toScreen(feat.latitude, feat.longitude)
                    if (c.x in -60f..w + 60f && c.y in -60f..h + 60f) {
                        drawPillLabel(feat.name, c, labelPaint, labelBgPaint, density.density)
                    }
                }
            }

            for (feat in lines) {
                val layerColor = parseHexColor(visibleLayerMap[feat.layerId]?.colorHex, Color(0xFFD97706))
                val isSelected = selectedFeature?.id == feat.id
                val parts = parsedGeometries[feat.id].orEmpty()
                for (part in parts) {
                    if (part.size < 2) continue
                    val path = Path()
                    part.forEachIndexed { idx, pt ->
                        val p = toScreen(pt.latitude, pt.longitude)
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.7f),
                        style = Stroke(width = if (isSelected) 6.5.dp.toPx() else 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = path,
                        color = if (isSelected) AmberGold else layerColor,
                        style = Stroke(width = if (isSelected) 4.dp.toPx() else 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            for (feat in points) {
                val cat = runCatching { SurveyCategory.valueOf(feat.category) }.getOrDefault(SurveyCategory.UMUM)
                val pinColor = parseHexColor(cat.defaultColorHex, Color(0xFF1B6B52))
                val isSelected = selectedFeature?.id == feat.id
                val pos = toScreen(feat.latitude, feat.longitude)
                if (pos.x !in -50f..w + 50f || pos.y !in -50f..h + 50f) continue

                if (isSelected) {
                    drawCircle(
                        color = AmberGold.copy(alpha = 0.28f),
                        radius = 20.dp.toPx(),
                        center = pos
                    )
                }
                drawCircle(
                    color = Color.White,
                    radius = if (isSelected) 11.dp.toPx() else 9.dp.toPx(),
                    center = pos
                )
                drawCircle(
                    color = if (isSelected) AmberGold else pinColor,
                    radius = if (isSelected) 8.dp.toPx() else 6.5.dp.toPx(),
                    center = pos
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.2.dp.toPx(),
                    center = pos
                )
                if (viewport.zoom >= 11.5 || isSelected) {
                    drawPillLabel(
                        feat.name,
                        Offset(pos.x, pos.y + 18.dp.toPx()),
                        labelPaint,
                        labelBgPaint,
                        density.density
                    )
                }
            }

            // 5. Active GPS Track Recording Trail
            if (activeTrack.isRecording && activeTrack.points.isNotEmpty()) {
                val trackPath = Path()
                activeTrack.points.forEachIndexed { idx, pt ->
                    val p = toScreen(pt.latitude, pt.longitude)
                    if (idx == 0) trackPath.moveTo(p.x, p.y) else trackPath.lineTo(p.x, p.y)
                }
                if (activeTrack.points.size >= 2) {
                    drawPath(
                        path = trackPath,
                        color = Color(0xFFEA580C),
                        style = Stroke(
                            width = 4.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
                        )
                    )
                }
                activeTrack.points.firstOrNull()?.let { start ->
                    val sp = toScreen(start.latitude, start.longitude)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = sp)
                    drawCircle(Color(0xFFEA580C), radius = 4.dp.toPx(), center = sp)
                }
            }

            // 6. Draft Measure / Draw Overlay
            if (draftVertices.isNotEmpty()) {
                val isPolygon = toolMode == MapToolMode.MEASURE_AREA || toolMode == MapToolMode.DRAW_POLYGON
                val screenPts = draftVertices.map { toScreen(it.latitude, it.longitude) }
                val draftPath = Path()
                screenPts.forEachIndexed { idx, p ->
                    if (idx == 0) draftPath.moveTo(p.x, p.y) else draftPath.lineTo(p.x, p.y)
                }
                if (isPolygon && screenPts.size >= 3) {
                    draftPath.close()
                    drawPath(draftPath, color = Color(0xFF0284C7).copy(alpha = 0.24f))
                }
                if (screenPts.size >= 2) {
                    drawPath(
                        path = draftPath,
                        color = Color(0xFF0284C7),
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
                        )
                    )
                }
                screenPts.forEachIndexed { idx, p ->
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = p)
                    drawCircle(Color(0xFF0284C7), radius = 5.dp.toPx(), center = p)
                    drawPillLabel("${idx + 1}", Offset(p.x, p.y - 14.dp.toPx()), labelPaint, labelBgPaint, density.density)
                }
            }

            // 7. Live GPS Position Marker
            if (gpsState.hasFix && gpsState.latitude != null && gpsState.longitude != null) {
                val gpsPos = toScreen(gpsState.latitude, gpsState.longitude)
                val metersPerPx = (156543.03392 * cos(Math.toRadians(gpsState.latitude)) / 2.0.pow(viewport.zoom)).coerceAtLeast(0.01)
                val accRadiusPx = ((gpsState.accuracyMeters ?: 8f) / metersPerPx).toFloat().coerceIn(14f, 180f)

                drawCircle(
                    color = GpsBlue.copy(alpha = 0.14f),
                    radius = accRadiusPx,
                    center = gpsPos
                )
                drawCircle(
                    color = GpsBlue.copy(alpha = 0.45f),
                    radius = accRadiusPx,
                    center = gpsPos,
                    style = Stroke(width = 1.2.dp.toPx())
                )
                drawCircle(Color.White, radius = 9.5.dp.toPx(), center = gpsPos)
                drawCircle(GpsBlue, radius = 6.5.dp.toPx(), center = gpsPos)
            }

            // 8. Center Surveyor Crosshair
            if (showCrosshair) {
                val cx = w / 2f
                val cy = h / 2f
                val arm = 14.dp.toPx()
                val gap = 4.dp.toPx()
                val chColor = if (isDarkMap) Color.White.copy(alpha = 0.8f) else Color(0xFF16302B).copy(alpha = 0.8f)
                drawLine(chColor, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = 1.8.dp.toPx())
                drawLine(chColor, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = 1.8.dp.toPx())
                drawLine(chColor, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = 1.8.dp.toPx())
                drawLine(chColor, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = 1.8.dp.toPx())
                drawCircle(AmberGold, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
            }
        }

        // Dynamic Scale Bar overlay at bottom-start
        if (canvasSize.width > 0) {
            ScaleBarOverlay(
                centerLat = viewport.centerLat,
                zoom = viewport.zoom,
                unit = distanceUnit,
                isDarkMap = isDarkMap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
            )
        }
    }
}

@Composable
private fun ScaleBarOverlay(
    centerLat: Double,
    zoom: Double,
    unit: DistanceUnit,
    isDarkMap: Boolean,
    modifier: Modifier = Modifier
) {
    val metersPerPx = (156543.03392 * cos(Math.toRadians(centerLat)) / 2.0.pow(zoom)).coerceAtLeast(0.01)
    val targetPx = 180f
    val rawMeters = metersPerPx * targetPx

    val (displayText, barMeters) = remember(rawMeters, unit) {
        if (unit == DistanceUnit.METRIC) {
            val steps = listOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 25_000.0, 50_000.0, 100_000.0)
            val chosen = steps.lastOrNull { it <= rawMeters } ?: steps.first()
            val label = if (chosen >= 1000.0) "${(chosen / 1000.0).toInt()} km" else "${chosen.toInt()} m"
            label to chosen
        } else {
            val rawFeet = rawMeters * 3.28084
            val stepsFt = listOf(20.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2640.0, 5280.0, 10560.0, 26400.0, 52800.0, 264000.0)
            val chosenFt = stepsFt.lastOrNull { it <= rawFeet } ?: stepsFt.first()
            val label = if (chosenFt >= 5280.0) "${(chosenFt / 5280.0).toInt()} mi" else "${chosenFt.toInt()} ft"
            label to (chosenFt / 3.28084)
        }
    }

    val barDp = with(LocalDensity.current) { (barMeters / metersPerPx).toFloat().coerceIn(36f, 200f).toDp() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isDarkMap) Color(0xCC15221E) else Color(0xD9FCFBF8),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(5.dp)
                        .background(MaterialTheme.colorScheme.onSurface)
                )
                Box(
                    modifier = Modifier
                        .width(barDp)
                        .height(2.5.dp)
                        .background(MaterialTheme.colorScheme.onSurface)
                )
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(5.dp)
                        .background(MaterialTheme.colorScheme.onSurface)
                )
            }
        }
    }
}

private fun DrawScope.drawGraticuleGrid(
    viewport: MapViewportState,
    w: Float,
    h: Float,
    worldPx: Double,
    centerNormX: Double,
    centerNormY: Double,
    gridColor: Color,
    gridTextPaint: Paint,
    toScreen: (Double, Double) -> Offset
) {
    val topLeft = screenToLatLon(0f, 0f, viewport, w, h)
    val bottomRight = screenToLatLon(w, h, viewport, w, h)
    val minLon = min(topLeft.longitude, bottomRight.longitude)
    val maxLon = max(topLeft.longitude, bottomRight.longitude)
    val minLat = min(topLeft.latitude, bottomRight.latitude)
    val maxLat = max(topLeft.latitude, bottomRight.latitude)

    val step = when {
        viewport.zoom >= 17 -> 0.001
        viewport.zoom >= 15 -> 0.005
        viewport.zoom >= 13 -> 0.01
        viewport.zoom >= 11 -> 0.05
        viewport.zoom >= 9 -> 0.1
        viewport.zoom >= 7 -> 0.5
        viewport.zoom >= 5 -> 2.0
        else -> 10.0
    }

    val startLon = floor(minLon / step) * step
    val startLat = floor(minLat / step) * step

    var lon = startLon
    var guard = 0
    while (lon <= maxLon && guard < 30) {
        val p1 = toScreen(maxLat, lon)
        val p2 = toScreen(minLat, lon)
        drawLine(gridColor, Offset(p1.x, 0f), Offset(p2.x, h), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText(
            String.format(java.util.Locale.US, "%.3f°", lon),
            p1.x + 6f,
            34f,
            gridTextPaint
        )
        lon += step
        guard++
    }

    var lat = startLat
    guard = 0
    while (lat <= maxLat && guard < 30) {
        val p1 = toScreen(lat, minLon)
        val p2 = toScreen(lat, maxLon)
        drawLine(gridColor, Offset(0f, p1.y), Offset(w, p2.y), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText(
            String.format(java.util.Locale.US, "%.3f°", lat),
            10f,
            p1.y - 6f,
            gridTextPaint
        )
        lat += step
        guard++
    }
}

private fun DrawScope.drawPillLabel(
    text: String,
    center: Offset,
    textPaint: Paint,
    bgPaint: Paint,
    density: Float
) {
    val short = if (text.length > 22) text.take(20) + "…" else text
    val tw = textPaint.measureText(short)
    val padX = 6f * density
    val padY = 3f * density
    val rectLeft = center.x - tw / 2f - padX
    val rectRight = center.x + tw / 2f + padX
    val rectTop = center.y - 10f * density - padY
    val rectBottom = center.y + 4f * density + padY
    val radius = 6f * density

    drawContext.canvas.nativeCanvas.drawRoundRect(
        rectLeft,
        rectTop,
        rectRight,
        rectBottom,
        radius,
        radius,
        bgPaint
    )
    drawContext.canvas.nativeCanvas.drawText(short, center.x, center.y + 1.5f * density, textPaint)
}

fun screenToLatLon(
    screenX: Float,
    screenY: Float,
    viewport: MapViewportState,
    canvasWidth: Float,
    canvasHeight: Float
): LatLon {
    val worldPx = 256.0 * 2.0.pow(viewport.zoom)
    val centerNormX = GeoMath.lonToNormX(viewport.centerLon)
    val centerNormY = GeoMath.latToNormY(viewport.centerLat)

    val normX = (centerNormX + (screenX - canvasWidth / 2.0) / worldPx).coerceIn(0.0, 1.0)
    val normY = (centerNormY + (screenY - canvasHeight / 2.0) / worldPx).coerceIn(0.00001, 0.99999)
    return LatLon(GeoMath.normYToLat(normY), GeoMath.normXToLon(normX))
}

fun findNearestTappedFeature(
    tapOffset: Offset,
    viewport: MapViewportState,
    canvasWidth: Float,
    canvasHeight: Float,
    visibleFeatures: List<SpatialFeatureEntity>,
    hitRadiusPx: Float
): SpatialFeatureEntity? {
    val worldPx = 256.0 * 2.0.pow(viewport.zoom)
    val centerNormX = GeoMath.lonToNormX(viewport.centerLon)
    val centerNormY = GeoMath.latToNormY(viewport.centerLat)

    fun toScreen(lat: Double, lon: Double): Offset {
        val nx = GeoMath.lonToNormX(lon)
        val ny = GeoMath.latToNormY(lat)
        return Offset(
            (canvasWidth / 2.0 + (nx - centerNormX) * worldPx).toFloat(),
            (canvasHeight / 2.0 + (ny - centerNormY) * worldPx).toFloat()
        )
    }

    // Check points first
    var bestPoint: Pair<SpatialFeatureEntity, Float>? = null
    for (feat in visibleFeatures) {
        if (feat.geometryType == GeometryType.POINT.name) {
            val p = toScreen(feat.latitude, feat.longitude)
            val dist = hypot(p.x - tapOffset.x, p.y - tapOffset.y)
            if (dist <= hitRadiusPx && (bestPoint == null || dist < bestPoint.second)) {
                bestPoint = feat to dist
            }
        }
    }
    if (bestPoint != null) return bestPoint.first

    // Check polylines and polygons
    for (feat in visibleFeatures) {
        if (feat.geometryType == GeometryType.POINT.name) continue
        val rings = GeoMath.jsonToRings(feat.coordinatesJson)
        for (ring in rings) {
            if (feat.geometryType == GeometryType.POLYGON.name && isPointInScreenPolygon(tapOffset, ring.map { toScreen(it.latitude, it.longitude) })) {
                return feat
            }
            for (i in 0 until ring.size - 1) {
                val a = toScreen(ring[i].latitude, ring[i].longitude)
                val b = toScreen(ring[i + 1].latitude, ring[i + 1].longitude)
                if (distanceToSegment(tapOffset, a, b) <= hitRadiusPx * 0.7f) {
                    return feat
                }
            }
        }
    }
    return null
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
    val projX = a.x + t * dx
    val projY = a.y + t * dy
    return hypot(p.x - projX, p.y - projY)
}

private fun isPointInScreenPolygon(p: Offset, poly: List<Offset>): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val xi = poly[i].x
        val yi = poly[i].y
        val xj = poly[j].x
        val yj = poly[j].y
        val intersect = ((yi > p.y) != (yj > p.y)) &&
            (p.x < (xj - xi) * (p.y - yi) / ((yj - yi).takeIf { it != 0f } ?: 0.0001f) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}

fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
}
