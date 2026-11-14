package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.local.*
import com.example.spatial.GeoMath
import com.example.ui.components.parseHexColor
import com.example.ui.theme.SurveyEmerald
import java.io.File

@Composable
fun SurveyDataScreen(
    features: List<SpatialFeatureEntity>,
    layers: List<SpatialLayerEntity>,
    searchQuery: String,
    selectedCategory: SurveyCategory?,
    coordFormat: CoordFormat,
    distanceUnit: DistanceUnit,
    referenceOrigin: LatLon,
    onSearchChange: (String) -> Unit,
    onCategoryFilterChange: (SurveyCategory?) -> Unit,
    onZoomToFeature: (SpatialFeatureEntity) -> Unit,
    onOpenCompass: (SpatialFeatureEntity) -> Unit,
    onOpenDetail: (SpatialFeatureEntity) -> Unit,
    onAddNewWaypoint: () -> Unit,
    modifier: Modifier = Modifier
) {
    val layerNames = remember(layers) { layers.associate { it.id to it.name } }

    val filtered = remember(features, searchQuery, selectedCategory) {
        features.filter { feat ->
            val matchesCat = selectedCategory == null || feat.category == selectedCategory.name
            val q = searchQuery.trim().lowercase()
            val matchesQuery = q.isEmpty() ||
                feat.name.lowercase().contains(q) ||
                feat.description.lowercase().contains(q) ||
                feat.attributesJson.lowercase().contains(q)
            matchesCat && matchesQuery
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Data Fitur Survei", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${filtered.size} dari ${features.size} fitur tersimpan offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onAddNewWaypoint,
                modifier = Modifier.testTag("survey_list_add_button")
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Titik Baru")
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Cari nama titik, patok, atau catatan...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Hapus pencarian")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("survey_search_input")
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { onCategoryFilterChange(null) },
                label = { Text("Semua") }
            )
            SurveyCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = {
                        onCategoryFilterChange(if (selectedCategory == cat) null else cat)
                    },
                    label = { Text(cat.label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.ExploreOff,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Tidak ada data survei yang cocok", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tambahkan titik dari peta atau impor file GeoJSON/KML/GPX.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { feat ->
                    SurveyFeatureCard(
                        feature = feat,
                        layerName = layerNames[feat.layerId] ?: "Layer",
                        coordFormat = coordFormat,
                        distanceUnit = distanceUnit,
                        referenceOrigin = referenceOrigin,
                        onOpenDetail = { onOpenDetail(feat) },
                        onZoomToFeature = { onZoomToFeature(feat) },
                        onOpenCompass = { onOpenCompass(feat) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SurveyFeatureCard(
    feature: SpatialFeatureEntity,
    layerName: String,
    coordFormat: CoordFormat,
    distanceUnit: DistanceUnit,
    referenceOrigin: LatLon,
    onOpenDetail: () -> Unit,
    onZoomToFeature: () -> Unit,
    onOpenCompass: () -> Unit
) {
    val category = runCatching { SurveyCategory.valueOf(feature.category) }.getOrDefault(SurveyCategory.UMUM)
    val geomType = runCatching { GeometryType.valueOf(feature.geometryType) }.getOrDefault(GeometryType.POINT)
    val catColor = parseHexColor(category.defaultColorHex, SurveyEmerald)
    val distMeters = GeoMath.distanceMeters(referenceOrigin, LatLon(feature.latitude, feature.longitude))

    ElevatedCard(
        onClick = onOpenDetail,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(catColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (geomType) {
                        GeometryType.POINT -> Icons.Default.LocationOn
                        GeometryType.LINESTRING -> Icons.Default.Timeline
                        GeometryType.POLYGON -> Icons.Default.Pentagon
                    }
                    Icon(icon, contentDescription = null, tint = catColor, modifier = Modifier.size(20.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = feature.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${category.label} • $layerName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val firstPhoto = remember(feature.photoUri) { GeoMath.decodePhotoUris(feature.photoUri).firstOrNull() }
                if (!firstPhoto.isNullOrBlank()) {
                    val model: Any = if (firstPhoto.startsWith("/")) File(firstPhoto) else Uri.parse(firstPhoto)
                    AsyncImage(
                        model = model,
                        contentDescription = "Thumbnail foto",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            }

            if (feature.description.isNotBlank()) {
                Text(
                    text = feature.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = GeoMath.formatCoordinate(feature.latitude, feature.longitude, coordFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = GeoMath.formatDistance(distMeters, distanceUnit),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onZoomToFeature,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lihat di Peta")
                }
                FilledTonalButton(
                    onClick = onOpenCompass,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Kompas")
                }
            }
        }
    }
}
