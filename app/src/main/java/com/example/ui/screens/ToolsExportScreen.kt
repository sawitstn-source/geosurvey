package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.*
import com.example.spatial.ExportFormat

@Composable
fun ToolsExportScreen(
    prefs: UserPreferences,
    featureCount: Int,
    layerCount: Int,
    onSetCoordFormat: (CoordFormat) -> Unit,
    onSetDistanceUnit: (DistanceUnit) -> Unit,
    onSetThemeMode: (MapThemeMode) -> Unit,
    onSetShowGrid: (Boolean) -> Unit,
    onSetShowCrosshair: (Boolean) -> Unit,
    onSaveExportFile: (ExportFormat) -> Unit,
    onShareExport: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Ekspor & Pengaturan Lapangan", style = MaterialTheme.typography.headlineSmall)

        // Export Hub Card
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.IosShare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Ekspor Semua Data Survei", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$featureCount fitur dari $layerCount layer siap diekspor offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ExportFormat.entries.forEach { format ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(format.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { onSaveExportFile(format) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("export_save_${format.extension}")
                                ) {
                                    Text("Simpan")
                                }
                                FilledTonalButton(
                                    onClick = { onShareExport(format) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("export_share_${format.extension}")
                                ) {
                                    Text("Kirim")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Coordinate System Card
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Format Koordinat", style = MaterialTheme.typography.titleMedium)
                CoordFormat.entries.forEach { fmt ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = prefs.coordFormat == fmt,
                            onClick = { onSetCoordFormat(fmt) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(fmt.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider()
                Text("Satuan Pengukuran", style = MaterialTheme.typography.titleMedium)
                DistanceUnit.entries.forEach { unit ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = prefs.distanceUnit == unit,
                            onClick = { onSetDistanceUnit(unit) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(unit.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Map Display & Theme Preferences
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tampilan Kanvas Peta", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tampilkan Grid Koordinat", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = prefs.showGridLines, onCheckedChange = onSetShowGrid)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tampilkan Crosshair Tengah", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = prefs.showCrosshair, onCheckedChange = onSetShowCrosshair)
                }

                HorizontalDivider()
                Text("Tema Survei", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.mapThemeMode == mode,
                            onClick = { onSetThemeMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }
        }

        // Offline GIS Format Guide
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Panduan Impor Data Offline", style = MaterialTheme.typography.titleSmall)
                Text(
                    "• MBTiles (.mbtiles): Peta dasar raster offline dari QGIS / Mobile Atlas Creator.\n" +
                        "• Peta Raster (.png, .jpg): Peta citra/topografi dengan kalibrasi batas WGS84.\n" +
                        "• GeoJSON, KML/KMZ, GPX, CSV: Data batas blok, jaringan jalan, & titik sampel lapangan.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
