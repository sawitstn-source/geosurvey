package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.*
import com.example.spatial.ControlPoint
import com.example.spatial.GeoMath
import com.example.spatial.GeoreferenceSolution
import com.example.spatial.ImageGeoreferencer
import com.example.spatial.MbTilesReader
import com.example.ui.MapViewportState
import com.example.ui.PendingImageGeoreference
import com.example.ui.theme.AmberGold
import com.example.ui.theme.GpsBlue
import com.example.ui.theme.SurveyEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

private enum class GeorefCompactTab(val label: String, val tag: String) {
    IMAGE("1. Gambar", "gcp_tab_image"),
    MAP("2. Peta", "gcp_tab_map"),
    PREVIEW("3. Pratinjau", "gcp_tab_preview")
}

private val GcpMarkerColors = listOf(
    Color(0xFF059669),
    Color(0xFFD97706),
    Color(0xFF2563EB),
    Color(0xFFDC2626),
    Color(0xFF7C3AED),
    Color(0xFF0891B2)
)

@Composable
fun ImageGeoreferenceDialog(
    pending: PendingImageGeoreference,
    initialViewport: MapViewportState,
    layers: List<SpatialLayerEntity>,
    features: List<SpatialFeatureEntity>,
    gpsState: LiveGpsState,
    isDarkMap: Boolean,
    isSaving: Boolean,
    mbTilesReader: MbTilesReader,
    getRasterBitmap: (SpatialLayerEntity) -> ImageBitmap?,
    onCancel: () -> Unit,
    onConfirm: (layerName: String, controlPoints: List<ControlPoint>, generateRoomTiles: Boolean) -> Unit,
    initialControlPoints: List<ControlPoint> = listOf(ControlPoint(id = 1), ControlPoint(id = 2))
) {
    BackHandler(enabled = !isSaving, onBack = onCancel)

    var layerName by remember(pending) { mutableStateOf(pending.fileName) }
    var generateRoomTiles by remember(pending) { mutableStateOf(true) }
    val controlPoints = remember(pending) {
        mutableStateListOf<ControlPoint>().apply {
            addAll(initialControlPoints.ifEmpty { listOf(ControlPoint(1), ControlPoint(2)) })
        }
    }
    var selectedGcpId by remember(pending) {
        mutableIntStateOf(controlPoints.firstOrNull()?.id ?: 1)
    }
    var compactTab by remember { mutableStateOf(GeorefCompactTab.IMAGE) }
    var showOverlayOnSplitMap by remember { mutableStateOf(true) }
    var showManualCoordDialogForId by remember { mutableStateOf<Int?>(null) }
    var miniMapViewport by remember(pending) {
        mutableStateOf(initialViewport.copy(followGps = false))
    }

    var sourceBitmap by remember(pending.localImagePath) { mutableStateOf<Bitmap?>(null) }
    val sourceImageBitmap = remember(sourceBitmap) { sourceBitmap?.asImageBitmap() }

    LaunchedEffect(pending.localImagePath) {
        sourceBitmap = withContext(Dispatchers.IO) {
            ImageGeoreferencer.decodeSampledBitmap(pending.localImagePath, maxEdgePx = 1600)
        }
    }

    val gcpSnapshot = controlPoints.toList()
    val solution = remember(gcpSnapshot) {
        ImageGeoreferencer.solve(gcpSnapshot)
    }
    val pairedCount = gcpSnapshot.count { it.isPaired }
    val activePoint = gcpSnapshot.find { it.id == selectedGcpId } ?: gcpSnapshot.firstOrNull()

    fun updatePoint(id: Int, transform: (ControlPoint) -> ControlPoint) {
        val idx = controlPoints.indexOfFirst { it.id == id }
        if (idx >= 0) {
            controlPoints[idx] = transform(controlPoints[idx])
        }
    }

    fun addNewControlPoint() {
        val nextId = (controlPoints.maxOfOrNull { it.id } ?: 0) + 1
        controlPoints.add(ControlPoint(id = nextId))
        selectedGcpId = nextId
    }

    fun removeControlPoint(id: Int) {
        if (controlPoints.size <= 2) {
            // Reset coordinates instead of removing below 2 slots
            updatePoint(id) { ControlPoint(id = it.id) }
            return
        }
        controlPoints.removeAll { it.id == id }
        if (selectedGcpId == id) {
            selectedGcpId = controlPoints.first().id
        }
    }

    fun zoomToPreviewFootprint(sol: GeoreferenceSolution) {
        val b = sol.bounds
        val span = maxOf(b.maxLat - b.minLat, b.maxLon - b.minLon).coerceAtLeast(0.001)
        val estZoom = (ln(360.0 / span) / ln(2.0) - 0.6).coerceIn(3.0, 18.5)
        miniMapViewport = miniMapViewport.copy(
            centerLat = b.centerLat,
            centerLon = b.centerLon,
            zoom = estZoom,
            followGps = false
        )
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onCancel() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .testTag("image_georeference_dialog"),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = onCancel,
                            enabled = !isSaving,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("cancel_georef_gcp_button")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup georeferensi gambar")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Georeferensi Titik Kontrol (GCP)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${pending.fileName} (${pending.imageWidthPx}×${pending.imageHeightPx} px)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        SolutionStatusBadge(
                            pairedCount = pairedCount,
                            solution = solution
                        )
                    }
                }

                // GCP Chips Strip + Quick Actions
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            controlPoints.forEach { cp ->
                                val isSelected = cp.id == selectedGcpId
                                val markerColor = GcpMarkerColors[(cp.id - 1) % GcpMarkerColors.size]
                                val statusText = when {
                                    cp.isPaired -> "Lengkap"
                                    cp.hasImagePoint -> "Pilih Peta"
                                    cp.hasMapPoint -> "Pilih Gbr"
                                    else -> "Kosong"
                                }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedGcpId = cp.id },
                                    label = {
                                        Text(
                                            text = "#${cp.id} • $statusText",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(if (cp.isPaired) SurveyEmerald else markerColor),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (cp.isPaired) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            } else {
                                                Text(
                                                    text = cp.id.toString(),
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("gcp_chip_${cp.id}")
                                )
                            }

                            FilledTonalButton(
                                onClick = { addNewControlPoint() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("add_gcp_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Titik")
                            }
                        }

                        // Active GCP Readout Strip
                        activePoint?.let { ap ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val imgStr = if (ap.hasImagePoint) {
                                    "Gbr: (${(ap.imageNormX!! * 100).roundToInt()}%, ${(ap.imageNormY!! * 100).roundToInt()}%)"
                                } else {
                                    "Gbr: belum dipilih"
                                }
                                val mapStr = if (ap.hasMapPoint) {
                                    String.format(Locale.US, "%.5f°, %.5f°", ap.mapLat!!, ap.mapLon!!)
                                } else {
                                    "Peta: belum dipilih"
                                }
                                Text(
                                    text = "Titik #${ap.id}: $imgStr  |  $mapStr",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { showManualCoordDialogForId = ap.id },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("edit_gcp_coord_button")
                                ) {
                                    Icon(Icons.Outlined.EditLocationAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Koordinat", style = MaterialTheme.typography.labelMedium)
                                }
                                if (ap.hasImagePoint || ap.hasMapPoint || controlPoints.size > 2) {
                                    IconButton(
                                        onClick = { removeControlPoint(ap.id) },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("remove_active_gcp_button")
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = "Hapus atau reset titik kontrol ${ap.id}",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Main Interactive Workspace (Adaptive Split vs Segmented Tabs)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val isWideScreen = maxWidth >= 700.dp

                    if (isWideScreen) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ImageControlPointPane(
                                imageBitmap = sourceImageBitmap,
                                controlPoints = gcpSnapshot,
                                selectedGcpId = selectedGcpId,
                                onSelectGcp = { selectedGcpId = it },
                                onPlaceImagePoint = { u, v ->
                                    updatePoint(selectedGcpId) { it.copy(imageNormX = u, imageNormY = v) }
                                },
                                onJumpToMap = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )

                            MapControlPointPane(
                                viewport = miniMapViewport,
                                layers = layers,
                                features = features,
                                gpsState = gpsState,
                                isDarkMap = isDarkMap,
                                mbTilesReader = mbTilesReader,
                                getRasterBitmap = getRasterBitmap,
                                controlPoints = gcpSnapshot,
                                selectedGcpId = selectedGcpId,
                                solution = if (showOverlayOnSplitMap) solution else null,
                                overlaySourceBitmap = if (showOverlayOnSplitMap) sourceBitmap else null,
                                isPreviewOnly = false,
                                onViewportChange = { miniMapViewport = it },
                                onPlaceMapPoint = { latLon ->
                                    updatePoint(selectedGcpId) { it.copy(mapLat = latLon.latitude, mapLon = latLon.longitude) }
                                },
                                onZoomToFootprint = solution?.let { sol -> { zoomToPreviewFootprint(sol) } },
                                showOverlayToggle = solution != null,
                                isOverlayEnabled = showOverlayOnSplitMap,
                                onToggleOverlay = { showOverlayOnSplitMap = !showOverlayOnSplitMap },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                GeorefCompactTab.entries.forEachIndexed { idx, tab ->
                                    val enabled = tab != GeorefCompactTab.PREVIEW || solution != null
                                    SegmentedButton(
                                        selected = compactTab == tab,
                                        enabled = enabled,
                                        onClick = {
                                            compactTab = tab
                                            if (tab == GeorefCompactTab.PREVIEW && solution != null) {
                                                zoomToPreviewFootprint(solution)
                                            }
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = idx,
                                            count = GeorefCompactTab.entries.size
                                        ),
                                        modifier = Modifier.testTag(tab.tag)
                                    ) {
                                        Text(
                                            text = tab.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp)
                            ) {
                                when (compactTab) {
                                    GeorefCompactTab.IMAGE -> {
                                        ImageControlPointPane(
                                            imageBitmap = sourceImageBitmap,
                                            controlPoints = gcpSnapshot,
                                            selectedGcpId = selectedGcpId,
                                            onSelectGcp = { selectedGcpId = it },
                                            onPlaceImagePoint = { u, v ->
                                                updatePoint(selectedGcpId) { it.copy(imageNormX = u, imageNormY = v) }
                                            },
                                            onJumpToMap = if (activePoint?.hasImagePoint == true) {
                                                { compactTab = GeorefCompactTab.MAP }
                                            } else null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    GeorefCompactTab.MAP -> {
                                        MapControlPointPane(
                                            viewport = miniMapViewport,
                                            layers = layers,
                                            features = features,
                                            gpsState = gpsState,
                                            isDarkMap = isDarkMap,
                                            mbTilesReader = mbTilesReader,
                                            getRasterBitmap = getRasterBitmap,
                                            controlPoints = gcpSnapshot,
                                            selectedGcpId = selectedGcpId,
                                            solution = solution,
                                            overlaySourceBitmap = sourceBitmap,
                                            isPreviewOnly = false,
                                            onViewportChange = { miniMapViewport = it },
                                            onPlaceMapPoint = { latLon ->
                                                updatePoint(selectedGcpId) {
                                                    it.copy(mapLat = latLon.latitude, mapLon = latLon.longitude)
                                                }
                                            },
                                            onZoomToFootprint = null,
                                            showOverlayToggle = false,
                                            isOverlayEnabled = true,
                                            onToggleOverlay = {},
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    GeorefCompactTab.PREVIEW -> {
                                        MapControlPointPane(
                                            viewport = miniMapViewport,
                                            layers = layers,
                                            features = features,
                                            gpsState = gpsState,
                                            isDarkMap = isDarkMap,
                                            mbTilesReader = mbTilesReader,
                                            getRasterBitmap = getRasterBitmap,
                                            controlPoints = gcpSnapshot,
                                            selectedGcpId = selectedGcpId,
                                            solution = solution,
                                            overlaySourceBitmap = sourceBitmap,
                                            isPreviewOnly = true,
                                            onViewportChange = { miniMapViewport = it },
                                            onPlaceMapPoint = {},
                                            onZoomToFootprint = solution?.let { sol -> { zoomToPreviewFootprint(sol) } },
                                            showOverlayToggle = false,
                                            isOverlayEnabled = true,
                                            onToggleOverlay = {},
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Layer Name + Transform Feedback + Confirm Bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (solution != null) {
                            val rmseStr = String.format(Locale.US, "±%.1f m", solution.rmseMeters)
                            val rotStr = String.format(Locale.US, "%.1f°", solution.rotationDeg)
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "${solution.modeLabel} • RMSE $rmseStr • Rotasi $rotStr",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("georef_solution_summary")
                                    )
                                }
                            }
                        } else {
                            val hintMsg = if (pairedCount < 2) {
                                "Pasangkan minimal 2 titik kontrol (Gambar + Peta)."
                            } else {
                                "Titik kontrol berhimpit/sejajar. Sebar posisi titik agar valid."
                            }
                            Text(
                                text = hintMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pairedCount >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = layerName,
                                onValueChange = { layerName = it },
                                label = { Text("Nama Layer Peta") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("georef_layer_name_input")
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { generateRoomTiles = !generateRoomTiles }
                                    .padding(end = 4.dp)
                            ) {
                                Checkbox(
                                    checked = generateRoomTiles,
                                    onCheckedChange = { generateRoomTiles = it }
                                )
                                Text(
                                    text = "Tile Room",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCancel,
                                enabled = !isSaving,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Batal")
                            }
                            Button(
                                onClick = {
                                    onConfirm(layerName, gcpSnapshot, generateRoomTiles)
                                },
                                enabled = solution != null && !isSaving,
                                modifier = Modifier
                                    .weight(1.6f)
                                    .testTag("apply_georef_gcp_button")
                            ) {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Merrektifikasi...")
                                } else {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Terapkan Georeferensi")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showManualCoordDialogForId?.let { targetId ->
        val target = controlPoints.find { it.id == targetId }
        ManualGcpCoordinateDialog(
            pointId = targetId,
            initialLat = target?.mapLat ?: miniMapViewport.centerLat,
            initialLon = target?.mapLon ?: miniMapViewport.centerLon,
            onDismiss = { showManualCoordDialogForId = null },
            onSave = { lat, lon ->
                updatePoint(targetId) { it.copy(mapLat = lat, mapLon = lon) }
                miniMapViewport = miniMapViewport.copy(centerLat = lat, centerLon = lon, followGps = false)
                showManualCoordDialogForId = null
            }
        )
    }
}

@Composable
private fun SolutionStatusBadge(
    pairedCount: Int,
    solution: GeoreferenceSolution?
) {
    val bgColor = if (solution != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (solution != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val text = if (solution != null) {
        "${solution.gcpCount} GCP • RMSE ±${String.format(Locale.US, "%.1f", solution.rmseMeters)}m"
    } else {
        "$pairedCount/2 GCP"
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ImageControlPointPane(
    imageBitmap: ImageBitmap?,
    controlPoints: List<ControlPoint>,
    selectedGcpId: Int,
    onSelectGcp: (Int) -> Unit,
    onPlaceImagePoint: (Float, Float) -> Unit,
    onJumpToMap: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    val currentZoom by rememberUpdatedState(zoomScale)
    val currentPan by rememberUpdatedState(panOffset)
    val currentSize by rememberUpdatedState(canvasSize)
    val currentSelectedId by rememberUpdatedState(selectedGcpId)

    val labelPaint = remember(density) {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = with(density) { 11.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }

    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = Color(0xFF141B19)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
        ) {
            if (imageBitmap == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("georef_image_canvas")
                        .pointerInput(imageBitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (currentZoom * zoom).coerceIn(1f, 8f)
                                val maxPanX = currentSize.width * (newScale - 1f) * 0.6f + 160f
                                val maxPanY = currentSize.height * (newScale - 1f) * 0.6f + 160f
                                zoomScale = newScale
                                panOffset = Offset(
                                    x = (currentPan.x + pan.x).coerceIn(-maxPanX, maxPanX),
                                    y = (currentPan.y + pan.y).coerceIn(-maxPanY, maxPanY)
                                )
                            }
                        }
                        .pointerInput(imageBitmap) {
                            detectTapGestures { tap ->
                                val w = currentSize.width.toFloat()
                                val h = currentSize.height.toFloat()
                                if (w <= 0f || h <= 0f) return@detectTapGestures

                                val bw = imageBitmap.width.toFloat()
                                val bh = imageBitmap.height.toFloat()
                                val baseScale = min(w / bw, h / bh) * 0.94f
                                val drawW = bw * baseScale
                                val drawH = bh * baseScale
                                val baseLeft = (w - drawW) / 2f
                                val baseTop = (h - drawH) / 2f
                                val cx = w / 2f
                                val cy = h / 2f

                                val bx = (tap.x - currentPan.x - cx) / currentZoom + cx
                                val by = (tap.y - currentPan.y - cy) / currentZoom + cy

                                val normU = (bx - baseLeft) / drawW
                                val normV = (by - baseTop) / drawH
                                if (normU in -0.05f..1.05f && normV in -0.05f..1.05f) {
                                    // Keep side effects explicit via callback
                                    onSelectGcp(currentSelectedId)
                                    onPlaceImagePoint(normU.coerceIn(0f, 1f), normV.coerceIn(0f, 1f))
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val bw = imageBitmap.width.toFloat()
                    val bh = imageBitmap.height.toFloat()
                    val baseScale = min(w / bw, h / bh) * 0.94f
                    val drawW = bw * baseScale
                    val drawH = bh * baseScale
                    val baseLeft = (w - drawW) / 2f
                    val baseTop = (h - drawH) / 2f
                    val cx = w / 2f
                    val cy = h / 2f

                    fun imageNormToScreen(u: Float, v: Float): Offset {
                        val bx = baseLeft + u * drawW
                        val by = baseTop + v * drawH
                        val sx = cx + (bx - cx) * zoomScale + panOffset.x
                        val sy = cy + (by - cy) * zoomScale + panOffset.y
                        return Offset(sx, sy)
                    }

                    val topLeft = imageNormToScreen(0f, 0f)
                    val bottomRight = imageNormToScreen(1f, 1f)
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                        dstSize = IntSize(
                            max(1, (bottomRight.x - topLeft.x).roundToInt()),
                            max(1, (bottomRight.y - topLeft.y).roundToInt())
                        )
                    )

                    // Draw placed GCP markers on the image
                    controlPoints.forEach { cp ->
                        if (cp.hasImagePoint) {
                            val pt = imageNormToScreen(cp.imageNormX!!, cp.imageNormY!!)
                            val isSel = cp.id == selectedGcpId
                            val col = GcpMarkerColors[(cp.id - 1) % GcpMarkerColors.size]
                            val r = if (isSel) 15.dp.toPx() else 12.dp.toPx()

                            // Outer crosshair ticks
                            val tick = r + 7.dp.toPx()
                            drawLine(Color.White, Offset(pt.x - tick, pt.y), Offset(pt.x - r * 0.4f, pt.y), strokeWidth = 2.dp.toPx())
                            drawLine(Color.White, Offset(pt.x + r * 0.4f, pt.y), Offset(pt.x + tick, pt.y), strokeWidth = 2.dp.toPx())
                            drawLine(Color.White, Offset(pt.x, pt.y - tick), Offset(pt.x, pt.y - r * 0.4f), strokeWidth = 2.dp.toPx())
                            drawLine(Color.White, Offset(pt.x, pt.y + r * 0.4f), Offset(pt.x, pt.y + tick), strokeWidth = 2.dp.toPx())

                            drawCircle(Color.White, radius = r + 2.5.dp.toPx(), center = pt)
                            drawCircle(col, radius = r, center = pt)
                            if (isSel) {
                                drawCircle(
                                    color = AmberGold,
                                    radius = r + 5.dp.toPx(),
                                    center = pt,
                                    style = Stroke(width = 2.2.dp.toPx())
                                )
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                cp.id.toString(),
                                pt.x,
                                pt.y + 4.dp.toPx(),
                                labelPaint
                            )
                        }
                    }
                }
            }

            // Top Instruction Banner
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = "Ketuk gambar untuk titik #$selectedGcpId (cubit untuk zoom)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // Reset Zoom Button
            if (zoomScale > 1.05f || panOffset != Offset.Zero) {
                FilledTonalIconButton(
                    onClick = {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Outlined.ZoomOutMap, contentDescription = "Reset zoom gambar")
                }
            }

            // Quick Next-Step Jump Button for compact phones
            if (onJumpToMap != null) {
                FilledTonalButton(
                    onClick = onJumpToMap,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .testTag("jump_to_map_gcp_button")
                ) {
                    Text("Pilih #$selectedGcpId di Peta")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun MapControlPointPane(
    viewport: MapViewportState,
    layers: List<SpatialLayerEntity>,
    features: List<SpatialFeatureEntity>,
    gpsState: LiveGpsState,
    isDarkMap: Boolean,
    mbTilesReader: MbTilesReader,
    getRasterBitmap: (SpatialLayerEntity) -> ImageBitmap?,
    controlPoints: List<ControlPoint>,
    selectedGcpId: Int,
    solution: GeoreferenceSolution?,
    overlaySourceBitmap: Bitmap?,
    isPreviewOnly: Boolean,
    onViewportChange: (MapViewportState) -> Unit,
    onPlaceMapPoint: (LatLon) -> Unit,
    onZoomToFootprint: (() -> Unit)?,
    showOverlayToggle: Boolean,
    isOverlayEnabled: Boolean,
    onToggleOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var tileTick by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    val currentViewport by rememberUpdatedState(viewport)
    val currentSize by rememberUpdatedState(canvasSize)

    val visibleLayerMap = remember(layers) {
        layers.filter { it.isVisible }.associateBy { it.id }
    }
    val visibleFeatures = remember(features, visibleLayerMap) {
        features.filter { visibleLayerMap.containsKey(it.layerId) }
    }
    val parsedGeometries = remember(visibleFeatures) {
        visibleFeatures.associate { it.id to GeoMath.jsonToRings(it.coordinatesJson) }
    }
    val rasterLayers = remember(layers) {
        layers.filter {
            it.isVisible && (it.sourceType == LayerSourceType.RASTER.name || it.sourceType == LayerSourceType.PDF.name)
        }
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
            val xStart = (if (scaleShift >= 0) minTileX shl scaleShift else minTileX shr -scaleShift).coerceIn(0, zTiles - 1)
            val xEnd = (if (scaleShift >= 0) maxTileX shl scaleShift else maxTileX shr -scaleShift).coerceIn(0, zTiles - 1)
            val yStart = (if (scaleShift >= 0) minTileY shl scaleShift else minTileY shr -scaleShift).coerceIn(0, zTiles - 1)
            val yEnd = (if (scaleShift >= 0) maxTileY shl scaleShift else maxTileY shr -scaleShift).coerceIn(0, zTiles - 1)
            for (tx in xStart..xEnd) {
                for (ty in yStart..yEnd) {
                    if (mbTilesReader.getCachedTile(layer.id, z, tx, ty) == null &&
                        !mbTilesReader.isKnownMissing(layer.id, z, tx, ty)
                    ) {
                        val loaded = mbTilesReader.loadTile(layer, z, tx, ty)
                        if (loaded != null) tileTick++
                    }
                }
            }
        }
    }

    val pinLabelPaint = remember(density) {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = with(density) { 11.sp.toPx() }
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    }
    val overlayBitmapPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = 185
        }
    }
    val polyMatrix = remember { Matrix() }

    val bgColor = if (isDarkMap) Color(0xFF0E1714) else Color(0xFFF1EEE4)
    val gridColor = if (isDarkMap) Color(0xFF283E37) else Color(0xFFD5D1C5)

    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = bgColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("georef_map_canvas")
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val w = currentSize.width.toFloat()
                            val h = currentSize.height.toFloat()
                            if (w <= 0f || h <= 0f) return@detectTransformGestures
                            val vp = currentViewport
                            val newZoom = (vp.zoom + ln(zoom.toDouble()) / ln(2.0)).coerceIn(2.0, 20.5)
                            val worldSizePx = 256.0 * 2.0.pow(newZoom)
                            val newNormX = (GeoMath.lonToNormX(vp.centerLon) - pan.x / worldSizePx).coerceIn(0.0, 1.0)
                            val newNormY = (GeoMath.latToNormY(vp.centerLat) - pan.y / worldSizePx).coerceIn(0.0001, 0.9999)
                            onViewportChange(
                                vp.copy(
                                    centerLat = GeoMath.normYToLat(newNormY),
                                    centerLon = GeoMath.normXToLon(newNormX),
                                    zoom = newZoom
                                )
                            )
                        }
                    }
                    .pointerInput(isPreviewOnly) {
                        if (!isPreviewOnly) {
                            detectTapGestures { offset ->
                                val w = currentSize.width.toFloat()
                                val h = currentSize.height.toFloat()
                                if (w <= 0f || h <= 0f) return@detectTapGestures
                                val latLon = screenToLatLon(offset.x, offset.y, currentViewport, w, h)
                                onPlaceMapPoint(latLon)
                            }
                        }
                    }
            ) {
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
                    return Offset(
                        (w / 2.0 + (nx - centerNormX) * worldPx).toFloat(),
                        (h / 2.0 + (ny - centerNormY) * worldPx).toFloat()
                    )
                }

                // Simple Grid Lines
                val stepDeg = when {
                    viewport.zoom >= 16 -> 0.002
                    viewport.zoom >= 14 -> 0.01
                    viewport.zoom >= 11 -> 0.05
                    viewport.zoom >= 8 -> 0.25
                    else -> 2.0
                }
                val tl = screenToLatLon(0f, 0f, viewport, w, h)
                val br = screenToLatLon(w, h, viewport, w, h)
                var lon = floor(min(tl.longitude, br.longitude) / stepDeg) * stepDeg
                var guard = 0
                while (lon <= max(tl.longitude, br.longitude) && guard < 24) {
                    val sx = toScreen(0.0, lon).x
                    drawLine(gridColor, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1f)
                    lon += stepDeg
                    guard++
                }
                var lat = floor(min(tl.latitude, br.latitude) / stepDeg) * stepDeg
                guard = 0
                while (lat <= max(tl.latitude, br.latitude) && guard < 24) {
                    val sy = toScreen(lat, 0.0).y
                    drawLine(gridColor, Offset(0f, sy), Offset(w, sy), strokeWidth = 1f)
                    lat += stepDeg
                    guard++
                }

                // Existing Raster Basemaps
                for (raster in rasterLayers) {
                    val bmp = getRasterBitmap(raster) ?: continue
                    val p0 = toScreen(raster.maxLat, raster.minLon)
                    val p1 = toScreen(raster.minLat, raster.maxLon)
                    val dw = (p1.x - p0.x).roundToInt()
                    val dh = (p1.y - p0.y).roundToInt()
                    if (dw > 4 && dh > 4) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(p0.x.roundToInt(), p0.y.roundToInt()),
                            dstSize = IntSize(dw, dh),
                            alpha = 0.8f
                        )
                    }
                }

                // Existing Visible Vector Features (reference context!)
                for (feat in visibleFeatures) {
                    val layerCol = parseHexColor(visibleLayerMap[feat.layerId]?.colorHex, SurveyEmerald)
                    val rings = parsedGeometries[feat.id].orEmpty()
                    when (feat.geometryType) {
                        GeometryType.POLYGON.name -> {
                            for (ring in rings) {
                                if (ring.size < 3) continue
                                val path = Path()
                                ring.forEachIndexed { idx, pt ->
                                    val s = toScreen(pt.latitude, pt.longitude)
                                    if (idx == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
                                }
                                path.close()
                                drawPath(path, layerCol.copy(alpha = 0.16f))
                                drawPath(path, layerCol.copy(alpha = 0.7f), style = Stroke(width = 1.6.dp.toPx()))
                            }
                        }
                        GeometryType.LINESTRING.name -> {
                            for (ring in rings) {
                                if (ring.size < 2) continue
                                val path = Path()
                                ring.forEachIndexed { idx, pt ->
                                    val s = toScreen(pt.latitude, pt.longitude)
                                    if (idx == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
                                }
                                drawPath(path, layerCol.copy(alpha = 0.85f), style = Stroke(width = 2.dp.toPx()))
                            }
                        }
                        else -> {
                            val s = toScreen(feat.latitude, feat.longitude)
                            drawCircle(Color.White, radius = 5.dp.toPx(), center = s)
                            drawCircle(layerCol, radius = 3.5.dp.toPx(), center = s)
                        }
                    }
                }

                // Live Affine-Transformed Overlay Preview!
                if (solution != null) {
                    val c = solution.cornersLatLon
                    if (c.size == 4) {
                        val sTL = toScreen(c[0].latitude, c[0].longitude)
                        val sTR = toScreen(c[1].latitude, c[1].longitude)
                        val sBR = toScreen(c[2].latitude, c[2].longitude)
                        val sBL = toScreen(c[3].latitude, c[3].longitude)

                        if (overlaySourceBitmap != null) {
                            val sw = overlaySourceBitmap.width.toFloat().coerceAtLeast(1f)
                            val sh = overlaySourceBitmap.height.toFloat().coerceAtLeast(1f)
                            val t = solution.transform
                            polyMatrix.setValues(
                                floatArrayOf(
                                    ((t.a / sw) * worldPx).toFloat(),
                                    ((t.b / sh) * worldPx).toFloat(),
                                    (w / 2.0 + (t.c - centerNormX) * worldPx).toFloat(),
                                    ((t.d / sw) * worldPx).toFloat(),
                                    ((t.e / sh) * worldPx).toFloat(),
                                    (h / 2.0 + (t.f - centerNormY) * worldPx).toFloat(),
                                    0f,
                                    0f,
                                    1f
                                )
                            )
                            drawContext.canvas.nativeCanvas.drawBitmap(
                                overlaySourceBitmap,
                                polyMatrix,
                                overlayBitmapPaint
                            )
                        }

                        val quadPath = Path().apply {
                            moveTo(sTL.x, sTL.y)
                            lineTo(sTR.x, sTR.y)
                            lineTo(sBR.x, sBR.y)
                            lineTo(sBL.x, sBL.y)
                            close()
                        }
                        drawPath(
                            path = quadPath,
                            color = SurveyEmerald,
                            style = Stroke(
                                width = 2.2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                            )
                        )
                    }
                }

                // GPS Live Position Indicator
                if (gpsState.hasFix && gpsState.latitude != null && gpsState.longitude != null) {
                    val gpsScreen = toScreen(gpsState.latitude, gpsState.longitude)
                    drawCircle(GpsBlue.copy(alpha = 0.2f), radius = 14.dp.toPx(), center = gpsScreen)
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = gpsScreen)
                    drawCircle(GpsBlue, radius = 4.5.dp.toPx(), center = gpsScreen)
                }

                // GCP Map Pins + Predicted Residual Connectors
                controlPoints.forEach { cp ->
                    if (cp.hasMapPoint) {
                        val pt = toScreen(cp.mapLat!!, cp.mapLon!!)
                        val isSel = cp.id == selectedGcpId
                        val col = GcpMarkerColors[(cp.id - 1) % GcpMarkerColors.size]

                        if (solution != null && cp.hasImagePoint) {
                            val pred = solution.transform.imageNormToLatLon(
                                cp.imageNormX!!.toDouble(),
                                cp.imageNormY!!.toDouble()
                            )
                            val sPred = toScreen(pred.latitude, pred.longitude)
                            drawLine(
                                color = col,
                                start = pt,
                                end = sPred,
                                strokeWidth = 1.6.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                            )
                        }

                        val r = if (isSel) 14.dp.toPx() else 11.dp.toPx()
                        drawCircle(Color.White, radius = r + 2.dp.toPx(), center = pt)
                        drawCircle(col, radius = r, center = pt)
                        if (isSel) {
                            drawCircle(AmberGold, radius = r + 4.dp.toPx(), center = pt, style = Stroke(2.dp.toPx()))
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            cp.id.toString(),
                            pt.x,
                            pt.y + 4.dp.toPx(),
                            pinLabelPaint
                        )
                    }
                }

                // Center Crosshair (for accurate targeting)
                if (!isPreviewOnly) {
                    val cx = w / 2f
                    val cy = h / 2f
                    val arm = 12.dp.toPx()
                    drawLine(AmberGold, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = 2.dp.toPx())
                    drawLine(AmberGold, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = 2.dp.toPx())
                }
            }

            // Top Map Hint
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = if (isPreviewOnly) {
                        "Pratinjau rektifikasi peta di atas layer lapangan"
                    } else {
                        "Ketuk peta untuk target koordinat #$selectedGcpId"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // Top-Right Zoom Controls + Overlay Toggle
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                FilledTonalIconButton(
                    onClick = {
                        onViewportChange(viewport.copy(zoom = (viewport.zoom + 0.9).coerceIn(2.0, 20.5)))
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Perbesar peta target")
                }
                FilledTonalIconButton(
                    onClick = {
                        onViewportChange(viewport.copy(zoom = (viewport.zoom - 0.9).coerceIn(2.0, 20.5)))
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Perkecil peta target")
                }
                if (onZoomToFootprint != null) {
                    FilledTonalIconButton(
                        onClick = onZoomToFootprint,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Fokus ke bidang peta")
                    }
                }
                if (showOverlayToggle) {
                    FilterChip(
                        selected = isOverlayEnabled,
                        onClick = onToggleOverlay,
                        label = { Text("Overlay", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // Bottom Quick Map Coordinate Pickers
            if (!isPreviewOnly) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            onPlaceMapPoint(LatLon(viewport.centerLat, viewport.centerLon))
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("use_crosshair_for_gcp_button")
                    ) {
                        Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pakai Crosshair", maxLines = 1)
                    }
                    if (gpsState.hasFix && gpsState.latitude != null && gpsState.longitude != null) {
                        FilledTonalButton(
                            onClick = {
                                val coord = LatLon(gpsState.latitude, gpsState.longitude)
                                onPlaceMapPoint(coord)
                                onViewportChange(
                                    viewport.copy(
                                        centerLat = coord.latitude,
                                        centerLon = coord.longitude,
                                        zoom = maxOf(viewport.zoom, 16.0)
                                    )
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("use_gps_for_gcp_button")
                        ) {
                            Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pakai GPS", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualGcpCoordinateDialog(
    pointId: Int,
    initialLat: Double,
    initialLon: Double,
    onDismiss: () -> Unit,
    onSave: (Double, Double) -> Unit
) {
    var latText by remember(pointId, initialLat) {
        mutableStateOf(String.format(Locale.US, "%.6f", initialLat))
    }
    var lonText by remember(pointId, initialLon) {
        mutableStateOf(String.format(Locale.US, "%.6f", initialLon))
    }
    val parsedLat = latText.replace(',', '.').toDoubleOrNull()?.takeIf { it in -85.0..85.0 }
    val parsedLon = lonText.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Koordinat Peta Titik #$pointId") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Masukkan koordinat geografis WGS84 untuk titik kontrol #$pointId.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Latitude (-85..85)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = parsedLat == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gcp_manual_lat_input")
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("Longitude (-180..180)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = parsedLon == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gcp_manual_lon_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (parsedLat != null && parsedLon != null) {
                        onSave(parsedLat, parsedLon)
                    }
                },
                enabled = parsedLat != null && parsedLon != null,
                modifier = Modifier.testTag("save_gcp_manual_coord_button")
            ) {
                Text("Simpan Titik")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}
