package com.example.spatial

import com.example.data.local.CoordFormat
import com.example.data.local.DistanceUnit
import com.example.data.local.LatLon
import com.example.data.local.MapBounds
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

object GeoMath {
    private const val EARTH_RADIUS_M = 6371008.8
    private const val MAX_MERCATOR_LAT = 85.05112878

    /**
     * Converts longitude [-180, 180] to normalized Web Mercator X [0, 1].
     */
    fun lonToNormX(lon: Double): Double {
        return (lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0
    }

    /**
     * Converts latitude [-85.0511, 85.0511] to normalized Web Mercator Y [0, 1] (top = 0).
     */
    fun latToNormY(lat: Double): Double {
        val clamped = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val rad = Math.toRadians(clamped)
        val mercN = ln(tan(Math.PI / 4.0 + rad / 2.0))
        return 0.5 - (mercN / (2.0 * Math.PI))
    }

    fun normXToLon(normX: Double): Double {
        return (normX * 360.0 - 180.0).coerceIn(-180.0, 180.0)
    }

    fun normYToLat(normY: Double): Double {
        val y = normY.coerceIn(0.00001, 0.99999)
        val n = Math.PI * (1.0 - 2.0 * y)
        return Math.toDegrees(atan(sinh(n))).coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
    }

    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun polylineLengthMeters(points: List<LatLon>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += distanceMeters(points[i], points[i + 1])
        }
        return total
    }

    /**
     * Spherical polygon area in square meters (Chamberlain & Duquette geodesic formula).
     */
    fun polygonAreaSquareMeters(ring: List<LatLon>): Double {
        if (ring.size < 3) return 0.0
        val pts = if (ring.first().latitude == ring.last().latitude &&
            ring.first().longitude == ring.last().longitude
        ) {
            ring.dropLast(1)
        } else {
            ring
        }
        if (pts.size < 3) return 0.0
        var total = 0.0
        val n = pts.size
        for (i in 0 until n) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            val lon1 = Math.toRadians(p1.longitude)
            val lon2 = Math.toRadians(p2.longitude)
            val lat1 = Math.toRadians(p1.latitude)
            val lat2 = Math.toRadians(p2.latitude)
            total += (lon2 - lon1) * (2.0 + sin(lat1) + sin(lat2))
        }
        return abs(total * EARTH_RADIUS_M * EARTH_RADIUS_M / 2.0)
    }

    fun bearingDegrees(from: LatLon, to: LatLon): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun cardinalDirectionId(bearingDeg: Double): String {
        val norm = ((bearingDeg % 360.0) + 360.0) % 360.0
        return when {
            norm < 22.5 || norm >= 337.5 -> "U"
            norm < 67.5 -> "TL"
            norm < 112.5 -> "T"
            norm < 157.5 -> "TG"
            norm < 202.5 -> "S"
            norm < 247.5 -> "BD"
            norm < 292.5 -> "B"
            else -> "BL"
        }
    }

    fun formatCoordinate(lat: Double, lon: Double, format: CoordFormat): String {
        return when (format) {
            CoordFormat.DD -> String.format(Locale.US, "%.6f°, %.6f°", lat, lon)
            CoordFormat.DMS -> "${toDms(lat, isLat = true)}  ${toDms(lon, isLat = false)}"
            CoordFormat.UTM -> toUtmString(lat, lon)
        }
    }

    private fun toDms(value: Double, isLat: Boolean): String {
        val hemi = if (isLat) {
            if (value >= 0) "U" else "S"
        } else {
            if (value >= 0) "T" else "B"
        }
        val absVal = abs(value)
        val deg = absVal.toInt()
        val minFull = (absVal - deg) * 60.0
        val min = minFull.toInt()
        val sec = (minFull - min) * 60.0
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", deg, min, sec, hemi)
    }

    fun toUtmString(lat: Double, lon: Double): String {
        val safeLat = lat.coerceIn(-80.0, 84.0)
        val zone = (((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        val band = utmBandLetter(safeLat)

        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2.0 - f)
        val ep2 = e2 / (1.0 - e2)

        val latRad = Math.toRadians(safeLat)
        val lonRad = Math.toRadians(lon)
        val lonOrigin = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)

        val n = a / sqrt(1.0 - e2 * sin(latRad).pow(2))
        val t = tan(latRad).pow(2)
        val c = ep2 * cos(latRad).pow(2)
        val aCoeff = cos(latRad) * (lonRad - lonOrigin)

        val m = a * (
            (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2.pow(3) / 256.0) * latRad -
                (3.0 * e2 / 8.0 + 3.0 * e2 * e2 / 32.0 + 45.0 * e2.pow(3) / 1024.0) * sin(2.0 * latRad) +
                (15.0 * e2 * e2 / 256.0 + 45.0 * e2.pow(3) / 1024.0) * sin(4.0 * latRad) -
                (35.0 * e2.pow(3) / 3072.0) * sin(6.0 * latRad)
            )

        val easting = k0 * n * (
            aCoeff + (1.0 - t + c) * aCoeff.pow(3) / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * aCoeff.pow(5) / 120.0
            ) + 500000.0

        var northing = k0 * (
            m + n * tan(latRad) * (
                aCoeff * aCoeff / 2.0 +
                    (5.0 - t + 9.0 * c + 4.0 * c * c) * aCoeff.pow(4) / 24.0 +
                    (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * aCoeff.pow(6) / 720.0
                )
            )
        if (safeLat < 0) northing += 10000000.0

        return String.format(Locale.US, "%d%c %.0fE %.0fN", zone, band, easting, northing)
    }

    private fun utmBandLetter(lat: Double): Char {
        val letters = "CDEFGHJKLMNPQRSTUVWX"
        val index = (((lat + 80.0) / 8.0).toInt()).coerceIn(0, letters.lastIndex)
        return letters[index]
    }

    fun formatDistance(meters: Double, unit: DistanceUnit): String {
        return when (unit) {
            DistanceUnit.METRIC -> {
                if (meters < 1000.0) {
                    String.format(Locale.US, "%.1f m", meters)
                } else {
                    String.format(Locale.US, "%.2f km", meters / 1000.0)
                }
            }
            DistanceUnit.IMPERIAL -> {
                val feet = meters * 3.28084
                if (feet < 1000.0) {
                    String.format(Locale.US, "%.0f ft", feet)
                } else {
                    String.format(Locale.US, "%.2f mi", meters / 1609.344)
                }
            }
        }
    }

    fun formatArea(sqMeters: Double, unit: DistanceUnit): String {
        return when (unit) {
            DistanceUnit.METRIC -> {
                when {
                    sqMeters < 10_000.0 -> String.format(Locale.US, "%.1f m²", sqMeters)
                    sqMeters < 1_000_000.0 -> String.format(Locale.US, "%.2f ha", sqMeters / 10_000.0)
                    else -> String.format(Locale.US, "%.2f km²", sqMeters / 1_000_000.0)
                }
            }
            DistanceUnit.IMPERIAL -> {
                val sqFeet = sqMeters * 10.7639
                if (sqFeet < 43560.0) {
                    String.format(Locale.US, "%.0f ft²", sqFeet)
                } else {
                    String.format(Locale.US, "%.2f ac", sqMeters / 4046.8564)
                }
            }
        }
    }

    fun computeBounds(points: List<LatLon>): MapBounds? {
        if (points.isEmpty()) return null
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        return MapBounds(minLat, maxLat, minLon, maxLon)
    }

    fun ringsToJson(rings: List<List<LatLon>>): String {
        val outer = JSONArray()
        for (ring in rings) {
            val ringArr = JSONArray()
            for (pt in ring) {
                val ptArr = JSONArray()
                ptArr.put(pt.longitude)
                ptArr.put(pt.latitude)
                if (pt.elevation != null) ptArr.put(pt.elevation)
                ringArr.put(ptArr)
            }
            outer.put(ringArr)
        }
        return outer.toString()
    }

    fun jsonToRings(json: String): List<List<LatLon>> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val outer = JSONArray(json)
            val result = ArrayList<List<LatLon>>(outer.length())
            for (i in 0 until outer.length()) {
                val ringArr = outer.optJSONArray(i) ?: continue
                val pts = ArrayList<LatLon>(ringArr.length())
                for (j in 0 until ringArr.length()) {
                    val ptArr = ringArr.optJSONArray(j) ?: continue
                    if (ptArr.length() >= 2) {
                        val lon = ptArr.optDouble(0)
                        val lat = ptArr.optDouble(1)
                        val ele = if (ptArr.length() >= 3 && !ptArr.isNull(2)) ptArr.optDouble(2) else null
                        if (lat.isFinite() && lon.isFinite()) {
                            pts.add(LatLon(lat, lon, ele))
                        }
                    }
                }
                if (pts.isNotEmpty()) result.add(pts)
            }
            result
        }.getOrDefault(emptyList())
    }

    fun attributesToJson(attributes: Map<String, String>): String {
        val obj = JSONObject()
        attributes.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    fun jsonToAttributes(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val map = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optString(key, "")
            }
            map
        }.getOrDefault(emptyMap())
    }

    fun encodePhotoUris(uris: List<String>): String? {
        val clean = uris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when {
            clean.isEmpty() -> null
            clean.size == 1 -> clean.first()
            else -> JSONArray(clean).toString()
        }
    }

    fun decodePhotoUris(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        return if (trimmed.startsWith("[")) {
            runCatching {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotEmpty()) add(s)
                    }
                }
            }.getOrDefault(emptyList())
        } else {
            listOf(trimmed)
        }
    }

    fun elevationGainMeters(points: List<LatLon>): Double {
        val eles = points.mapNotNull { it.elevation }
        if (eles.size < 2) return 0.0
        var gain = 0.0
        for (i in 1 until eles.size) {
            val delta = eles[i] - eles[i - 1]
            if (delta > 0.0) gain += delta
        }
        return gain
    }
}

