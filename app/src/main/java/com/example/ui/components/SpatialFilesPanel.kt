package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.GeometryType
import com.example.data.local.LayerSourceType
import com.example.data.local.SpatialFeatureEntity
import com.example.data.local.SpatialLayerEntity
import com.example.ui.theme.SurveyEmerald

enum class SpatialFileFilter(val label: String) {
    ALL("Semua"),
    GPX("GPX"),
    KML("KML"),
    GEOJSON("GeoJSON"),
    OTHER("Lainnya")
}

@Composable
fun SpatialFilesPanel(
    layers: List<SpatialLayerEntity>,
    features: List<SpatialFeatureEntity>,
    tileCountsByLayer: Map<Long, Int>,
    onToggleVisibility: (SpatialLayerEntity) -> Unit,
    onSetMultipleVisibility: (List<Long>, Boolean) -> Unit,
    onZoomToLayer: (SpatialLayerEntity) -> Unit,
    onImportClick: () -> Unit,
    onGeorefImageClick: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeFilter by remember { mutableStateOf(SpatialFileFilter.ALL) }

    val featuresByLayer = remember(features) {
        features.groupBy { it.layerId }
    }

    val layersSnapshot = layers.toList()
    // Prioritize loaded spatial files (GPX, KML, GeoJSON first, then other imported/survey layers)
    val orderedLayers = remember(layersSnapshot) {
        layersSnapshot.sortedWith(
            compareBy<SpatialLayerEntity> { layer ->
                when (layer.sourceType) {
                    LayerSourceType.GPX.name -> 0
                    LayerSourceType.KML.name -> 1
                    LayerSourceType.GEOJSON.name -> 2
                    LayerSourceType.CSV.name -> 3
                    LayerSourceType.PDF.name -> 4
                    LayerSourceType.MBTILES.name -> 5
                    LayerSourceType.RASTER.name -> 6
                    else -> 7
                }
            }.thenByDescending { it.createdAt }
        )
    }

    val countsByFilter = remember(orderedLayers) {
        mapOf(
            SpatialFileFilter.ALL to orderedLayers.size,
            SpatialFileFilter.GPX to orderedLayers.count { it.sourceType == LayerSourceType.GPX.name },
            SpatialFileFilter.KML to orderedLayers.count { it.sourceType == LayerSourceType.KML.name },
            SpatialFileFilter.GEOJSON to orderedLayers.count { it.sourceType == LayerSourceType.GEOJSON.name },
            SpatialFileFilter.OTHER to orderedLayers.count {
                it.sourceType !in setOf(
                    LayerSourceType.GPX.name,
                    LayerSourceType.KML.name,
                    LayerSourceType.GEOJSON.name
                )
            }
        )
    }

    val filteredLayers = remember(orderedLayers, activeFilter) {
        when (activeFilter) {
            SpatialFileFilter.ALL -> orderedLayers
            SpatialFileFilter.GPX -> orderedLayers.filter { it.sourceType == LayerSourceType.GPX.name }
            SpatialFileFilter.KML -> orderedLayers.filter { it.sourceType == LayerSourceType.KML.name }
            SpatialFileFilter.GEOJSON -> orderedLayers.filter { it.sourceType == LayerSourceType.GEOJSON.name }
            SpatialFileFilter.OTHER -> orderedLayers.filter {
                it.sourceType !in setOf(
                    LayerSourceType.GPX.name,
                    LayerSourceType.KML.name,
                    LayerSourceType.GEOJSON.name
                )
            }
        }
    }

    val visibleCount = filteredLayers.count { it.isVisible }
    val allVisible = filteredLayers.isNotEmpty() && visibleCount == filteredLayers.size

    Surface(
        modifier = modifier
            .widthIn(max = 480.dp)
            .testTag("spatial_files_panel"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "File Spasial di Peta",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$visibleCount dari ${filteredLayers.size} file tampil aktif",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("close_spatial_files_panel")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Tutup panel file spasial"
                    )
                }
            }

            // Format Filter Chips (Semua, GPX, KML, GeoJSON, Lainnya)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SpatialFileFilter.entries.forEach { filter ->
                    val count = countsByFilter[filter] ?: 0
                    val selected = activeFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { activeFilter = filter },
                        label = {
                            Text(
                                text = "${filter.label} ($count)",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("spatial_filter_${filter.name.lowercase()}")
                    )
                }
            }

            // Quick Action Bar: Import, Image Georef & Bulk Visibility Toggle
            if (filteredLayers.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            onClick = onImportClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("spatial_panel_import_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Impor")
                        }
                        TextButton(
                            onClick = onGeorefImageClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("spatial_panel_georef_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AddPhotoAlternate,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Georef Gambar")
                        }
                    }
                    TextButton(
                        onClick = {
                            onSetMultipleVisibility(filteredLayers.map { it.id }, !allVisible)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("spatial_panel_toggle_all_button")
                    ) {
                        Text(if (allVisible) "Sembunyikan Semua" else "Tampilkan Semua")
                    }
                }
            }

            // Loaded Spatial Files List
            if (filteredLayers.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Belum ada file ${activeFilter.label} dimuat",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Impor file GPX, KML/KMZ, atau GeoJSON untuk ditampilkan di peta.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        FilledTonalButton(onClick = onImportClick) {
                            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Impor File Spasial")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredLayers, key = { it.id }) { layer ->
                        val layerFeatures = featuresByLayer[layer.id].orEmpty()
                        val tileCount = tileCountsByLayer[layer.id] ?: 0
                        SpatialFileVisibilityRow(
                            layer = layer,
                            features = layerFeatures,
                            tileCount = tileCount,
                            onToggleVisibility = { onToggleVisibility(layer) },
                            onZoomToLayer = { onZoomToLayer(layer) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpatialFileVisibilityRow(
    layer: SpatialLayerEntity,
    features: List<SpatialFeatureEntity>,
    tileCount: Int,
    onToggleVisibility: () -> Unit,
    onZoomToLayer: () -> Unit
) {
    val sourceType = remember(layer.sourceType) {
        runCatching { LayerSourceType.valueOf(layer.sourceType) }.getOrDefault(LayerSourceType.GEOJSON)
    }
    val layerColor = remember(layer.colorHex) {
        parseHexColor(layer.colorHex, SurveyEmerald)
    }

    val summaryText = remember(features, sourceType, tileCount) {
        when (sourceType) {
            LayerSourceType.MBTILES -> if (tileCount > 0) "$tileCount tile Room" else "Tile Offline"
            LayerSourceType.PDF -> if (tileCount > 0) "PDF • $tileCount tile Room" else "Peta PDF"
            LayerSourceType.RASTER -> if (tileCount > 0) "Raster • $tileCount tile Room" else "Peta Raster"
            else -> {
                val pts = features.count { it.geometryType == GeometryType.POINT.name }
                val lines = features.count { it.geometryType == GeometryType.LINESTRING.name }
                val polys = features.count { it.geometryType == GeometryType.POLYGON.name }
                val parts = buildList {
                    if (pts > 0) add("$pts titik")
                    if (lines > 0) add("$lines jalur")
                    if (polys > 0) add("$polys area")
                }
                if (parts.isEmpty()) "${features.size} fitur" else "${features.size} fitur (${parts.joinToString(", ")})"
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (layer.isVisible) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggleVisibility)
            .testTag("spatial_file_row_${layer.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .alpha(if (layer.isVisible) 1f else 0.62f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(layerColor)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = layer.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = sourceType.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onZoomToLayer,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("focus_spatial_file_${layer.id}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.CenterFocusStrong,
                    contentDescription = "Fokus peta ke ${layer.name}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Switch(
                checked = layer.isVisible,
                onCheckedChange = { onToggleVisibility() },
                modifier = Modifier.testTag("toggle_spatial_file_${layer.id}")
            )
        }
    }
}
