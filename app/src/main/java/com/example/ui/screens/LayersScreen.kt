package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.LayerSourceType
import com.example.data.local.SpatialFeatureEntity
import com.example.data.local.SpatialLayerEntity
import com.example.spatial.ExportFormat
import com.example.ui.components.parseHexColor
import com.example.ui.theme.ForestSlate
import com.example.ui.theme.SurveyEmerald

@Composable
fun LayersScreen(
    layers: List<SpatialLayerEntity>,
    features: List<SpatialFeatureEntity>,
    tileCountsByLayer: Map<Long, Int>,
    isImporting: Boolean,
    onImportSpatialClick: () -> Unit,
    onGeoreferenceImageClick: () -> Unit = {},
    onToggleVisibility: (SpatialLayerEntity) -> Unit,
    onChangeColor: (Long, String) -> Unit,
    onZoomToLayer: (SpatialLayerEntity) -> Unit,
    onOpenTileViewer: (SpatialLayerEntity) -> Unit,
    onShareLayer: (SpatialLayerEntity, ExportFormat) -> Unit,
    onDeleteLayer: (SpatialLayerEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val featureCountByLayer = remember(features) {
        features.groupingBy { it.layerId }.eachCount()
    }
    var layerToDelete by remember { mutableStateOf<SpatialLayerEntity?>(null) }
    var layerToShare by remember { mutableStateOf<SpatialLayerEntity?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Editorial Cartographic Hero Card
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(172.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_topo_banner_1794571470608),
                        contentDescription = "Ilustrasi peta topografi survei lapangan",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        ForestSlate.copy(alpha = 0.25f),
                                        ForestSlate.copy(alpha = 0.88f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = Color(0xFF2BB673),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "OFFLINE GIS READY",
                                style = MaterialTheme.typography.labelSmall,
                                color = ForestSlate,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            text = "Pustaka Peta & Layer Spasial",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            text = "Dukungan GeoJSON, KML/KMZ, GPX, CSV, MBTiles (Room), Peta PDF / GeoPDF, & Raster.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.88f)
                        )
                    }
                }
            }
        }

        // Import & Image Georeference CTAs
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onImportSpatialClick,
                    enabled = !isImporting,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("import_spatial_button")
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Memproses Data Spasial...")
                    } else {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Impor Data Spasial / Peta Offline")
                    }
                }
                OutlinedButton(
                    onClick = onGeoreferenceImageClick,
                    enabled = !isImporting,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("georef_image_button")
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Pilih Gambar & Georeferensi Titik Kontrol")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Daftar Layer (${layers.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${layers.count { it.isVisible }} aktif",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (layers.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Belum ada layer peta", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Impor file GeoJSON, KML, GPX, CSV, MBTiles, peta PDF/GeoPDF, atau gambar peta untuk mulai bekerja offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(layers, key = { it.id }) { layer ->
                LayerItemCard(
                    layer = layer,
                    featureCount = featureCountByLayer[layer.id] ?: 0,
                    tileCount = tileCountsByLayer[layer.id] ?: 0,
                    onToggleVisibility = { onToggleVisibility(layer) },
                    onChangeColor = { hex -> onChangeColor(layer.id, hex) },
                    onZoomToLayer = { onZoomToLayer(layer) },
                    onOpenTileViewer = { onOpenTileViewer(layer) },
                    onShareClick = { layerToShare = layer },
                    onDeleteClick = { layerToDelete = layer }
                )
            }
        }
    }

    layerToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { layerToDelete = null },
            title = { Text("Hapus Layer?") },
            text = {
                Text("Layer '${target.name}' beserta seluruh fitur di dalamnya akan dihapus permanen dari penyimpanan offline.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteLayer(target)
                        layerToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { layerToDelete = null }) { Text("Batal") }
            }
        )
    }

    layerToShare?.let { target ->
        AlertDialog(
            onDismissRequest = { layerToShare = null },
            title = { Text("Ekspor & Bagikan Layer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pilih format ekspor untuk '${target.name}':", style = MaterialTheme.typography.bodySmall)
                    ExportFormat.entries.forEach { fmt ->
                        OutlinedButton(
                            onClick = {
                                layerToShare = null
                                onShareLayer(target, fmt)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(fmt.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { layerToShare = null }) { Text("Tutup") }
            }
        )
    }
}

@Composable
private fun LayerItemCard(
    layer: SpatialLayerEntity,
    featureCount: Int,
    tileCount: Int,
    onToggleVisibility: () -> Unit,
    onChangeColor: (String) -> Unit,
    onZoomToLayer: () -> Unit,
    onOpenTileViewer: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val sourceType = runCatching { LayerSourceType.valueOf(layer.sourceType) }.getOrDefault(LayerSourceType.GEOJSON)
    val isBasemapLayer = sourceType == LayerSourceType.MBTILES ||
        sourceType == LayerSourceType.RASTER ||
        sourceType == LayerSourceType.PDF
    val layerColor = parseHexColor(layer.colorHex, SurveyEmerald)
    var showColors by remember { mutableStateOf(false) }

    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(layerColor)
                        .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                        .clickable { showColors = !showColors }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = layer.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = sourceType.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        val detail = when (sourceType) {
                            LayerSourceType.MBTILES -> if (tileCount > 0) "$tileCount tile Room (z${layer.minZoom}-${layer.maxZoom})" else "Tile Offline (z${layer.minZoom}-${layer.maxZoom})"
                            LayerSourceType.PDF -> if (tileCount > 0) "PDF • $tileCount tile Room" else "Peta PDF Georeferensi"
                            LayerSourceType.RASTER -> if (tileCount > 0) "Raster • $tileCount tile Room" else "Peta Raster Georeferensi"
                            else -> "$featureCount fitur"
                        }
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onToggleVisibility,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (layer.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = if (layer.isVisible) "Sembunyikan layer" else "Tampilkan layer",
                        tint = if (layer.isVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showColors && !isBasemapLayer) {
                val swatches = listOf("#1B6B52", "#D97706", "#2563EB", "#7C3AED", "#0891B2", "#DC2626", "#65A30D")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    swatches.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex, SurveyEmerald))
                                .border(
                                    width = if (hex.equals(layer.colorHex, ignoreCase = true)) 2.5.dp else 1.dp,
                                    color = if (hex.equals(layer.colorHex, ignoreCase = true)) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable {
                                    onChangeColor(hex)
                                    showColors = false
                                }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onZoomToLayer,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Fokus Peta")
                }
                if (isBasemapLayer || tileCount > 0) {
                    OutlinedButton(
                        onClick = onOpenTileViewer,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("open_tile_viewer_${layer.id}")
                    ) {
                        Icon(Icons.Outlined.GridView, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tile Viewer")
                    }
                }
                if (!isBasemapLayer && featureCount > 0) {
                    OutlinedButton(
                        onClick = onShareClick,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ekspor")
                    }
                }
                if (!layer.isEditable) {
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Hapus layer",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
