package com.example.spatial

import com.example.data.local.GeometryType
import com.example.data.local.SpatialFeatureEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    GEOJSON("GeoJSON (.geojson)", "geojson", "application/geo+json"),
    KML("Google Earth KML (.kml)", "kml", "application/vnd.google-earth.kml+xml"),
    GPX("GPS Exchange GPX (.gpx)", "gpx", "application/gpx+xml"),
    CSV("Tabel CSV (.csv)", "csv", "text/csv")
}

object SpatialExporter {

    fun export(features: List<SpatialFeatureEntity>, layerTitle: String, format: ExportFormat): String {
        return when (format) {
            ExportFormat.GEOJSON -> toGeoJson(features, layerTitle)
            ExportFormat.KML -> toKml(features, layerTitle)
            ExportFormat.GPX -> toGpx(features, layerTitle)
            ExportFormat.CSV -> toCsv(features)
        }
    }

    private fun toGeoJson(features: List<SpatialFeatureEntity>, layerTitle: String): String {
        val root = JSONObject()
        root.put("type", "FeatureCollection")
        root.put("name", layerTitle)
        val featArray = JSONArray()

        for (feature in features) {
            val rings = GeoMath.jsonToRings(feature.coordinatesJson)
            if (rings.isEmpty()) continue
            val attrs = GeoMath.jsonToAttributes(feature.attributesJson)

            val geomObj = JSONObject()
            when (runCatching { GeometryType.valueOf(feature.geometryType) }.getOrDefault(GeometryType.POINT)) {
                GeometryType.POINT -> {
                    val pt = rings.first().firstOrNull() ?: continue
                    geomObj.put("type", "Point")
                    geomObj.put("coordinates", JSONArray().apply {
                        put(pt.longitude)
                        put(pt.latitude)
                        (pt.elevation ?: feature.elevationMeters)?.let { put(it) }
                    })
                }
                GeometryType.LINESTRING -> {
                    if (rings.size == 1) {
                        geomObj.put("type", "LineString")
                        geomObj.put("coordinates", ringToJsonArray(rings.first()))
                    } else {
                        geomObj.put("type", "MultiLineString")
                        geomObj.put("coordinates", JSONArray().apply {
                            rings.forEach { put(ringToJsonArray(it)) }
                        })
                    }
                }
                GeometryType.POLYGON -> {
                    val closedRing = ensureClosed(rings.first())
                    geomObj.put("type", "Polygon")
                    geomObj.put("coordinates", JSONArray().apply {
                        put(ringToJsonArray(closedRing))
                    })
                }
            }

            val props = JSONObject().apply {
                put("name", feature.name)
                if (feature.description.isNotBlank()) put("description", feature.description)
                put("category", feature.category)
                feature.elevationMeters?.let { put("elevation_m", it) }
                feature.accuracyMeters?.let { put("accuracy_m", it.toDouble()) }
                attrs.forEach { (k, v) -> put(k, v) }
            }

            featArray.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", props)
                put("geometry", geomObj)
            })
        }
        root.put("features", featArray)
        return root.toString(2)
    }

    private fun ringToJsonArray(ring: List<com.example.data.local.LatLon>): JSONArray {
        return JSONArray().apply {
            ring.forEach { pt ->
                put(JSONArray().apply {
                    put(pt.longitude)
                    put(pt.latitude)
                    pt.elevation?.let { put(it) }
                })
            }
        }
    }

    private fun ensureClosed(ring: List<com.example.data.local.LatLon>): List<com.example.data.local.LatLon> {
        if (ring.isEmpty()) return ring
        val first = ring.first()
        val last = ring.last()
        return if (first.latitude == last.latitude && first.longitude == last.longitude) ring else ring + first
    }

    private fun toKml(features: List<SpatialFeatureEntity>, layerTitle: String): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        sb.appendLine("  <Document>")
        sb.appendLine("    <name>${escapeXml(layerTitle)}</name>")

        for (feature in features) {
            val rings = GeoMath.jsonToRings(feature.coordinatesJson)
            if (rings.isEmpty()) continue
            val attrs = GeoMath.jsonToAttributes(feature.attributesJson)
            sb.appendLine("    <Placemark>")
            sb.appendLine("      <name>${escapeXml(feature.name)}</name>")
            if (feature.description.isNotBlank()) {
                sb.appendLine("      <description>${escapeXml(feature.description)}</description>")
            }
            if (attrs.isNotEmpty()) {
                sb.appendLine("      <ExtendedData>")
                attrs.forEach { (k, v) ->
                    sb.appendLine("""        <Data name="${escapeXml(k)}"><value>${escapeXml(v)}</value></Data>""")
                }
                sb.appendLine("      </ExtendedData>")
            }
            when (runCatching { GeometryType.valueOf(feature.geometryType) }.getOrDefault(GeometryType.POINT)) {
                GeometryType.POINT -> {
                    val pt = rings.first().first()
                    val alt = pt.elevation ?: feature.elevationMeters ?: 0.0
                    sb.appendLine("      <Point><coordinates>${pt.longitude},${pt.latitude},$alt</coordinates></Point>")
                }
                GeometryType.LINESTRING -> {
                    val coordStr = rings.first().joinToString(" ") { "${it.longitude},${it.latitude},${it.elevation ?: 0.0}" }
                    sb.appendLine("      <LineString><tessellate>1</tessellate><coordinates>$coordStr</coordinates></LineString>")
                }
                GeometryType.POLYGON -> {
                    val closed = ensureClosed(rings.first())
                    val coordStr = closed.joinToString(" ") { "${it.longitude},${it.latitude},${it.elevation ?: 0.0}" }
                    sb.appendLine("      <Polygon><outerBoundaryIs><LinearRing><coordinates>$coordStr</coordinates></LinearRing></outerBoundaryIs></Polygon>")
                }
            }
            sb.appendLine("    </Placemark>")
        }
        sb.appendLine("  </Document>")
        sb.appendLine("</kml>")
        return sb.toString()
    }

    private fun toGpx(features: List<SpatialFeatureEntity>, layerTitle: String): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="GeoSurvei Offline" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("  <metadata><name>${escapeXml(layerTitle)}</name></metadata>")

        for (feature in features) {
            val rings = GeoMath.jsonToRings(feature.coordinatesJson)
            if (rings.isEmpty()) continue
            val timeStr = isoFormat.format(Date(feature.createdAt))
            when (runCatching { GeometryType.valueOf(feature.geometryType) }.getOrDefault(GeometryType.POINT)) {
                GeometryType.POINT -> {
                    val pt = rings.first().first()
                    sb.appendLine("""  <wpt lat="${pt.latitude}" lon="${pt.longitude}">""")
                    (pt.elevation ?: feature.elevationMeters)?.let { sb.appendLine("    <ele>$it</ele>") }
                    sb.appendLine("    <time>$timeStr</time>")
                    sb.appendLine("    <name>${escapeXml(feature.name)}</name>")
                    if (feature.description.isNotBlank()) sb.appendLine("    <desc>${escapeXml(feature.description)}</desc>")
                    sb.appendLine("  </wpt>")
                }
                GeometryType.LINESTRING, GeometryType.POLYGON -> {
                    sb.appendLine("  <trk>")
                    sb.appendLine("    <name>${escapeXml(feature.name)}</name>")
                    if (feature.description.isNotBlank()) sb.appendLine("    <desc>${escapeXml(feature.description)}</desc>")
                    for (seg in rings) {
                        sb.appendLine("    <trkseg>")
                        for (pt in seg) {
                            sb.append("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">""")
                            pt.elevation?.let { sb.append("<ele>$it</ele>") }
                            sb.appendLine("</trkpt>")
                        }
                        sb.appendLine("    </trkseg>")
                    }
                    sb.appendLine("  </trk>")
                }
            }
        }
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    private fun toCsv(features: List<SpatialFeatureEntity>): String {
        val dynamicKeys = features.flatMap { GeoMath.jsonToAttributes(it.attributesJson).keys }.distinct()
        val sb = StringBuilder()
        val header = listOf("nama", "tipe_geometri", "kategori", "latitude", "longitude", "elevasi_m", "akurasi_m", "catatan") + dynamicKeys
        sb.appendLine(header.joinToString(",") { escapeCsv(it) })

        for (feature in features) {
            val attrs = GeoMath.jsonToAttributes(feature.attributesJson)
            val row = mutableListOf(
                feature.name,
                feature.geometryType,
                feature.category,
                feature.latitude.toString(),
                feature.longitude.toString(),
                feature.elevationMeters?.toString() ?: "",
                feature.accuracyMeters?.toString() ?: "",
                feature.description
            )
            dynamicKeys.forEach { key -> row.add(attrs[key] ?: "") }
            sb.appendLine(row.joinToString(",") { escapeCsv(it) })
        }
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun escapeCsv(s: String): String {
        val clean = s.replace("\n", " ").replace("\r", " ")
        return if (clean.contains(",") || clean.contains("\"")) {
            "\"${clean.replace("\"", "\"\"")}\""
        } else {
            clean
        }
    }
}
