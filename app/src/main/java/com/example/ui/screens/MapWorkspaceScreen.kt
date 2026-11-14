package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.*
import com.example.spatial.GeoMath
import com.example.spatial.MbTilesReader
import com.example.ui.GeoSurveiUiState
import com.example.ui.components.OfflineMapCanvas
import com.example.ui.components.SaveDraftGeometryDialog
import com.example.ui.components.SpatialFilesPanel
import com.example.ui.components.StartTrackingDialog
import com.example.ui.components.findNearestTappedFeature
import com.example.ui.theme.AmberGold
import com.example.ui.theme.GpsBlue
import com.example.ui.theme.SurveyEmerald
import java.util.Locale

@Composable
fun MapWorkspaceScreen(
    state: GeoSurveiUiState,
    isDarkMap: Boolean,
    mbTilesReader: MbTilesReader,
    getRasterBitmap: (SpatialLayerEntity) -> ImageBitmap?,
    onRequestLocationPermission: () -> Unit,
    onImportClick: () -> Unit,
    onPanZoom: (Float, Float, Float, Float, Float) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onCenterGps: () -> Unit,
    onCycleCoordFormat: () -> Unit,
    onSelectToolMode: (MapToolMode) -> Unit,
    onAddDraftVertex: (LatLon) -> Unit,
    onUndoDraftVertex: () -> Unit,
    onClearDraft: () -> Unit,
    onSaveDraftFeature: (String, SurveyCategory, String) -> Unit,
    onOpenAddWaypoint: (Boolean) -> Unit,
    onOpenAddWaypointAtCoord: (LatLon) -> Unit,
    onStartTrack: (String, SurveyCategory, TrackIntervalPreset) -> Unit,
    onAddManualTrackPoint: () -> Unit,
    onTogglePauseTrack: () -> Unit,
    onSaveTrack: (String) -> Unit,
    onDiscardTrack: () -> Unit,
    onSelectFeature: (SpatialFeatureEntity?) -> Unit,
    onOpenCompass: (SpatialFeatureEntity) -> Unit,
    onToggleLayerVisibility: (SpatialLayerEntity) -> Unit,
    onSetLayersVisibility: (List<Long>, Boolean) -> Unit,
    onZoomToLayer: (SpatialLayerEntity) -> Unit,
    onGeorefImageClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var showSaveDraftDialog by remember { mutableStateOf(false) }
    var showStartTrackDialog by remember { mutableStateOf(false) }
    var showSaveTrackDialog by remember { mutableStateOf(false) }
    var showSpatialFilesPanel by remember { mutableStateOf(false) }
    var trackNameInput by remember(state.activeTrack.trackName) { mutableStateOf(state.activeTrack.trackName) }

    BackHandler(enabled = showSpatialFilesPanel) {
        showSpatialFilesPanel = false
    }

    val visibleLayerIds = remember(state.layers) {
        state.layers.filter { it.isVisible }.map { it.id }.toSet()
    }
    val visibleFeatures = remember(state.features, visibleLayerIds) {
        state.features.filter { it.layerId in visibleLayerIds }
    }

    val activeLat = if (state.gpsState.hasFix && state.gpsState.latitude != null) {
        state.gpsState.latitude
    } else {
        state.viewport.centerLat
    }
    val activeLon = if (state.gpsState.hasFix && state.gpsState.longitude != null) {
        state.gpsState.longitude
    } else {
        state.viewport.centerLon
    }

    Box(modifier = modifier.fillMaxSize()) {
        OfflineMapCanvas(
            viewport = state.viewport,
            layers = state.layers,
            features = state.features,
            gpsState = state.gpsState,
            toolMode = state.toolMode,
            draftVertices = state.draftVertices,
            activeTrack = state.activeTrack,
            selectedFeature = state.selectedFeature,
            distanceUnit = state.prefs.distanceUnit,
            showGridLines = state.prefs.showGridLines,
            showCrosshair = state.prefs.showCrosshair,
            isDarkMap = isDarkMap,
            mbTilesReader = mbTilesReader,
            getRasterBitmap = getRasterBitmap,
            onPanZoom = onPanZoom,
            onMapTap = { latLon, offset, w, h ->
                when (state.toolMode) {
                    MapToolMode.DROP_POINT -> onOpenAddWaypointAtCoord(latLon)
                    MapToolMode.MEASURE_DISTANCE,
                    MapToolMode.MEASURE_AREA,
                    MapToolMode.DRAW_LINE,
                    MapToolMode.DRAW_POLYGON -> onAddDraftVertex(latLon)
                    MapToolMode.PAN -> {
                        val hitRadiusPx = with(density) { 26.dp.toPx() }
                        val hit = findNearestTappedFeature(
                            tapOffset = offset,
                            viewport = state.viewport,
                            canvasWidth = w,
                            canvasHeight = h,
                            visibleFeatures = visibleFeatures,
                            hitRadiusPx = hitRadiusPx
                        )
                        onSelectFeature(hit)
                    }
                }
            },
            onMapLongPress = { latLon ->
                onOpenAddWaypointAtCoord(latLon)
            }
        )

        // Top Telemetry Bar + Active Tool Banners
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TopTelemetryHud(
                coordText = GeoMath.formatCoordinate(
                    state.viewport.centerLat,
                    state.viewport.centerLon,
                    state.prefs.coordFormat
                ),
                coordFormatLabel = state.prefs.coordFormat.shortLabel,
                gpsState = state.gpsState,
                azimuthDeg = state.compassAzimuthDeg,
                visibleLayerCount = state.layers.count { it.isVisible },
                isSpatialPanelOpen = showSpatialFilesPanel,
                onCycleCoordFormat = onCycleCoordFormat,
                onRequestPermission = onRequestLocationPermission,
                onToggleSpatialPanel = { showSpatialFilesPanel = !showSpatialFilesPanel },
                onImportClick = onImportClick
            )

            // Active Track Recording HUD
            AnimatedVisibility(visible = state.activeTrack.isRecording) {
                TrackRecordingBanner(
                    trackName = state.activeTrack.trackName,
                    elapsedSeconds = state.activeTrack.elapsedSeconds,
                    distanceMeters = state.activeTrack.totalDistanceMeters,
                    elevationGainMeters = state.activeTrack.elevationGainMeters,
                    pointCount = state.activeTrack.points.size,
                    isPaused = state.activeTrack.isPaused,
                    unit = state.prefs.distanceUnit,
                    onAddManualPoint = onAddManualTrackPoint,
                    onDropWaypointDuringTrack = { onOpenAddWaypoint(state.gpsState.hasFix) },
                    onTogglePause = onTogglePauseTrack,
                    onStopAndSave = { showSaveTrackDialog = true },
                    onDiscard = onDiscardTrack
                )
            }

            // Active Drop-Point Mode Banner
            AnimatedVisibility(visible = state.toolMode == MapToolMode.DROP_POINT) {
                DropPointModeBanner(
                    onClose = { onSelectToolMode(MapToolMode.PAN) }
                )
            }

            // Active Measure / Draw HUD
            AnimatedVisibility(
                visible = state.toolMode != MapToolMode.PAN && state.toolMode != MapToolMode.DROP_POINT
            ) {
                MeasureDrawBanner(
                    toolMode = state.toolMode,
                    vertices = state.draftVertices,
                    unit = state.prefs.distanceUnit,
                    onUndo = onUndoDraftVertex,
                    onClear = onClearDraft,
                    onSave = { showSaveDraftDialog = true },
                    onClose = { onSelectToolMode(MapToolMode.PAN) }
                )
            }
        }

        // Right-side Map Controls Stack (hidden while SpatialFilesPanel is open to keep the map uncluttered)
        AnimatedVisibility(
            visible = !showSpatialFilesPanel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                MapControlButton(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_zoom_in),
                    onClick = onZoomIn,
                    testTag = "zoom_in_button"
                )
            MapControlButton(
                icon = Icons.Default.Remove,
                contentDescription = stringResource(R.string.cd_zoom_out),
                onClick = onZoomOut,
                testTag = "zoom_out_button"
            )
            MapControlButton(
                icon = if (state.viewport.followGps) Icons.Default.MyLocation else Icons.Outlined.MyLocation,
                contentDescription = stringResource(R.string.cd_center_gps),
                containerColor = if (state.viewport.followGps) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface,
                contentColor = if (state.viewport.followGps) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    if (!state.gpsState.isPermissionGranted) onRequestLocationPermission()
                    onCenterGps()
                },
                testTag = "center_gps_button"
            )
            MapControlButton(
                icon = Icons.Outlined.TouchApp,
                contentDescription = "Mode ketuk peta untuk tambah titik",
                containerColor = if (state.toolMode == MapToolMode.DROP_POINT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (state.toolMode == MapToolMode.DROP_POINT) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = {
                    val next = if (state.toolMode == MapToolMode.DROP_POINT) MapToolMode.PAN else MapToolMode.DROP_POINT
                    onSelectToolMode(next)
                },
                testTag = "tap_drop_point_button"
            )
            MapControlButton(
                icon = Icons.Outlined.Straighten,
                contentDescription = stringResource(R.string.cd_measure),
                containerColor = if (state.toolMode == MapToolMode.MEASURE_DISTANCE || state.toolMode == MapToolMode.MEASURE_AREA) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (state.toolMode == MapToolMode.MEASURE_DISTANCE || state.toolMode == MapToolMode.MEASURE_AREA) {
                    MaterialTheme.colorScheme.onSecondary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = {
                    val next = when (state.toolMode) {
                        MapToolMode.PAN, MapToolMode.DROP_POINT -> MapToolMode.MEASURE_DISTANCE
                        MapToolMode.MEASURE_DISTANCE -> MapToolMode.MEASURE_AREA
                        else -> MapToolMode.PAN
                    }
                    onSelectToolMode(next)
                },
                testTag = "measure_tool_button"
            )
            MapControlButton(
                icon = if (state.activeTrack.isRecording) Icons.Default.FiberManualRecord else Icons.Outlined.Timeline,
                contentDescription = stringResource(R.string.cd_record_track),
                containerColor = if (state.activeTrack.isRecording) Color(0xFFEA580C) else MaterialTheme.colorScheme.surface,
                contentColor = if (state.activeTrack.isRecording) Color.White else MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        if (state.activeTrack.isRecording) {
                            showSaveTrackDialog = true
                        } else {
                            if (!state.gpsState.isPermissionGranted) onRequestLocationPermission()
                            showStartTrackDialog = true
                        }
                    },
                    testTag = "record_track_button"
                )
            }
        }

        // Bottom Action Area: Add Waypoint FAB (hidden when Spatial Files panel is open)
        AnimatedVisibility(
            visible = !showSpatialFilesPanel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 14.dp)
        ) {
            ExtendedFloatingActionButton(
                onClick = { onOpenAddWaypoint(state.gpsState.hasFix) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.AddLocationAlt, contentDescription = null) },
                text = {
                    Text(if (state.gpsState.hasFix) "Plot GPS" else "Catat Titik")
                },
                modifier = Modifier.testTag("add_waypoint_fab")
            )
        }

        // Spatial Files Visibility Panel Overlay (GPX, KML, GeoJSON, Basemaps)
        AnimatedVisibility(
            visible = showSpatialFilesPanel,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        ) {
            SpatialFilesPanel(
                layers = state.layers,
                features = state.features,
                tileCountsByLayer = state.tileCountsByLayer,
                onToggleVisibility = onToggleLayerVisibility,
                onSetMultipleVisibility = onSetLayersVisibility,
                onZoomToLayer = { layer ->
                    onZoomToLayer(layer)
                    showSpatialFilesPanel = false
                },
                onImportClick = onImportClick,
                onGeorefImageClick = {
                    showSpatialFilesPanel = false
                    onGeorefImageClick()
                },
                onClose = { showSpatialFilesPanel = false }
            )
        }
    }

    if (showStartTrackDialog) {
        StartTrackingDialog(
            hasGpsFix = state.gpsState.hasFix,
            onDismiss = { showStartTrackDialog = false },
            onStart = { name, cat, interval ->
                showStartTrackDialog = false
                onStartTrack(name, cat, interval)
            }
        )
    }

    if (showSaveDraftDialog) {
        val isPoly = state.toolMode == MapToolMode.MEASURE_AREA || state.toolMode == MapToolMode.DRAW_POLYGON
        val len = GeoMath.polylineLengthMeters(
            if (isPoly && state.draftVertices.size >= 3) state.draftVertices + state.draftVertices.first() else state.draftVertices
        )
        val summary = buildString {
            append("Panjang: ${GeoMath.formatDistance(len, state.prefs.distanceUnit)}")
            if (isPoly && state.draftVertices.size >= 3) {
                val area = GeoMath.polygonAreaSquareMeters(state.draftVertices)
                append(" • Luas: ${GeoMath.formatArea(area, state.prefs.distanceUnit)}")
            }
        }
        SaveDraftGeometryDialog(
            isPolygon = isPoly,
            summaryText = summary,
            onDismiss = { showSaveDraftDialog = false },
            onConfirm = { name, cat, notes ->
                showSaveDraftDialog = false
                onSaveDraftFeature(name, cat, notes)
            }
        )
    }

    if (showSaveTrackDialog) {
        AlertDialog(
            onDismissRequest = { showSaveTrackDialog = false },
            title = { Text("Simpan Jejak GPS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Total jarak: ${GeoMath.formatDistance(state.activeTrack.totalDistanceMeters, state.prefs.distanceUnit)} (${state.activeTrack.points.size} titik)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = trackNameInput,
                        onValueChange = { trackNameInput = it },
                        label = { Text("Nama Jejak") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showSaveTrackDialog = false
                    onSaveTrack(trackNameInput)
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveTrackDialog = false }) { Text("Lanjut Rekam") }
            }
        )
    }
}

@Composable
private fun TopTelemetryHud(
    coordText: String,
    coordFormatLabel: String,
    gpsState: LiveGpsState,
    azimuthDeg: Float,
    visibleLayerCount: Int,
    isSpatialPanelOpen: Boolean,
    onCycleCoordFormat: () -> Unit,
    onRequestPermission: () -> Unit,
    onToggleSpatialPanel: () -> Unit,
    onImportClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Coordinate Pill (tap to cycle DD / DMS / UTM)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onCycleCoordFormat)
                    .padding(vertical = 2.dp, horizontal = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = coordFormatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = coordText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val statusDotColor = when {
                        gpsState.hasFix -> SurveyEmerald
                        gpsState.isPermissionGranted -> AmberGold
                        else -> MaterialTheme.colorScheme.error
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    val gpsSummary = when {
                        gpsState.hasFix -> {
                            val acc = gpsState.accuracyMeters?.let { "±${it.toInt()}m" } ?: "Aktif"
                            val alt = gpsState.altitudeMeters?.let { " • ${it.toInt()}mdpl" } ?: ""
                            "GPS $acc$alt • ${azimuthDeg.toInt()}° ${GeoMath.cardinalDirectionId(azimuthDeg.toDouble())}"
                        }
                        gpsState.isPermissionGranted -> "Mencari GPS • Crosshair Offline Siap"
                        else -> "Izin GPS nonaktif (Ketuk aktifkan)"
                    }
                    Text(
                        text = gpsSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (!gpsState.isPermissionGranted) {
                            Modifier.clickable(onClick = onRequestPermission)
                        } else Modifier
                    )
                }
            }

            BadgedBox(
                badge = {
                    if (visibleLayerCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(visibleLayerCount.toString())
                        }
                    }
                }
            ) {
                FilledTonalIconButton(
                    onClick = onToggleSpatialPanel,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSpatialPanelOpen) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (isSpatialPanelOpen) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("map_spatial_panel_toggle")
                ) {
                    Icon(
                        imageVector = if (isSpatialPanelOpen) Icons.Filled.Layers else Icons.Outlined.Layers,
                        contentDescription = "Panel daftar file spasial (GPX, KML, GeoJSON)"
                    )
                }
            }

            FilledTonalIconButton(
                onClick = onImportClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("hud_import_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileOpen,
                    contentDescription = stringResource(R.string.cd_import_layer)
                )
            }
        }
    }
}

@Composable
private fun DropPointModeBanner(onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(20.dp))
                Column {
                    Text("Mode Ketuk Peta: Titik Survei", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ketuk di mana saja pada peta untuk mencatat titik, foto & atribut.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Tutup mode ketuk peta")
            }
        }
    }
}

@Composable
private fun TrackRecordingBanner(
    trackName: String,
    elapsedSeconds: Long,
    distanceMeters: Double,
    elevationGainMeters: Double,
    pointCount: Int,
    isPaused: Boolean,
    unit: DistanceUnit,
    onAddManualPoint: () -> Unit,
    onDropWaypointDuringTrack: () -> Unit,
    onTogglePause: () -> Unit,
    onStopAndSave: () -> Unit,
    onDiscard: () -> Unit
) {
    val mins = elapsedSeconds / 60
    val secs = elapsedSeconds % 60
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFEA580C),
        contentColor = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(
                        text = if (isPaused) "DIJEDA • $trackName" else trackName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val elevStr = if (elevationGainMeters > 1.0) " • +${elevationGainMeters.toInt()}m" else ""
                Text(
                    text = String.format(Locale.US, "%02d:%02d • %s%s (%d)", mins, secs, GeoMath.formatDistance(distanceMeters, unit), elevStr, pointCount),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onAddManualPoint,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Jalur", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onDropWaypointDuringTrack,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+ Pin Foto", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onTogglePause,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(if (isPaused) "Lanjut" else "Jeda")
                }
                Button(
                    onClick = onStopAndSave,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF9A3412)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Simpan")
                }
                IconButton(onClick = onDiscard, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Buang rekaman", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MeasureDrawBanner(
    toolMode: MapToolMode,
    vertices: List<LatLon>,
    unit: DistanceUnit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    val isPoly = toolMode == MapToolMode.MEASURE_AREA || toolMode == MapToolMode.DRAW_POLYGON
    val totalLen = if (isPoly && vertices.size >= 3) {
        GeoMath.polylineLengthMeters(vertices + vertices.first())
    } else {
        GeoMath.polylineLengthMeters(vertices)
    }
    val area = if (isPoly && vertices.size >= 3) GeoMath.polygonAreaSquareMeters(vertices) else 0.0

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPoly) "Mode Ukur Luas Poligon (Ketuk Peta)" else "Mode Ukur Jarak Garis (Ketuk Peta)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    val readout = if (isPoly) {
                        "Luas: ${GeoMath.formatArea(area, unit)} • Keliling: ${GeoMath.formatDistance(totalLen, unit)} (${vertices.size} titik)"
                    } else {
                        "Jarak: ${GeoMath.formatDistance(totalLen, unit)} (${vertices.size} titik)"
                    }
                    Text(readout, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup mode ukur")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUndo, enabled = vertices.isNotEmpty()) {
                    Text("Undo")
                }
                OutlinedButton(onClick = onClear, enabled = vertices.isNotEmpty()) {
                    Text("Reset")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSave,
                    enabled = (isPoly && vertices.size >= 3) || (!isPoly && vertices.size >= 2)
                ) {
                    Text("Simpan Fitur")
                }
            }
        }
    }
}

@Composable
private fun MapControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .size(48.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription)
        }
    }
}
