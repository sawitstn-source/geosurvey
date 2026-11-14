package com.example.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.LayerSourceType
import com.example.data.local.MapBounds
import com.example.data.local.MapTileDao
import com.example.data.local.SpatialLayerEntity
import com.example.spatial.GeoMath
import com.example.spatial.MbTilesReader
import com.example.ui.MapViewportState
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Full-screen offline MBTiles / Room Tile Pyramid viewer component.
 * Reads 256x256 PNG/JPEG tiles stored in Room `map_tiles` and renders them via Jetpack Compose Canvas
 * with smooth pan/pinch-zoom gestures and a toggleable z/x/y tile grid inspector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MbTilesViewerDialog(
    layer: SpatialLayerEntity,
    mapTileDao: MapTileDao,
    mbTilesReader: MbTilesReader,
    isDarkMap: Boolean,
    onZoomToOnMainMap: (SpatialLayerEntity) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val bounds = remember(layer) {
        MapBounds(layer.minLat, layer.maxLat, layer.minLon, layer.maxLon)
    }
    var roomTileCount by remember(layer.id) { mutableIntStateOf(0) }
    var availableZooms by remember(layer.id) { mutableStateOf<List<Int>>(emptyList()) }
    var showTileGrid by remember { mutableStateOf(true) }

    val initialZoom = remember(layer) {
        val latSpan = (layer.maxLat - layer.minLat).coerceAtLeast(0.001)
        val lonSpan = (layer.maxLon - layer.minLon).coerceAtLeast(0.001)
        val est = (log2(360.0 / maxOf(latSpan, lonSpan)) - 0.3).coerceIn(layer.minZoom.toDouble(), layer.maxZoom.toDouble())
        est
    }

    var viewport by remember(layer.id) {
        mutableStateOf(
            MapViewportState(
                centerLat = if (bounds.isValid) bounds.centerLat else -7.5450,
                centerLon = if (bounds.isValid) bounds.centerLon else 110.4425,
                zoom = initialZoom,
                followGps = false
            )
        )
    }

    LaunchedEffect(layer.id) {
        roomTileCount = mapTileDao.getTileCount(layer.id)
        availableZooms = mapTileDao.getAvailableZooms(layer.id)
        if (availableZooms.isNotEmpty()) {
            val clamped = viewport.zoom.coerceIn(
                availableZooms.first().toDouble(),
                availableZooms.last().toDouble()
            )
            viewport = viewport.copy(zoom = clamped)
        }
    }

    val sourceType = remember(layer.sourceType) {
        runCatching { LayerSourceType.valueOf(layer.sourceType) }.getOrDefault(LayerSourceType.MBTILES)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("mbtiles_viewer_dialog"),
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("mbtiles_viewer_close")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tutup MBTiles Viewer")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = layer.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${sourceType.label} • $roomTileCount tile di Room",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showTileGrid = !showTileGrid },
                            modifier = Modifier.testTag("mbtiles_toggle_grid")
                        ) {
                            Icon(
                                imageVector = if (showTileGrid) Icons.Default.GridOn else Icons.Outlined.GridOff,
                                contentDescription = if (showTileGrid) "Sembunyikan grid z/x/y" else "Tampilkan grid z/x/y",
                                tint = if (showTileGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { onZoomToOnMainMap(layer) },
                            modifier = Modifier.testTag("mbtiles_open_in_survey")
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Peta Survei")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                MbTilesCanvasRenderer(
                    layer = layer,
                    viewport = viewport,
                    mbTilesReader = mbTilesReader,
                    showTileGrid = showTileGrid,
                    isDarkMap = isDarkMap,
                    onPanZoom = { dx, dy, zoomFactor, w, h ->
                        if (w <= 0f || h <= 0f) return@MbTilesCanvasRenderer
                        val newZoom = (viewport.zoom + log2(zoomFactor.toDouble())).coerceIn(1.0, 20.0)
                        val worldSizePx = 256.0 * 2.0.pow(newZoom)
                        val nx = (GeoMath.lonToNormX(viewport.centerLon) - dx / worldSizePx).coerceIn(0.0, 1.0)
                        val ny = (GeoMath.latToNormY(viewport.centerLat) - dy / worldSizePx).coerceIn(0.0001, 0.9999)
                        viewport = viewport.copy(
                            centerLat = GeoMath.normYToLat(ny),
                            centerLon = GeoMath.normXToLon(nx),
                            zoom = newZoom
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top-start Zoom Level Pills stored in Room
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Room Tile Cache • Z ${String.format(Locale.US, "%.1f", viewport.zoom)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (availableZooms.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val activeTileZ = viewport.zoom.roundToInt()
                            availableZooms.forEach { z ->
                                val selected = z == activeTileZ
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewport = viewport.copy(zoom = z.toDouble()) },
                                    label = { Text("z$z") }
                                )
                            }
                        }
                    }
                }

                // Bottom-end Zoom & Reset Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            viewport = viewport.copy(zoom = (viewport.zoom + 1.0).coerceIn(1.0, 20.0))
                        },
                        modifier = Modifier.testTag("mbtiles_zoom_in")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Perbesar tile")
                    }
                    FilledTonalIconButton(
                        onClick = {
                            viewport = viewport.copy(zoom = (viewport.zoom - 1.0).coerceIn(1.0, 20.0))
                        },
                        modifier = Modifier.testTag("mbtiles_zoom_out")
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Perkecil tile")
                    }
                    FilledTonalIconButton(
                        onClick = {
                            if (bounds.isValid) {
                                viewport = viewport.copy(
                                    centerLat = bounds.centerLat,
                                    centerLon = bounds.centerLon,
                                    zoom = initialZoom
                                )
                            }
                        },
                        modifier = Modifier.testTag("mbtiles_fit_bounds")
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fokus ke batas basemap")
                    }
                }

                // Bottom-start Coordinate & Tile Telemetry HUD
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, bottom = 14.dp, end = 76.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Pusat: %.5f, %.5f",
                                viewport.centerLat,
                                viewport.centerLon
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val activeTileZ = viewport.zoom.roundToInt().coerceIn(
                            layer.minZoom.coerceAtLeast(0),
                            layer.maxZoom.coerceAtLeast(layer.minZoom)
                        )
                        Text(
                            text = if (roomTileCount > 0) {
                                "Render z$activeTileZ dari Room SQLite ($roomTileCount tile tersedia)"
                            } else {
                                "Belum ada tile di Room untuk rentang ini"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Standalone Canvas-based MBTiles renderer backed by Room + MbTilesReader.
 */
@Composable
fun MbTilesCanvasRenderer(
    layer: SpatialLayerEntity,
    viewport: MapViewportState,
    mbTilesReader: MbTilesReader,
    showTileGrid: Boolean,
    isDarkMap: Boolean,
    onPanZoom: (dx: Float, dy: Float, zoomFactor: Float, width: Float, height: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var tileTick by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val currentViewport by rememberUpdatedState(viewport)
    val currentCanvasSize by rememberUpdatedState(canvasSize)

    LaunchedEffect(
        layer.id,
        viewport.centerLat,
        viewport.centerLon,
        viewport.zoom,
        canvasSize
    ) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        val tileZ = viewport.zoom.roundToInt().coerceIn(layer.minZoom.coerceAtLeast(0), layer.maxZoom.coerceAtLeast(layer.minZoom))
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

        for (tx in minTileX..maxTileX) {
            for (ty in minTileY..maxTileY) {
                if (mbTilesReader.getCachedTile(layer.id, tileZ, tx, ty) == null &&
                    !mbTilesReader.isKnownMissing(layer.id, tileZ, tx, ty)
                ) {
                    coroutineScope.launch {
                        val bmp = mbTilesReader.loadTile(layer, tileZ, tx, ty)
                        if (bmp != null) tileTick++
                    }
                }
            }
        }
    }

    val bgColor = if (isDarkMap) Color(0xFF0E1714) else Color(0xFFEDEAE0)
    val gridBorderColor = if (isDarkMap) Color(0xFF5EEAD4).copy(alpha = 0.45f) else Color(0xFF1B6B52).copy(alpha = 0.45f)
    val tileTextPaint = remember(isDarkMap, density) {
        Paint().apply {
            isAntiAlias = true
            color = if (isDarkMap) {
                android.graphics.Color.argb(200, 180, 235, 220)
            } else {
                android.graphics.Color.argb(190, 22, 68, 54)
            }
            textSize = with(density) { 10.sp.toPx() }
            typeface = Typeface.MONOSPACE
        }
    }

    Canvas(
        modifier = modifier
            .background(bgColor)
            .onSizeChanged { canvasSize = it }
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
    ) {
        @Suppress("UNUSED_EXPRESSION")
        tileTick

        val w = size.width
        val h = size.height
        val vp = currentViewport
        val worldPx = 256.0 * 2.0.pow(vp.zoom)
        val centerNormX = GeoMath.lonToNormX(vp.centerLon)
        val centerNormY = GeoMath.latToNormY(vp.centerLat)

        val z = vp.zoom.roundToInt().coerceIn(layer.minZoom.coerceAtLeast(0), layer.maxZoom.coerceAtLeast(layer.minZoom))
        val nTiles = 1 shl z

        val minNormX = (centerNormX - (w / 2.0) / worldPx).coerceIn(0.0, 0.99999)
        val maxNormX = (centerNormX + (w / 2.0) / worldPx).coerceIn(0.0, 0.99999)
        val minNormY = (centerNormY - (h / 2.0) / worldPx).coerceIn(0.0, 0.99999)
        val maxNormY = (centerNormY + (h / 2.0) / worldPx).coerceIn(0.0, 0.99999)

        val minTileX = floor(minNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
        val maxTileX = floor(maxNormX * nTiles).toInt().coerceIn(0, nTiles - 1)
        val minTileY = floor(minNormY * nTiles).toInt().coerceIn(0, nTiles - 1)
        val maxTileY = floor(maxNormY * nTiles).toInt().coerceIn(0, nTiles - 1)

        val tileScreenSize = (worldPx / nTiles).toFloat()

        for (tx in minTileX..maxTileX) {
            for (ty in minTileY..maxTileY) {
                val sx = (w / 2.0 + (tx.toDouble() / nTiles - centerNormX) * worldPx).toFloat()
                val sy = (h / 2.0 + (ty.toDouble() / nTiles - centerNormY) * worldPx).toFloat()

                val bmp = mbTilesReader.getCachedTile(layer.id, z, tx, ty)
                if (bmp != null) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
                        dstSize = IntSize(ceil(tileScreenSize).toInt() + 1, ceil(tileScreenSize).toInt() + 1)
                    )
                }

                if (showTileGrid) {
                    drawRect(
                        color = gridBorderColor,
                        topLeft = Offset(sx, sy),
                        size = Size(tileScreenSize, tileScreenSize),
                        style = Stroke(width = 1.dp.toPx())
                    )
                    if (tileScreenSize >= 64f) {
                        drawContext.canvas.nativeCanvas.drawText(
                            "$z/$tx/$ty",
                            sx + 8.dp.toPx(),
                            sy + 18.dp.toPx(),
                            tileTextPaint
                        )
                    }
                }
            }
        }

        // Layer Geographic Extent Bounding Box
        if (layer.maxLat > layer.minLat && layer.maxLon > layer.minLon) {
            val tlX = (w / 2.0 + (GeoMath.lonToNormX(layer.minLon) - centerNormX) * worldPx).toFloat()
            val tlY = (h / 2.0 + (GeoMath.latToNormY(layer.maxLat) - centerNormY) * worldPx).toFloat()
            val brX = (w / 2.0 + (GeoMath.lonToNormX(layer.maxLon) - centerNormX) * worldPx).toFloat()
            val brY = (h / 2.0 + (GeoMath.latToNormY(layer.minLat) - centerNormY) * worldPx).toFloat()
            drawRect(
                color = Color(0xFFD97706),
                topLeft = Offset(tlX, tlY),
                size = Size((brX - tlX).coerceAtLeast(1f), (brY - tlY).coerceAtLeast(1f)),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 8f), 0f)
                )
            )
        }
    }
}
