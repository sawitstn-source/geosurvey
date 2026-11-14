package com.example.spatial

import com.example.data.local.DistanceUnit
import com.example.data.local.GeometryType
import com.example.data.local.LatLon
import com.example.data.local.LayerSourceType
import com.example.data.local.MapBounds
import com.example.data.local.ParsedFeatureData
import com.example.data.local.ParsedLayerData
import com.example.data.local.SpatialFeatureEntity
import com.example.data.local.SurveyCategory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipInputStream

enum class VectorFormat(val label: String) {
    GPX("GPX"),
    KML("KML"),
    KMZ("KMZ")
}

/**
 * Common internal feature representation shared by GPX and KML/KMZ parsers
 * prior to map rendering or Room persistence.
 */
data class VectorMapFeature(
    val name: String,
    val description: String,
    val geometryType: GeometryType,
    val category: SurveyCategory,
    val coordinateRings: List<List<LatLon>>,
    val attributes: Map<String, String> = emptyMap(),
    val styleColorHex: String? = null,
    val elevationMeters: Double? = null,
    val timestampIso: String? = null
) {
    fun toParsedFeatureData(): ParsedFeatureData = ParsedFeatureData(
        geometryType = geometryType,
        name = name,
        description = description,
        category = category,
        rings = coordinateRings,
        attributes = attributes,
        elevationMeters = elevationMeters
    )

    fun toSpatialFeatureEntity(layerId: Long): SpatialFeatureEntity {
        val allPts = coordinateRings.flatten()
        val fBounds = GeoMath.computeBounds(allPts)
        val firstPt = allPts.firstOrNull()
        return SpatialFeatureEntity(
            layerId = layerId,
            geometryType = geometryType.name,
            name = name,
            description = description,
            category = category.name,
            coordinatesJson = GeoMath.ringsToJson(coordinateRings),
            attributesJson = GeoMath.attributesToJson(attributes),
            latitude = fBounds?.centerLat ?: firstPt?.latitude ?: 0.0,
            longitude = fBounds?.centerLon ?: firstPt?.longitude ?: 0.0,
            elevationMeters = elevationMeters ?: firstPt?.elevation
        )
    }
}

/**
 * Common internal document representation returned by GpxKmlParserService
 * so GPX, KML, and KMZ files can be uniformly persisted and rendered on the map canvas.
 */
data class VectorMapDocument(
    val title: String,
    val description: String,
    val format: VectorFormat,
    val bounds: MapBounds?,
    val features: List<VectorMapFeature>,
    val preferredColorHex: String? = null
) {
    val pointCount: Int get() = features.count { it.geometryType == GeometryType.POINT }
    val lineCount: Int get() = features.count { it.geometryType == GeometryType.LINESTRING }
    val polygonCount: Int get() = features.count { it.geometryType == GeometryType.POLYGON }

    fun toParsedLayerData(): ParsedLayerData = ParsedLayerData(
        name = title,
        sourceType = when (format) {
            VectorFormat.GPX -> LayerSourceType.GPX
            VectorFormat.KML, VectorFormat.KMZ -> LayerSourceType.KML
        },
        bounds = bounds,
        features = features.map { it.toParsedFeatureData() },
        preferredColorHex = preferredColorHex
    )
}

/**
 * Service responsible for parsing GPX, KML, and KMZ spatial files into the unified
 * [VectorMapDocument] / [ParsedLayerData] internal data structure for map rendering.
 */
class GpxKmlParserService {

    fun parse(
        fileName: String,
        inputStream: InputStream,
        formatHint: VectorFormat? = null
    ): VectorMapDocument {
        val lower = fileName.lowercase(Locale.US)
        val detectedFormat = formatHint ?: when {
            lower.endsWith(".kmz") -> VectorFormat.KMZ
            lower.endsWith(".kml") -> VectorFormat.KML
            lower.endsWith(".gpx") -> VectorFormat.GPX
            else -> null
        }

        if (detectedFormat == VectorFormat.KMZ) {
            return parseKmz(fileName, inputStream)
        }

        val text = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix("\uFEFF")
        return parseText(fileName, text, detectedFormat)
    }

    fun parseText(
        fileName: String,
        xmlContent: String,
        formatHint: VectorFormat? = null
    ): VectorMapDocument {
        val trimmed = xmlContent.removePrefix("\uFEFF").trimStart()
        val format = formatHint ?: when {
            trimmed.contains("<gpx", ignoreCase = true) -> VectorFormat.GPX
            trimmed.contains("<kml", ignoreCase = true) -> VectorFormat.KML
            else -> throw IllegalArgumentException("Format XML '$fileName' bukan dokumen GPX atau KML yang valid.")
        }
        return when (format) {
            VectorFormat.GPX -> parseGpx(fileName, trimmed)
            VectorFormat.KML, VectorFormat.KMZ -> parseKml(fileName, trimmed, format)
        }
    }

    fun parseKmz(fileName: String, zipStream: InputStream): VectorMapDocument {
        val kmlEntries = mutableListOf<Pair<String, String>>()
        ZipInputStream(zipStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase(Locale.US).endsWith(".kml")) {
                    val content = zis.bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")
                    kmlEntries.add(entry.name to content)
                }
                entry = zis.nextEntry
            }
        }
        if (kmlEntries.isEmpty()) {
            throw IllegalArgumentException("File KMZ '$fileName' tidak berisi dokumen KML yang valid.")
        }
        // Prefer doc.kml if present, otherwise parse and merge all KML entries cleanly
        val primary = kmlEntries.firstOrNull { it.first.equals("doc.kml", ignoreCase = true) } ?: kmlEntries.first()
        if (kmlEntries.size == 1) {
            return parseKml(fileName, primary.second, VectorFormat.KMZ)
        }

        val mergedFeatures = mutableListOf<VectorMapFeature>()
        var docTitle = fileName.substringBeforeLast('.')
        var docDesc = ""
        var preferredColor: String? = null
        val allPts = mutableListOf<LatLon>()

        kmlEntries.forEachIndexed { idx, (_, kml) ->
            runCatching { parseKml(fileName, kml, VectorFormat.KMZ) }.getOrNull()?.let { doc ->
                if (idx == 0 && doc.title.isNotBlank()) docTitle = doc.title
                if (docDesc.isBlank()) docDesc = doc.description
                if (preferredColor == null) preferredColor = doc.preferredColorHex
                mergedFeatures.addAll(doc.features)
                doc.features.forEach { f -> f.coordinateRings.forEach { allPts.addAll(it) } }
            }
        }
        if (mergedFeatures.isEmpty()) {
            throw IllegalArgumentException("File KMZ '$fileName' tidak berisi fitur geografis yang valid.")
        }
        return VectorMapDocument(
            title = docTitle,
            description = docDesc,
            format = VectorFormat.KMZ,
            bounds = GeoMath.computeBounds(allPts),
            features = mergedFeatures,
            preferredColorHex = preferredColor
        )
    }

    fun parseGpx(fileName: String, xmlContent: String): VectorMapDocument {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlContent.removePrefix("\uFEFF")))

        var metadataName: String? = null
        var metadataDesc = ""
        val features = mutableListOf<VectorMapFeature>()
        val allPts = mutableListOf<LatLon>()

        var inMetadata = false
        var inWpt = false
        var inTrk = false
        var inRte = false
        var inExtensions = false

        var wptLat: Double? = null
        var wptLon: Double? = null
        var wptEle: Double? = null
        var wptTime: String? = null

        var itemName = ""
        var itemDesc = ""
        var itemSym = ""
        var itemType = ""
        val extraAttrs = LinkedHashMap<String, String>()

        var trackSegments = mutableListOf<List<LatLon>>()
        var currentSegment = mutableListOf<LatLon>()
        var currentPtLat: Double? = null
        var currentPtLon: Double? = null
        var currentPtEle: Double? = null
        var firstTrackTime: String? = null
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name?.lowercase(Locale.US)
                    currentTag = tag
                    when (tag) {
                        "metadata" -> inMetadata = true
                        "extensions" -> inExtensions = true
                        "wpt" -> {
                            inWpt = true
                            wptLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            wptLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            wptEle = null
                            wptTime = null
                            itemName = ""
                            itemDesc = ""
                            itemSym = ""
                            itemType = ""
                            extraAttrs.clear()
                        }
                        "trk" -> {
                            inTrk = true
                            itemName = ""
                            itemDesc = ""
                            itemSym = ""
                            itemType = ""
                            firstTrackTime = null
                            extraAttrs.clear()
                            trackSegments = mutableListOf()
                        }
                        "rte" -> {
                            inRte = true
                            itemName = ""
                            itemDesc = ""
                            itemSym = ""
                            itemType = ""
                            firstTrackTime = null
                            extraAttrs.clear()
                            trackSegments = mutableListOf()
                            currentSegment = mutableListOf()
                        }
                        "trkseg" -> currentSegment = mutableListOf()
                        "trkpt", "rtept" -> {
                            currentPtLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentPtLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentPtEle = null
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val v = parser.text?.trim().orEmpty()
                    if (v.isNotEmpty() && currentTag != null) {
                        when {
                            inMetadata && !inWpt && !inTrk && !inRte -> {
                                when (currentTag) {
                                    "name" -> if (metadataName == null) metadataName = v
                                    "desc" -> if (metadataDesc.isBlank()) metadataDesc = v
                                }
                            }
                            inExtensions && (inWpt || inTrk || inRte) -> {
                                val key = currentTag!!.replaceFirstChar { it.uppercaseChar() }
                                extraAttrs[key] = v
                            }
                            inWpt || inTrk || inRte -> {
                                when (currentTag) {
                                    "name" -> itemName = v
                                    "desc", "cmt" -> if (itemDesc.isBlank()) itemDesc = v
                                    "sym" -> itemSym = v
                                    "type" -> itemType = v
                                    "time" -> {
                                        if (inWpt) wptTime = v
                                        else if (firstTrackTime == null) firstTrackTime = v
                                    }
                                    "ele" -> {
                                        val eleVal = v.toDoubleOrNull()
                                        if (inWpt) wptEle = eleVal
                                        else if (currentPtLat != null) currentPtEle = eleVal
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name?.lowercase(Locale.US)
                    when (tag) {
                        "metadata" -> inMetadata = false
                        "extensions" -> inExtensions = false
                        "wpt" -> {
                            val lat = wptLat
                            val lon = wptLon
                            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                                val pt = LatLon(lat, lon, wptEle)
                                allPts.add(pt)
                                val fName = itemName.ifBlank { "Waypoint #${features.size + 1}" }
                                val attrs = LinkedHashMap<String, String>(extraAttrs).apply {
                                    put("Format", "GPX Waypoint")
                                    if (itemSym.isNotBlank()) put("Simbol", itemSym)
                                    if (itemType.isNotBlank()) put("Tipe", itemType)
                                    if (!wptTime.isNullOrBlank()) put("Waktu", wptTime!!)
                                }
                                features.add(
                                    VectorMapFeature(
                                        name = fName,
                                        description = itemDesc,
                                        geometryType = GeometryType.POINT,
                                        category = inferCategory(fName, "$itemDesc $itemSym $itemType", attrs),
                                        coordinateRings = listOf(listOf(pt)),
                                        attributes = attrs,
                                        elevationMeters = wptEle,
                                        timestampIso = wptTime
                                    )
                                )
                            }
                            inWpt = false
                        }
                        "trkpt", "rtept" -> {
                            val lat = currentPtLat
                            val lon = currentPtLon
                            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                                val pt = LatLon(lat, lon, currentPtEle)
                                currentSegment.add(pt)
                                allPts.add(pt)
                            }
                            currentPtLat = null
                            currentPtLon = null
                            currentPtEle = null
                        }
                        "trkseg" -> {
                            if (currentSegment.size >= 2) trackSegments.add(currentSegment.toList())
                        }
                        "trk" -> {
                            if (trackSegments.isNotEmpty()) {
                                val fName = itemName.ifBlank { "Jejak GPX #${features.size + 1}" }
                                val flatPts = trackSegments.flatten()
                                val totalDist = trackSegments.sumOf { GeoMath.polylineLengthMeters(it) }
                                val elevGain = trackSegments.sumOf { GeoMath.elevationGainMeters(it) }
                                val attrs = LinkedHashMap<String, String>(extraAttrs).apply {
                                    put("Format", "GPX Track")
                                    put("Jarak", GeoMath.formatDistance(totalDist, DistanceUnit.METRIC))
                                    put("Segmen", trackSegments.size.toString())
                                    put("Jumlah Titik", flatPts.size.toString())
                                    if (elevGain > 0.5) put("Tanjakan (+)", "${elevGain.toInt()} m")
                                    if (itemType.isNotBlank()) put("Tipe", itemType)
                                    if (!firstTrackTime.isNullOrBlank()) put("Waktu", firstTrackTime!!)
                                }
                                features.add(
                                    VectorMapFeature(
                                        name = fName,
                                        description = itemDesc,
                                        geometryType = GeometryType.LINESTRING,
                                        category = inferCategory(fName, "$itemDesc $itemType", attrs, GeometryType.LINESTRING),
                                        coordinateRings = trackSegments.toList(),
                                        attributes = attrs,
                                        elevationMeters = flatPts.firstOrNull()?.elevation,
                                        timestampIso = firstTrackTime
                                    )
                                )
                            }
                            inTrk = false
                        }
                        "rte" -> {
                            if (currentSegment.size >= 2) {
                                val fName = itemName.ifBlank { "Rute GPX #${features.size + 1}" }
                                val dist = GeoMath.polylineLengthMeters(currentSegment)
                                val attrs = LinkedHashMap<String, String>(extraAttrs).apply {
                                    put("Format", "GPX Route")
                                    put("Jarak", GeoMath.formatDistance(dist, DistanceUnit.METRIC))
                                    put("Jumlah Titik", currentSegment.size.toString())
                                    if (itemType.isNotBlank()) put("Tipe", itemType)
                                }
                                features.add(
                                    VectorMapFeature(
                                        name = fName,
                                        description = itemDesc,
                                        geometryType = GeometryType.LINESTRING,
                                        category = inferCategory(fName, "$itemDesc $itemType", attrs, GeometryType.LINESTRING),
                                        coordinateRings = listOf(currentSegment.toList()),
                                        attributes = attrs,
                                        elevationMeters = currentSegment.firstOrNull()?.elevation,
                                        timestampIso = firstTrackTime
                                    )
                                )
                            }
                            inRte = false
                        }
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }

        if (features.isEmpty()) {
            throw IllegalArgumentException("File GPX tidak berisi waypoint, rute, atau jejak yang valid.")
        }

        return VectorMapDocument(
            title = metadataName?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
            description = metadataDesc,
            format = VectorFormat.GPX,
            bounds = GeoMath.computeBounds(allPts),
            features = features
        )
    }

    fun parseKml(
        fileName: String,
        xmlContent: String,
        sourceFormat: VectorFormat = VectorFormat.KML
    ): VectorMapDocument {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xmlContent.removePrefix("\uFEFF")))

        var docName: String? = null
        var docDesc = ""
        val folderStack = ArrayDeque<String>()
        val stylesById = mutableMapOf<String, String>()
        val styleMapPrimary = mutableMapOf<String, String>()

        val features = mutableListOf<VectorMapFeature>()
        val allPts = mutableListOf<LatLon>()

        var inDocument = false
        var inFolder = false
        var inPlacemark = false
        var inStyle = false
        var inStyleMap = false
        var inStylePair = false
        var currentStyleId: String? = null
        var currentStyleMapId: String? = null
        var currentPairKey = ""
        var currentPairUrl = ""

        var inPoint = false
        var inLineString = false
        var inPolygon = false
        var inOuterBoundary = false
        var inInnerBoundary = false
        var inGxTrack = false
        var gxTrackPts = mutableListOf<LatLon>()

        var currentName = ""
        var currentDesc = ""
        var currentStyleUrl = ""
        var currentTimestamp: String? = null
        var currentDataKey: String? = null
        val currentAttrs = LinkedHashMap<String, String>()
        val placemarkGeoms = mutableListOf<Pair<GeometryType, List<List<LatLon>>>>()
        var currentTag: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name?.lowercase(Locale.US)
                    currentTag = tag
                    when (tag) {
                        "document" -> inDocument = true
                        "folder" -> {
                            inFolder = true
                            folderStack.addLast("")
                        }
                        "style" -> {
                            inStyle = true
                            currentStyleId = parser.getAttributeValue(null, "id")
                        }
                        "stylemap" -> {
                            inStyleMap = true
                            currentStyleMapId = parser.getAttributeValue(null, "id")
                        }
                        "pair" -> if (inStyleMap) {
                            inStylePair = true
                            currentPairKey = ""
                            currentPairUrl = ""
                        }
                        "placemark" -> {
                            inPlacemark = true
                            currentName = ""
                            currentDesc = ""
                            currentStyleUrl = ""
                            currentTimestamp = null
                            currentAttrs.clear()
                            placemarkGeoms.clear()
                            gxTrackPts.clear()
                        }
                        "point" -> inPoint = true
                        "linestring" -> inLineString = true
                        "polygon" -> inPolygon = true
                        "outerboundaryis" -> inOuterBoundary = true
                        "innerboundaryis" -> inInnerBoundary = true
                        "track" -> { // matches <gx:Track> when namespace-aware
                            inGxTrack = true
                            gxTrackPts = mutableListOf()
                        }
                        "data", "simpledata" -> {
                            currentDataKey = parser.getAttributeValue(null, "name")
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isNotEmpty() && currentTag != null) {
                        when {
                            inStyle && currentStyleId != null && currentTag == "color" -> {
                                parseKmlAbgrToHex(value)?.let { hex ->
                                    stylesById.putIfAbsent(currentStyleId!!, hex)
                                }
                            }
                            inStylePair && currentTag == "key" -> currentPairKey = value
                            inStylePair && currentTag == "styleurl" -> currentPairUrl = value
                            inFolder && !inPlacemark && currentTag == "name" -> {
                                if (folderStack.isNotEmpty()) {
                                    folderStack.removeLast()
                                    folderStack.addLast(value)
                                }
                            }
                            inDocument && !inFolder && !inPlacemark && !inStyle && currentTag == "name" && docName == null -> {
                                docName = value
                            }
                            inDocument && !inFolder && !inPlacemark && !inStyle && currentTag == "description" && docDesc.isBlank() -> {
                                docDesc = value.replace(Regex("<[^>]*>"), " ").trim()
                            }
                            inPlacemark && currentTag == "name" -> currentName = value
                            inPlacemark && currentTag == "description" -> {
                                currentDesc = value.replace(Regex("<[^>]*>"), " ").trim()
                            }
                            inPlacemark && currentTag == "styleurl" -> currentStyleUrl = value
                            inPlacemark && (currentTag == "when" || currentTag == "begin") && currentTimestamp == null -> {
                                currentTimestamp = value
                            }
                            inPlacemark && (currentTag == "value" || currentTag == "simpledata") && currentDataKey != null -> {
                                currentAttrs[currentDataKey!!] = value
                            }
                            inPlacemark && inGxTrack && currentTag == "coord" -> {
                                parseGxCoord(value)?.let { gxTrackPts.add(it) }
                            }
                            inPlacemark && currentTag == "coordinates" -> {
                                if (!inInnerBoundary) {
                                    val pts = parseKmlCoordinates(value)
                                    if (pts.isNotEmpty()) {
                                        val geomType = when {
                                            inPolygon || inOuterBoundary -> GeometryType.POLYGON
                                            inLineString -> GeometryType.LINESTRING
                                            inPoint -> GeometryType.POINT
                                            pts.size >= 3 && pts.first() == pts.last() -> GeometryType.POLYGON
                                            pts.size >= 2 -> GeometryType.LINESTRING
                                            else -> GeometryType.POINT
                                        }
                                        placemarkGeoms.add(geomType to listOf(pts))
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name?.lowercase(Locale.US)
                    when (tag) {
                        "document" -> inDocument = false
                        "folder" -> {
                            if (folderStack.isNotEmpty()) folderStack.removeLast()
                            inFolder = folderStack.isNotEmpty()
                        }
                        "style" -> {
                            inStyle = false
                            currentStyleId = null
                        }
                        "pair" -> {
                            if (inStylePair && currentStyleMapId != null && currentPairKey == "normal" && currentPairUrl.isNotBlank()) {
                                styleMapPrimary[currentStyleMapId!!] = currentPairUrl.removePrefix("#")
                            }
                            inStylePair = false
                        }
                        "stylemap" -> {
                            inStyleMap = false
                            currentStyleMapId = null
                        }
                        "point" -> inPoint = false
                        "linestring" -> inLineString = false
                        "polygon" -> inPolygon = false
                        "outerboundaryis" -> inOuterBoundary = false
                        "innerboundaryis" -> inInnerBoundary = false
                        "track" -> {
                            if (inGxTrack && gxTrackPts.size >= 2) {
                                placemarkGeoms.add(GeometryType.LINESTRING to listOf(gxTrackPts.toList()))
                            }
                            inGxTrack = false
                        }
                        "data", "simpledata" -> currentDataKey = null
                        "placemark" -> {
                            if (placemarkGeoms.isNotEmpty()) {
                                val baseName = currentName.ifBlank { "Placemark #${features.size + 1}" }
                                val styleId = currentStyleUrl.removePrefix("#").let { rawId ->
                                    styleMapPrimary[rawId] ?: rawId
                                }
                                val resolvedColor = stylesById[styleId]
                                val folderLabel = folderStack.filter { it.isNotBlank() }.joinToString(" / ")

                                // Group multi-part geometries cleanly: keep LineStrings/Polygons multi-part, emit Points individually so each pin is selectable
                                val groupedByType = placemarkGeoms.groupBy({ it.first }, { it.second })
                                val multiType = groupedByType.size > 1
                                groupedByType.forEach { (geomType, ringGroups) ->
                                    if (geomType == GeometryType.POINT) {
                                        val pointGroups = ringGroups.flatten().flatten()
                                        val multiPoint = pointGroups.size > 1
                                        pointGroups.forEachIndexed { ptIdx, pt ->
                                            allPts.add(pt)
                                            val attrs = LinkedHashMap<String, String>(currentAttrs).apply {
                                                put("Format", sourceFormat.label)
                                                if (folderLabel.isNotBlank()) put("Folder", folderLabel)
                                                if (!currentTimestamp.isNullOrBlank()) put("Waktu", currentTimestamp!!)
                                            }
                                            val suffix = when {
                                                multiType && multiPoint -> " (${geomType.label} ${ptIdx + 1})"
                                                multiType -> " (${geomType.label})"
                                                multiPoint -> " (${ptIdx + 1})"
                                                else -> ""
                                            }
                                            val finalName = "$baseName$suffix"
                                            features.add(
                                                VectorMapFeature(
                                                    name = finalName,
                                                    description = currentDesc,
                                                    geometryType = GeometryType.POINT,
                                                    category = inferCategory(finalName, "$currentDesc $folderLabel", attrs, GeometryType.POINT),
                                                    coordinateRings = listOf(listOf(pt)),
                                                    attributes = attrs,
                                                    styleColorHex = resolvedColor,
                                                    elevationMeters = pt.elevation,
                                                    timestampIso = currentTimestamp
                                                )
                                            )
                                        }
                                    } else {
                                        val mergedRings = ringGroups.flatten()
                                        val flatPts = mergedRings.flatten()
                                        allPts.addAll(flatPts)

                                        val attrs = LinkedHashMap<String, String>(currentAttrs).apply {
                                            put("Format", sourceFormat.label)
                                            if (folderLabel.isNotBlank()) put("Folder", folderLabel)
                                            if (geomType == GeometryType.LINESTRING) {
                                                val len = mergedRings.sumOf { GeoMath.polylineLengthMeters(it) }
                                                put("Panjang", GeoMath.formatDistance(len, DistanceUnit.METRIC))
                                            } else if (geomType == GeometryType.POLYGON && mergedRings.isNotEmpty()) {
                                                val area = GeoMath.polygonAreaSquareMeters(mergedRings.first())
                                                put("Luas", GeoMath.formatArea(area, DistanceUnit.METRIC))
                                            }
                                            if (!currentTimestamp.isNullOrBlank()) put("Waktu", currentTimestamp!!)
                                        }

                                        val finalName = if (multiType) "$baseName (${geomType.label})" else baseName
                                        features.add(
                                            VectorMapFeature(
                                                name = finalName,
                                                description = currentDesc,
                                                geometryType = geomType,
                                                category = inferCategory(finalName, "$currentDesc $folderLabel", attrs, geomType),
                                                coordinateRings = mergedRings,
                                                attributes = attrs,
                                                styleColorHex = resolvedColor,
                                                elevationMeters = flatPts.firstOrNull()?.elevation,
                                                timestampIso = currentTimestamp
                                            )
                                        )
                                    }
                                }
                            }
                            inPlacemark = false
                        }
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }

        if (features.isEmpty()) {
            throw IllegalArgumentException("File KML tidak berisi Placemark dengan koordinat yang valid.")
        }

        val dominantColor = features.firstNotNullOfOrNull { it.styleColorHex }
        return VectorMapDocument(
            title = docName?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.'),
            description = docDesc,
            format = sourceFormat,
            bounds = GeoMath.computeBounds(allPts),
            features = features,
            preferredColorHex = dominantColor
        )
    }

    private fun parseKmlCoordinates(raw: String): List<LatLon> {
        val tokens = raw.trim().split(Regex("\\s+"))
        val pts = mutableListOf<LatLon>()
        for (token in tokens) {
            val parts = token.split(",")
            if (parts.size >= 2) {
                val lon = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                val ele = parts.getOrNull(2)?.toDoubleOrNull()
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    pts.add(LatLon(lat, lon, ele))
                }
            }
        }
        return pts
    }

    private fun parseGxCoord(raw: String): LatLon? {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val lon = parts[0].toDoubleOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        val ele = parts.getOrNull(2)?.toDoubleOrNull()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon, ele) else null
    }

    /**
     * Converts KML ABGR hex (e.g. `ff00aa55`) into standard `#RRGGBB`.
     */
    private fun parseKmlAbgrToHex(kmlColor: String): String? {
        val clean = kmlColor.trim().removePrefix("#")
        if (clean.length != 8) return null
        val bb = clean.substring(2, 4)
        val gg = clean.substring(4, 6)
        val rr = clean.substring(6, 8)
        return runCatching {
            val r = rr.toInt(16)
            val g = gg.toInt(16)
            val b = bb.toInt(16)
            String.format(Locale.US, "#%02X%02X%02X", r, g, b)
        }.getOrNull()
    }

    private fun inferCategory(
        name: String,
        desc: String,
        attrs: Map<String, String>,
        geometryType: GeometryType = GeometryType.POINT
    ): SurveyCategory {
        val combined = "$name $desc ${attrs.values.joinToString(" ")}".lowercase(Locale.US)
        return when {
            combined.containsAny("pohon", "hutan", "vegetasi", "tree", "flora", "tanaman") -> SurveyCategory.VEGETASI
            combined.containsAny("batas", "patok", "blok", "bm", "border", "parcel", "lahan", "polygon") -> SurveyCategory.BATAS
            combined.containsAny("air", "sungai", "mata air", "river", "water", "danau", "hulu", "stream") -> SurveyCategory.AIR
            combined.containsAny("jalan", "jembatan", "pos", "kampung", "road", "trail", "track", "rute", "route") -> SurveyCategory.INFRASTRUKTUR
            combined.containsAny("longsor", "bahaya", "rawan", "hazard", "danger", "banjir") -> SurveyCategory.BAHAYA
            combined.containsAny("tanah", "mineral", "batu", "soil", "sampel", "sample") -> SurveyCategory.SAMPEL
            geometryType == GeometryType.LINESTRING -> SurveyCategory.INFRASTRUKTUR
            geometryType == GeometryType.POLYGON -> SurveyCategory.BATAS
            else -> SurveyCategory.UMUM
        }
    }

    private fun String.containsAny(vararg words: String): Boolean = words.any { this.contains(it) }

    companion object {
        val Default = GpxKmlParserService()
    }
}
