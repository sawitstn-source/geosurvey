package com.example.spatial

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.local.GeometryType
import com.example.data.local.LatLon
import com.example.data.local.LayerSourceType
import com.example.data.local.MapBounds
import com.example.data.local.ParsedFeatureData
import com.example.data.local.ParsedLayerData
import com.example.data.local.SurveyCategory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed class ImportResult {
    data class Ready(val layer: ParsedLayerData) : ImportResult()
    data class NeedsGeoreference(
        val fileName: String,
        val localImagePath: String,
        val suggestedBounds: MapBounds? = null,
        val isPdf: Boolean = false,
        val pdfFilePath: String? = null,
        val pageCount: Int = 1
    ) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

object SpatialFileParser {

    suspend fun parseUri(context: Context, uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val fileName = queryFileName(resolver, uri) ?: "layer_impor"
        val lower = fileName.lowercase()
        val mime = resolver.getType(uri)?.lowercase().orEmpty()

        return runCatching {
            when {
                lower.endsWith(".geojson") || lower.endsWith(".json") -> {
                    val text = readText(resolver, uri)
                    ImportResult.Ready(parseGeoJson(fileName, text))
                }
                lower.endsWith(".kml") || mime == "application/vnd.google-earth.kml+xml" -> {
                    val doc = resolver.openInputStream(uri)?.use { stream ->
                        GpxKmlParserService.Default.parse(fileName, stream, VectorFormat.KML)
                    } ?: throw IllegalStateException("Tidak dapat membuka file KML.")
                    ImportResult.Ready(doc.toParsedLayerData())
                }
                lower.endsWith(".kmz") || mime == "application/vnd.google-earth.kmz" -> {
                    val doc = resolver.openInputStream(uri)?.use { stream ->
                        GpxKmlParserService.Default.parse(fileName, stream, VectorFormat.KMZ)
                    } ?: throw IllegalStateException("Tidak dapat membuka file KMZ.")
                    ImportResult.Ready(doc.toParsedLayerData())
                }
                lower.endsWith(".gpx") || mime == "application/gpx+xml" -> {
                    val doc = resolver.openInputStream(uri)?.use { stream ->
                        GpxKmlParserService.Default.parse(fileName, stream, VectorFormat.GPX)
                    } ?: throw IllegalStateException("Tidak dapat membuka file GPX.")
                    ImportResult.Ready(doc.toParsedLayerData())
                }
                lower.endsWith(".csv") || lower.endsWith(".txt") -> {
                    val text = readText(resolver, uri)
                    ImportResult.Ready(parseCsv(fileName, text))
                }
                lower.endsWith(".mbtiles") -> {
                    parseMbTiles(context, resolver, uri, fileName)
                }
                lower.endsWith(".pdf") || mime == "application/pdf" -> {
                    val pdfDir = File(context.filesDir, "pdfs").apply { mkdirs() }
                    val pdfFile = File(pdfDir, "map_${UUID.randomUUID()}.pdf")
                    resolver.openInputStream(uri)?.use { input ->
                        pdfFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: return ImportResult.Error("Gagal menyalin file peta PDF.")

                    val inspection = PdfMapProcessor.inspectPdf(pdfFile)
                    val rasterDir = File(context.filesDir, "rasters").apply { mkdirs() }
                    val pngFile = File(rasterDir, "pdf_${UUID.randomUUID()}.png")
                    val rendered = PdfMapProcessor.renderPdfPageToPng(pdfFile, 0, pngFile)
                    if (!rendered) {
                        pdfFile.delete()
                        pngFile.delete()
                        return ImportResult.Error("Gagal merender halaman peta PDF.")
                    }
                    ImportResult.NeedsGeoreference(
                        fileName = fileName.substringBeforeLast('.'),
                        localImagePath = pngFile.absolutePath,
                        suggestedBounds = inspection.detectedGeoPdfBounds,
                        isPdf = true,
                        pdfFilePath = pdfFile.absolutePath,
                        pageCount = inspection.pageCount
                    )
                }
                lower.endsWith(".png") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".webp") ||
                    lower.endsWith(".tif") || lower.endsWith(".tiff") -> {
                    val dir = File(context.filesDir, "rasters").apply { mkdirs() }
                    val ext = fileName.substringAfterLast('.', "png")
                    val destFile = File(dir, "raster_${UUID.randomUUID()}.$ext")
                    resolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: return ImportResult.Error("Gagal menyalin file gambar raster.")
                    ImportResult.NeedsGeoreference(
                        fileName = fileName.substringBeforeLast('.'),
                        localImagePath = destFile.absolutePath
                    )
                }
                else -> {
                    // Try sniffing content as GeoJSON, KML, GPX, or CSV
                    val text = readText(resolver, uri).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                    when {
                        text.startsWith("{") -> ImportResult.Ready(parseGeoJson(fileName, text))
                        text.contains("<kml", ignoreCase = true) -> ImportResult.Ready(parseKml(fileName, text))
                        text.contains("<gpx", ignoreCase = true) -> ImportResult.Ready(parseGpx(fileName, text))
                        text.contains(",") -> ImportResult.Ready(parseCsv(fileName, text))
                        else -> ImportResult.Error(
                            "Format '$fileName' tidak dikenali. Gunakan PDF, MBTiles, GeoJSON, KML/KMZ, GPX, CSV, atau gambar raster."
                        )
                    }
                }
            }
        }.getOrElse { err ->
            ImportResult.Error("Gagal membaca '$fileName': ${err.localizedMessage ?: "Kesalahan tidak diketahui"}")
        }
    }

    private fun queryFileName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun readText(resolver: ContentResolver, uri: Uri): String {
        val raw = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Tidak dapat membuka aliran file.")
        return raw.removePrefix("\uFEFF")
    }

    private fun parseMbTiles(
        context: Context,
        resolver: ContentResolver,
        uri: Uri,
        fileName: String
    ): ImportResult {
        val dir = File(context.filesDir, "mbtiles").apply { mkdirs() }
        val destFile = File(dir, "tiles_${UUID.randomUUID()}.mbtiles")
        resolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return ImportResult.Error("Gagal menyalin file MBTiles.")

        return runCatching {
            val db = SQLiteDatabase.openDatabase(destFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.use { sqlite ->
                val meta = mutableMapOf<String, String>()
                sqlite.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        meta[cursor.getString(0)] = cursor.getString(1) ?: ""
                    }
                }
                val layerName = meta["name"]?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.')
                val bounds = meta["bounds"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.let { b ->
                    if (b.size == 4) MapBounds(minLon = b[0], minLat = b[1], maxLon = b[2], maxLat = b[3]) else null
                }
                val minZoom = meta["minzoom"]?.toIntOrNull() ?: 2
                val maxZoom = meta["maxzoom"]?.toIntOrNull() ?: 19

                // Verify tiles table exists
                sqlite.rawQuery("SELECT COUNT(*) FROM tiles LIMIT 1", null).use { c -> c.moveToFirst() }

                ImportResult.Ready(
                    ParsedLayerData(
                        name = layerName,
                        sourceType = LayerSourceType.MBTILES,
                        bounds = bounds,
                        features = emptyList(),
                        localFilePath = destFile.absolutePath,
                        minZoom = minZoom,
                        maxZoom = maxZoom
                    )
                )
            }
        }.getOrElse {
            destFile.delete()
            ImportResult.Error("File MBTiles tidak valid atau tidak memiliki tabel 'tiles' & 'metadata'.")
        }
    }

    fun parseGeoJson(fileName: String, text: String): ParsedLayerData {
        val root = JSONObject(text.trim())
        val layerName = root.optString("name").takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        val features = mutableListOf<ParsedFeatureData>()
        val allPts = mutableListOf<LatLon>()

        when (root.optString("type")) {
            "FeatureCollection" -> {
                val arr = root.optJSONArray("features") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val feat = arr.optJSONObject(i) ?: continue
                    extractGeoJsonFeature(feat, i + 1, features, allPts)
                }
            }
            "Feature" -> extractGeoJsonFeature(root, 1, features, allPts)
            else -> {
                extractGeoJsonGeometry(
                    geom = root,
                    name = layerName,
                    desc = "",
                    attrs = emptyMap(),
                    outFeatures = features,
                    allPts = allPts
                )
            }
        }

        if (features.isEmpty()) {
            throw IllegalArgumentException("GeoJSON tidak memiliki geometri yang dapat dibaca.")
        }

        return ParsedLayerData(
            name = layerName,
            sourceType = LayerSourceType.GEOJSON,
            bounds = GeoMath.computeBounds(allPts),
            features = features
        )
    }

    private fun extractGeoJsonFeature(
        feat: JSONObject,
        index: Int,
        outFeatures: MutableList<ParsedFeatureData>,
        allPts: MutableList<LatLon>
    ) {
        val geom = feat.optJSONObject("geometry") ?: return
        val props = feat.optJSONObject("properties")
        val attrs = LinkedHashMap<String, String>()
        if (props != null) {
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = props.opt(k)
                if (v != null && v != JSONObject.NULL) {
                    attrs[k] = v.toString()
                }
            }
        }
        val name = attrs["name"] ?: attrs["Name"] ?: attrs["NAME"]
            ?: attrs["title"] ?: attrs["nama"] ?: "Fitur #$index"
        val desc = attrs["description"] ?: attrs["desc"] ?: attrs["keterangan"] ?: ""
        extractGeoJsonGeometry(geom, name, desc, attrs, outFeatures, allPts)
    }

    private fun extractGeoJsonGeometry(
        geom: JSONObject,
        name: String,
        desc: String,
        attrs: Map<String, String>,
        outFeatures: MutableList<ParsedFeatureData>,
        allPts: MutableList<LatLon>
    ) {
        val type = geom.optString("type")
        val coords = geom.optJSONArray("coordinates")
        val cat = inferCategory(name, desc, attrs)
        when (type) {
            "Point" -> if (coords != null) {
                parseCoordPair(coords)?.let { pt ->
                    allPts.add(pt)
                    outFeatures.add(
                        ParsedFeatureData(
                            geometryType = GeometryType.POINT,
                            name = name,
                            description = desc,
                            category = cat,
                            rings = listOf(listOf(pt)),
                            attributes = attrs,
                            elevationMeters = pt.elevation
                        )
                    )
                }
            }
            "MultiPoint" -> if (coords != null) {
                for (i in 0 until coords.length()) {
                    coords.optJSONArray(i)?.let { parseCoordPair(it) }?.let { pt ->
                        allPts.add(pt)
                        outFeatures.add(
                            ParsedFeatureData(
                                geometryType = GeometryType.POINT,
                                name = "$name (${i + 1})",
                                description = desc,
                                category = cat,
                                rings = listOf(listOf(pt)),
                                attributes = attrs,
                                elevationMeters = pt.elevation
                            )
                        )
                    }
                }
            }
            "LineString" -> if (coords != null) {
                val line = parseCoordList(coords)
                if (line.size >= 2) {
                    allPts.addAll(line)
                    outFeatures.add(
                        ParsedFeatureData(
                            geometryType = GeometryType.LINESTRING,
                            name = name,
                            description = desc,
                            category = cat,
                            rings = listOf(line),
                            attributes = attrs
                        )
                    )
                }
            }
            "MultiLineString" -> if (coords != null) {
                val parts = mutableListOf<List<LatLon>>()
                for (i in 0 until coords.length()) {
                    val line = coords.optJSONArray(i)?.let { parseCoordList(it) }.orEmpty()
                    if (line.size >= 2) {
                        parts.add(line)
                        allPts.addAll(line)
                    }
                }
                if (parts.isNotEmpty()) {
                    outFeatures.add(
                        ParsedFeatureData(
                            geometryType = GeometryType.LINESTRING,
                            name = name,
                            description = desc,
                            category = cat,
                            rings = parts,
                            attributes = attrs
                        )
                    )
                }
            }
            "Polygon" -> if (coords != null) {
                val outer = coords.optJSONArray(0)?.let { parseCoordList(it) }.orEmpty()
                if (outer.size >= 3) {
                    allPts.addAll(outer)
                    outFeatures.add(
                        ParsedFeatureData(
                            geometryType = GeometryType.POLYGON,
                            name = name,
                            description = desc,
                            category = cat,
                            rings = listOf(outer),
                            attributes = attrs
                        )
                    )
                }
            }
            "MultiPolygon" -> if (coords != null) {
                val polys = mutableListOf<List<LatLon>>()
                for (i in 0 until coords.length()) {
                    val polyArr = coords.optJSONArray(i) ?: continue
                    val outer = polyArr.optJSONArray(0)?.let { parseCoordList(it) }.orEmpty()
                    if (outer.size >= 3) {
                        polys.add(outer)
                        allPts.addAll(outer)
                    }
                }
                if (polys.isNotEmpty()) {
                    outFeatures.add(
                        ParsedFeatureData(
                            geometryType = GeometryType.POLYGON,
                            name = name,
                            description = desc,
                            category = cat,
                            rings = polys,
                            attributes = attrs
                        )
                    )
                }
            }
            "GeometryCollection" -> {
                val geoms = geom.optJSONArray("geometries") ?: return
                for (i in 0 until geoms.length()) {
                    geoms.optJSONObject(i)?.let {
                        extractGeoJsonGeometry(it, "$name #${i + 1}", desc, attrs, outFeatures, allPts)
                    }
                }
            }
        }
    }

    private fun parseCoordPair(arr: JSONArray): LatLon? {
        if (arr.length() < 2) return null
        val lon = arr.optDouble(0)
        val lat = arr.optDouble(1)
        val ele = if (arr.length() >= 3 && !arr.isNull(2)) arr.optDouble(2) else null
        return if (lat.isFinite() && lon.isFinite()) LatLon(lat, lon, ele) else null
    }

    private fun parseCoordList(arr: JSONArray): List<LatLon> {
        val list = ArrayList<LatLon>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONArray(i)?.let { parseCoordPair(it) }?.let { list.add(it) }
        }
        return list
    }

    fun parseKml(fileName: String, text: String): ParsedLayerData {
        return GpxKmlParserService.Default.parseKml(fileName, text).toParsedLayerData()
    }

    fun parseGpx(fileName: String, text: String): ParsedLayerData {
        return GpxKmlParserService.Default.parseGpx(fileName, text).toParsedLayerData()
    }

    fun parseCsv(fileName: String, text: String): ParsedLayerData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) throw IllegalArgumentException("CSV harus memiliki baris header dan minimal 1 baris data.")

        val headerLine = lines.first()
        val delimiter = when {
            headerLine.count { it == ';' } > headerLine.count { it == ',' } -> ';'
            headerLine.count { it == '\t' } > headerLine.count { it == ',' } -> '\t'
            else -> ','
        }

        val headers = splitCsvRow(headerLine, delimiter).map { it.trim() }
        val lowerHeaders = headers.map { it.lowercase() }

        val latIdx = lowerHeaders.indexOfFirst {
            it in listOf("lat", "latitude", "lintang", "y", "coord_y")
        }
        val lonIdx = lowerHeaders.indexOfFirst {
            it in listOf("lon", "lng", "long", "longitude", "bujur", "x", "coord_x")
        }
        if (latIdx < 0 || lonIdx < 0) {
            throw IllegalArgumentException("Header CSV harus memiliki kolom Latitude (lat/y) dan Longitude (lon/lng/x).")
        }

        val nameIdx = lowerHeaders.indexOfFirst {
            it in listOf("name", "nama", "title", "label", "id", "kode", "titik")
        }
        val descIdx = lowerHeaders.indexOfFirst {
            it in listOf("desc", "description", "notes", "catatan", "keterangan")
        }
        val eleIdx = lowerHeaders.indexOfFirst {
            it in listOf("ele", "elev", "elevation", "alt", "altitude", "mdpl", "z")
        }

        val features = mutableListOf<ParsedFeatureData>()
        val allPts = mutableListOf<LatLon>()

        for (r in 1 until lines.size) {
            val cols = splitCsvRow(lines[r], delimiter)
            if (cols.size <= maxOf(latIdx, lonIdx)) continue
            val lat = cols[latIdx].trim().replace(',', '.').toDoubleOrNull() ?: continue
            val lon = cols[lonIdx].trim().replace(',', '.').toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val ele = eleIdx.takeIf { it >= 0 && it < cols.size }?.let { cols[it].trim().toDoubleOrNull() }
            val pt = LatLon(lat, lon, ele)
            allPts.add(pt)

            val name = nameIdx.takeIf { it >= 0 && it < cols.size }?.let { cols[it].trim() }
                ?.takeIf { it.isNotEmpty() } ?: "Titik CSV #$r"
            val desc = descIdx.takeIf { it >= 0 && it < cols.size }?.let { cols[it].trim() } ?: ""

            val attrs = LinkedHashMap<String, String>()
            headers.forEachIndexed { idx, h ->
                if (idx != latIdx && idx != lonIdx && idx < cols.size && cols[idx].isNotBlank()) {
                    attrs[h] = cols[idx].trim()
                }
            }

            features.add(
                ParsedFeatureData(
                    geometryType = GeometryType.POINT,
                    name = name,
                    description = desc,
                    category = inferCategory(name, desc, attrs),
                    rings = listOf(listOf(pt)),
                    attributes = attrs,
                    elevationMeters = ele
                )
            )
        }

        if (features.isEmpty()) {
            throw IllegalArgumentException("Tidak ada koordinat valid yang ditemukan di CSV.")
        }

        return ParsedLayerData(
            name = fileName.substringBeforeLast('.'),
            sourceType = LayerSourceType.CSV,
            bounds = GeoMath.computeBounds(allPts),
            features = features
        )
    }

    private fun splitCsvRow(row: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in row) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString())
        return result
    }

    private fun inferCategory(
        name: String,
        desc: String,
        attrs: Map<String, String>
    ): SurveyCategory {
        val combined = "$name $desc ${attrs.values.joinToString(" ")}".lowercase()
        return when {
            combined.containsAny("pohon", "hutan", "vegetasi", "tree", "flora", "tanaman") -> SurveyCategory.VEGETASI
            combined.containsAny("batas", "patok", "blok", "bm", "border", "parcel", "lahan") -> SurveyCategory.BATAS
            combined.containsAny("air", "sungai", "mata air", "river", "water", "danau", "hulu") -> SurveyCategory.AIR
            combined.containsAny("jalan", "jembatan", "pos", "kampung", "road", "trail", "track") -> SurveyCategory.INFRASTRUKTUR
            combined.containsAny("longsor", "bahaya", "rawan", "hazard", "danger", "banjir") -> SurveyCategory.BAHAYA
            combined.containsAny("tanah", "mineral", "batu", "soil", "sampel", "sample") -> SurveyCategory.SAMPEL
            else -> SurveyCategory.UMUM
        }
    }

    private fun String.containsAny(vararg words: String): Boolean = words.any { this.contains(it) }
}
