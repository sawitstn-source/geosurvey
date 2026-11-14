package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.local.*
import com.example.spatial.GeoMath
import com.example.ui.PendingRasterCalibration
import com.example.ui.theme.AmberGold
import com.example.ui.theme.SurveyEmerald
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditFeatureDialog(
    initialCoord: LatLon,
    editingFeature: SpatialFeatureEntity?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        description: String,
        category: SurveyCategory,
        lat: Double,
        lon: Double,
        elevation: Double?,
        photoUris: List<String>,
        attributes: Map<String, String>
    ) -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current

    var name by remember(editingFeature) { mutableStateOf(editingFeature?.name ?: "") }
    var description by remember(editingFeature) { mutableStateOf(editingFeature?.description ?: "") }
    var category by remember(editingFeature) {
        mutableStateOf(
            editingFeature?.category?.let {
                runCatching { SurveyCategory.valueOf(it) }.getOrNull()
            } ?: SurveyCategory.UMUM
        )
    }
    var latText by remember(editingFeature, initialCoord) {
        mutableStateOf(String.format(Locale.US, "%.6f", editingFeature?.latitude ?: initialCoord.latitude))
    }
    var lonText by remember(editingFeature, initialCoord) {
        mutableStateOf(String.format(Locale.US, "%.6f", editingFeature?.longitude ?: initialCoord.longitude))
    }
    var eleText by remember(editingFeature, initialCoord) {
        mutableStateOf((editingFeature?.elevationMeters ?: initialCoord.elevation)?.let {
            String.format(Locale.US, "%.1f", it)
        } ?: "")
    }
    val photoUris = remember(editingFeature) {
        mutableStateListOf<String>().apply {
            addAll(GeoMath.decodePhotoUris(editingFeature?.photoUri))
        }
    }

    val initialAttrs = remember(editingFeature) {
        editingFeature?.let { GeoMath.jsonToAttributes(it.attributesJson) } ?: emptyMap()
    }
    val attrPairs = remember(editingFeature) {
        mutableStateListOf<Pair<String, String>>().apply {
            initialAttrs.forEach { (k, v) -> add(k to v) }
        }
    }
    var newAttrKey by remember { mutableStateOf("") }
    var newAttrVal by remember { mutableStateOf("") }

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        uris.forEach { uri ->
            val str = uri.toString()
            if (str !in photoUris) photoUris.add(str)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraFile?.let { photoUris.add(it.absolutePath) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (editingFeature != null) "Edit Data Fitur Survei" else "Catat Titik Survei",
                    style = MaterialTheme.typography.headlineSmall
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Offline Lokal",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama Titik / Objek") },
                placeholder = { Text("Contoh: Patok Batas B-14") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("waypoint_name_input")
            )

            Text(
                text = "Kategori Survei",
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SurveyCategory.entries.forEach { cat ->
                    val selected = cat == category
                    val chipColor = parseHexColor(cat.defaultColorHex, SurveyEmerald)
                    FilterChip(
                        selected = selected,
                        onClick = { category = cat },
                        label = { Text(cat.label) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(chipColor)
                            )
                        }
                    )
                }
            }

            if (editingFeature == null || editingFeature.geometryType == GeometryType.POINT.name) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = { Text("Latitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = { Text("Longitude") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = eleText,
                    onValueChange = { eleText = it },
                    label = { Text("Elevasi (mdpl) — opsional") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Catatan Lapangan") },
                placeholder = { Text("Kondisi medan, tutupan lahan, temuan...") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            // Multi-Photo Attachment Section (Zero-permission Photo Picker & FileProvider Camera)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Foto Dokumentasi Lapangan", style = MaterialTheme.typography.labelLarge)
                if (photoUris.isNotEmpty()) {
                    Text(
                        "${photoUris.size} foto",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val dir = File(context.filesDir, "photos").apply { mkdirs() }
                        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                        pendingCameraFile = file
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        cameraLauncher.launch(uri)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("capture_photo_button")
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ambil Foto")
                }
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pick_gallery_photo_button")
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Galeri")
                }
            }
            if (photoUris.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    photoUris.forEachIndexed { idx, uriStr ->
                        Box(
                            modifier = Modifier
                                .size(width = 148.dp, height = 112.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            val model: Any = if (uriStr.startsWith("/")) File(uriStr) else Uri.parse(uriStr)
                            AsyncImage(
                                model = model,
                                contentDescription = "Foto titik survei ${idx + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            FilledIconButton(
                                onClick = { photoUris.removeAt(idx) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(30.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Hapus foto", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Dynamic GIS Attributes Table + Smart Category Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Atribut Survei (GIS)", style = MaterialTheme.typography.labelLarge)
                TextButton(
                    onClick = {
                        val existingKeys = attrPairs.map { it.first.lowercase() }.toSet()
                        category.attributeTemplates().forEach { (k, defaultVal) ->
                            if (k.lowercase() !in existingKeys) {
                                attrPairs.add(k to defaultVal)
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("apply_attribute_template_button")
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Isi Template ${category.label.substringBefore(" ")}")
                }
            }
            attrPairs.forEachIndexed { index, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = pair.second,
                        onValueChange = { updatedVal -> attrPairs[index] = pair.first to updatedVal },
                        label = { Text(pair.first) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { attrPairs.removeAt(index) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Hapus atribut")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newAttrKey,
                    onValueChange = { newAttrKey = it },
                    label = { Text("Kolom Baru") },
                    placeholder = { Text("Diameter") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = newAttrVal,
                    onValueChange = { newAttrVal = it },
                    label = { Text("Nilai") },
                    placeholder = { Text("45 cm") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = {
                        if (newAttrKey.isNotBlank()) {
                            attrPairs.add(newAttrKey.trim() to newAttrVal.trim())
                            newAttrKey = ""
                            newAttrVal = ""
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Tambah atribut")
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Batal")
                }
                Button(
                    onClick = {
                        val parsedLat = latText.replace(',', '.').toDoubleOrNull() ?: initialCoord.latitude
                        val parsedLon = lonText.replace(',', '.').toDoubleOrNull() ?: initialCoord.longitude
                        val parsedEle = eleText.replace(',', '.').toDoubleOrNull()
                        val map = linkedMapOf<String, String>()
                        attrPairs.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) map[k] = v }
                        if (newAttrKey.isNotBlank() && newAttrVal.isNotBlank()) {
                            map[newAttrKey.trim()] = newAttrVal.trim()
                        }
                        onSave(
                            name.trim().ifBlank { "Titik Survei" },
                            description.trim(),
                            category,
                            parsedLat.coerceIn(-89.9, 89.9),
                            parsedLon.coerceIn(-180.0, 180.0),
                            parsedEle,
                            photoUris.toList(),
                            map
                        )
                    },
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("save_waypoint_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Simpan Offline")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureDetailSheet(
    feature: SpatialFeatureEntity,
    layerName: String,
    coordFormat: CoordFormat,
    distanceUnit: DistanceUnit,
    onDismiss: () -> Unit,
    onNavigateCompass: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val rings = remember(feature.coordinatesJson) { GeoMath.jsonToRings(feature.coordinatesJson) }
    val attrs = remember(feature.attributesJson) { GeoMath.jsonToAttributes(feature.attributesJson) }
    val category = runCatching { SurveyCategory.valueOf(feature.category) }.getOrDefault(SurveyCategory.UMUM)
    val geomType = runCatching { GeometryType.valueOf(feature.geometryType) }.getOrDefault(GeometryType.POINT)
    val dateStr = remember(feature.createdAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(feature.createdAt))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(category.defaultColorHex, SurveyEmerald))
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(feature.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${geomType.label} • ${category.label} • $layerName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = GeoMath.formatCoordinate(feature.latitude, feature.longitude, coordFormat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        feature.elevationMeters?.let {
                            Text("Elevasi: ${it.toInt()} mdpl", style = MaterialTheme.typography.bodySmall)
                        }
                        feature.accuracyMeters?.let {
                            Text("Akurasi: ±${String.format(Locale.US, "%.1f", it)} m", style = MaterialTheme.typography.bodySmall)
                        }
                        if (geomType == GeometryType.LINESTRING && rings.isNotEmpty()) {
                            val totalLen = rings.sumOf { GeoMath.polylineLengthMeters(it) }
                            Text(
                                "Panjang: ${GeoMath.formatDistance(totalLen, distanceUnit)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (geomType == GeometryType.POLYGON && rings.isNotEmpty()) {
                            val area = GeoMath.polygonAreaSquareMeters(rings.first())
                            Text(
                                "Luas: ${GeoMath.formatArea(area, distanceUnit)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = "Dicatat: $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (feature.description.isNotBlank()) {
                Text(feature.description, style = MaterialTheme.typography.bodyMedium)
            }

            val photos = remember(feature.photoUri) { GeoMath.decodePhotoUris(feature.photoUri) }
            if (photos.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    photos.forEachIndexed { idx, uriStr ->
                        val model: Any = if (uriStr.startsWith("/")) File(uriStr) else Uri.parse(uriStr)
                        AsyncImage(
                            model = model,
                            contentDescription = "Foto lampiran ${feature.name} ${idx + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(
                                    width = if (photos.size == 1) 300.dp else 200.dp,
                                    height = 170.dp
                                )
                                .clip(RoundedCornerShape(14.dp))
                        )
                    }
                }
            }

            if (attrs.isNotEmpty()) {
                Text("Atribut Spasial", style = MaterialTheme.typography.labelLarge)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        attrs.entries.forEachIndexed { idx, (k, v) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                            if (idx < attrs.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onNavigateCompass,
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Arahkan Kompas")
                }
                FilledTonalIconButton(
                    onClick = onEdit,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit fitur")
                }
                FilledTonalIconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Hapus fitur")
                }
            }
        }
    }
}

@Composable
fun RasterCalibrationDialog(
    pending: PendingRasterCalibration,
    onCancel: () -> Unit,
    onSelectPdfPage: (Int) -> Unit = {},
    onSwitchToGcp: (() -> Unit)? = null,
    onConfirm: (name: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, generateRoomTiles: Boolean) -> Unit
) {
    var name by remember(pending) { mutableStateOf(pending.fileName) }
    var northText by remember(pending) { mutableStateOf(String.format(Locale.US, "%.5f", pending.maxLat)) }
    var southText by remember(pending) { mutableStateOf(String.format(Locale.US, "%.5f", pending.minLat)) }
    var westText by remember(pending) { mutableStateOf(String.format(Locale.US, "%.5f", pending.minLon)) }
    var eastText by remember(pending) { mutableStateOf(String.format(Locale.US, "%.5f", pending.maxLon)) }
    var generateRoomTiles by remember(pending) { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (pending.isPdf) "Kalibrasi Peta PDF" else "Kalibrasi Peta Raster") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (pending.hasAutoGeoPdfBounds) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.GpsFixed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Koordinat GeoPDF terdeteksi otomatis (/GPTS).",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (pending.isPdf) {
                            "Pilih halaman PDF dan periksa batas koordinat WGS84 agar lembar PDF sejajar dengan posisi GPS di lapangan."
                        } else {
                            "Masukkan batas koordinat WGS84 atau gunakan Titik Kontrol (GCP) agar gambar peta sejajar dengan posisi GPS di lapangan."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Rendered PDF page or raster image preview
                val previewFile = remember(pending.localImagePath, pending.selectedPageIndex) {
                    File(pending.localImagePath)
                }
                if (previewFile.exists()) {
                    key(pending.localImagePath, pending.selectedPageIndex, previewFile.lastModified()) {
                        AsyncImage(
                            model = previewFile,
                            contentDescription = "Pratinjau peta ${pending.fileName}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }

                if (onSwitchToGcp != null) {
                    FilledTonalButton(
                        onClick = onSwitchToGcp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("switch_to_gcp_mode_button")
                    ) {
                        Icon(Icons.Outlined.ControlPointDuplicate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Georeferensi via Titik Kontrol (GCP)")
                    }
                }

                if (pending.isPdf && pending.pageCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (pending.selectedPageIndex > 0) {
                                    onSelectPdfPage(pending.selectedPageIndex - 1)
                                }
                            },
                            enabled = pending.selectedPageIndex > 0,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("pdf_prev_page_button")
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Seb")
                        }
                        Text(
                            text = "Halaman ${pending.selectedPageIndex + 1} / ${pending.pageCount}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        OutlinedButton(
                            onClick = {
                                if (pending.selectedPageIndex < pending.pageCount - 1) {
                                    onSelectPdfPage(pending.selectedPageIndex + 1)
                                }
                            },
                            enabled = pending.selectedPageIndex < pending.pageCount - 1,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("pdf_next_page_button")
                        ) {
                            Text("Ber")
                            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Layer Peta") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = northText,
                        onValueChange = { northText = it },
                        label = { Text("Utara (Max Lat)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = southText,
                        onValueChange = { southText = it },
                        label = { Text("Selatan (Min Lat)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = westText,
                        onValueChange = { westText = it },
                        label = { Text("Barat (Min Lon)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = eastText,
                        onValueChange = { eastText = it },
                        label = { Text("Timur (Max Lon)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { generateRoomTiles = !generateRoomTiles }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = generateRoomTiles,
                        onCheckedChange = { generateRoomTiles = it }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Iris & simpan sebagai Tile MBTiles di Room (offline cepat)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val n = northText.replace(',', '.').toDoubleOrNull() ?: pending.maxLat
                    val s = southText.replace(',', '.').toDoubleOrNull() ?: pending.minLat
                    val w = westText.replace(',', '.').toDoubleOrNull() ?: pending.minLon
                    val e = eastText.replace(',', '.').toDoubleOrNull() ?: pending.maxLon
                    onConfirm(name, s, n, w, e, generateRoomTiles)
                },
                modifier = Modifier.testTag("confirm_raster_calibration_button")
            ) {
                Text("Terapkan Peta")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Batal") }
        }
    )
}

@Composable
fun SaveDraftGeometryDialog(
    isPolygon: Boolean,
    summaryText: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, category: SurveyCategory, notes: String) -> Unit
) {
    var name by remember { mutableStateOf(if (isPolygon) "Poligon Area Baru" else "Jalur Ukur Baru") }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(if (isPolygon) SurveyCategory.BATAS else SurveyCategory.INFRASTRUKTUR) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPolygon) "Simpan Area Poligon" else "Simpan Jalur Garis") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(summaryText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Fitur") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Catatan") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SurveyCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = cat == category,
                            onClick = { category = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, category, notes) }) {
                Text("Simpan ke Layer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

@Composable
fun CompassTargetDialog(
    target: SpatialFeatureEntity,
    currentOrigin: LatLon,
    isFromGps: Boolean,
    deviceAzimuthDeg: Float,
    distanceUnit: DistanceUnit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val targetCoord = LatLon(target.latitude, target.longitude)
    val distanceM = GeoMath.distanceMeters(currentOrigin, targetCoord)
    val bearingDeg = GeoMath.bearingDegrees(currentOrigin, targetCoord)
    val cardinal = GeoMath.cardinalDirectionId(bearingDeg)
    val relativeAngle = ((bearingDeg.toFloat() - deviceAzimuthDeg) + 360f) % 360f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Kompas Pencari Titik", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(190.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val primaryCol = MaterialTheme.colorScheme.primary
                    val onSurfaceCol = MaterialTheme.colorScheme.onSurface
                    val surfaceVar = MaterialTheme.colorScheme.surfaceVariant
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = size.minDimension * 0.44f

                        drawCircle(surfaceVar, radius = r, center = Offset(cx, cy))
                        drawCircle(primaryCol.copy(alpha = 0.45f), radius = r, center = Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))

                        // Tick marks rotating inversely with device azimuth so North stays North
                        rotate(degrees = -deviceAzimuthDeg, pivot = Offset(cx, cy)) {
                            for (deg in 0 until 360 step 30) {
                                val rad = Math.toRadians(deg.toDouble() - 90.0)
                                val inner = if (deg % 90 == 0) r * 0.78f else r * 0.88f
                                val p1 = Offset(cx + (cos(rad) * inner).toFloat(), cy + (sin(rad) * inner).toFloat())
                                val p2 = Offset(cx + (cos(rad) * r).toFloat(), cy + (sin(rad) * r).toFloat())
                                drawLine(
                                    color = if (deg == 0) Color(0xFFDC2626) else onSurfaceCol.copy(alpha = 0.5f),
                                    start = p1,
                                    end = p2,
                                    strokeWidth = if (deg % 90 == 0) 3.dp.toPx() else 1.5.dp.toPx()
                                )
                            }
                        }

                        // Target direction arrow
                        rotate(degrees = relativeAngle, pivot = Offset(cx, cy)) {
                            val arrowPath = Path().apply {
                                moveTo(cx, cy - r * 0.72f)
                                lineTo(cx + 14.dp.toPx(), cy + 16.dp.toPx())
                                lineTo(cx, cy + 4.dp.toPx())
                                lineTo(cx - 14.dp.toPx(), cy + 16.dp.toPx())
                                close()
                            }
                            drawPath(arrowPath, color = AmberGold)
                        }

                        drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(primaryCol, radius = 4.dp.toPx(), center = Offset(cx, cy))
                    }
                }

                Text(
                    text = GeoMath.formatDistance(distanceM, distanceUnit),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Azimut Target: ${bearingDeg.toInt()}° ($cardinal) • Kompas Perangkat: ${deviceAzimuthDeg.toInt()}°",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = if (isFromGps) "Menggunakan posisi GPS aktif" else "Menggunakan posisi crosshair peta (sinyal GPS belum terkunci)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Tutup") }
        }
    )
}

@Composable
fun StartTrackingDialog(
    hasGpsFix: Boolean,
    onDismiss: () -> Unit,
    onStart: (trackName: String, category: SurveyCategory, interval: TrackIntervalPreset) -> Unit
) {
    var trackName by remember { mutableStateOf("Jejak Survei Lapangan") }
    var selectedCategory by remember { mutableStateOf(SurveyCategory.INFRASTRUKTUR) }
    var selectedInterval by remember { mutableStateOf(TrackIntervalPreset.STANDARD) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mulai Pelacakan Offline") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (hasGpsFix) {
                        "GPS aktif. Titik jalur akan direkam otomatis saat Anda bergerak, dan Anda tetap bisa menambah titik manual di peta."
                    } else {
                        "Mode terputus (offline tanpa kunci GPS). Anda dapat merekam jalur dengan mengetuk peta atau menambahkan titik dari crosshair."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = trackName,
                    onValueChange = { trackName = it },
                    label = { Text("Nama Jejak / Transek") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("track_name_input")
                )

                Text("Kategori Jalur", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        SurveyCategory.INFRASTRUKTUR,
                        SurveyCategory.BATAS,
                        SurveyCategory.VEGETASI,
                        SurveyCategory.AIR
                    ).forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }

                Text("Interval Perekaman GPS", style = MaterialTheme.typography.labelLarge)
                TrackIntervalPreset.entries.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedInterval = preset },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedInterval == preset,
                            onClick = { selectedInterval = preset }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(preset.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(trackName, selectedCategory, selectedInterval) },
                modifier = Modifier.testTag("confirm_start_track_button")
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Mulai Lacak")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

