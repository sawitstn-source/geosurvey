package com.example.data.local

import androidx.compose.runtime.Immutable

@Immutable
data class LatLon(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null
)

@Immutable
data class MapBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0
    val isValid: Boolean
        get() = minLat.isFinite() && maxLat.isFinite() &&
            minLon.isFinite() && maxLon.isFinite() &&
            minLat <= maxLat && minLon <= maxLon &&
            minLat >= -90.0 && maxLat <= 90.0 &&
            minLon >= -180.0 && maxLon <= 180.0
}

enum class LayerSourceType(val label: String) {
    SURVEY("Survei"),
    GEOJSON("GeoJSON"),
    KML("KML/KMZ"),
    GPX("GPX"),
    CSV("CSV"),
    MBTILES("MBTiles"),
    RASTER("Peta Raster"),
    PDF("Peta PDF")
}

enum class GeometryType(val label: String) {
    POINT("Titik"),
    LINESTRING("Jalur"),
    POLYGON("Poligon")
}

enum class SurveyCategory(val label: String, val defaultColorHex: String) {
    UMUM("Titik Umum", "#1B6B52"),
    VEGETASI("Vegetasi / Pohon", "#2E7D32"),
    BATAS("Batas / Patok", "#D97706"),
    AIR("Sumber Air / Sungai", "#0284C7"),
    INFRASTRUKTUR("Infrastruktur / Jalan", "#7C3AED"),
    BAHAYA("Rawan / Bahaya", "#DC2626"),
    SAMPEL("Sampel Tanah / Mineral", "#9A3412")
}

enum class CoordFormat(val label: String, val shortLabel: String) {
    DD("Desimal (WGS84 DD)", "DD"),
    DMS("Derajat Menit Detik (DMS)", "DMS"),
    UTM("UTM (Meter Zona)", "UTM")
}

enum class DistanceUnit(val label: String) {
    METRIC("Metrik (m / km / ha)"),
    IMPERIAL("Imperial (ft / mi / ac)")
}

enum class MapThemeMode(val label: String) {
    SYSTEM("Ikuti Sistem"),
    LIGHT("Terang Lapangan"),
    DARK("Gelap Malam")
}

@Immutable
data class LiveGpsState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val altitudeMeters: Double? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val provider: String = "Menunggu sinyal...",
    val timestampMs: Long = 0L,
    val hasFix: Boolean = false,
    val isPermissionGranted: Boolean = false
)

enum class MapToolMode {
    PAN,
    DROP_POINT,
    MEASURE_DISTANCE,
    MEASURE_AREA,
    DRAW_LINE,
    DRAW_POLYGON
}

enum class TrackIntervalPreset(val label: String, val minDistanceMeters: Double, val minTimeMs: Long) {
    HIGH_PRECISION("Presisi Tinggi (2m)", 2.0, 1500L),
    STANDARD("Standar (5m)", 5.0, 3000L),
    BATTERY_SAVER("Hemat Daya (15m)", 15.0, 8000L)
}

fun SurveyCategory.attributeTemplates(): List<Pair<String, String>> = when (this) {
    SurveyCategory.VEGETASI -> listOf("Spesies" to "", "Diameter (cm)" to "", "Tinggi (m)" to "", "Kondisi" to "Sehat")
    SurveyCategory.BATAS -> listOf("Kode Patok" to "", "Material" to "Beton", "Kondisi" to "Baik")
    SurveyCategory.AIR -> listOf("Debit (L/dtk)" to "", "pH Air" to "7.0", "Kejernihan" to "Jernih")
    SurveyCategory.INFRASTRUKTUR -> listOf("Jenis" to "Jalan Setapak", "Lebar (m)" to "", "Kondisi" to "Baik")
    SurveyCategory.BAHAYA -> listOf("Jenis Bahaya" to "Longsor", "Risiko" to "Tinggi", "Tindakan" to "")
    SurveyCategory.SAMPEL -> listOf("Kode Sampel" to "", "Tekstur" to "", "Kedalaman (cm)" to "30")
    SurveyCategory.UMUM -> listOf("Surveyor" to "", "Kondisi" to "Baik")
}

@Immutable
data class ParsedFeatureData(
    val geometryType: GeometryType,
    val name: String,
    val description: String,
    val category: SurveyCategory,
    val rings: List<List<LatLon>>,
    val attributes: Map<String, String> = emptyMap(),
    val elevationMeters: Double? = null
)

@Immutable
data class ParsedLayerData(
    val name: String,
    val sourceType: LayerSourceType,
    val bounds: MapBounds?,
    val features: List<ParsedFeatureData>,
    val localFilePath: String? = null,
    val minZoom: Int = 2,
    val maxZoom: Int = 20,
    val preferredColorHex: String? = null
)
