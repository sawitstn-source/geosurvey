package com.example.spatial

import com.example.data.local.GeometryType
import com.example.data.local.LatLon
import com.example.data.local.LayerSourceType
import com.example.data.local.SpatialDao
import com.example.data.local.SpatialFeatureEntity
import com.example.data.local.SpatialLayerEntity
import com.example.data.local.SurveyCategory

object SampleSurveySeeder {
    const val DEMO_RASTER_RES_NAME = "img_topo_banner_1794571470608"

    suspend fun seedIfNeeded(dao: SpatialDao, alreadySeeded: Boolean, markSeeded: suspend () -> Unit) {
        if (alreadySeeded) return

        // 1. Editable active survey layer for field data collection
        val activeSurveyLayerId = dao.insertLayer(
            SpatialLayerEntity(
                name = "Survei Lapangan Aktif",
                sourceType = LayerSourceType.SURVEY.name,
                colorHex = "#D97706",
                isVisible = true,
                isEditable = true,
                minLat = -7.558,
                maxLat = -7.532,
                minLon = 110.422,
                maxLon = 110.462
            )
        )

        // 2. Georeferenced offline raster basemap (built-in topographic map sheet)
        dao.insertLayer(
            SpatialLayerEntity(
                name = "Lembar Topografi Lembah Hijau (Offline)",
                sourceType = LayerSourceType.RASTER.name,
                colorHex = "#1B6B52",
                isVisible = true,
                isEditable = false,
                minLat = -7.5620,
                maxLat = -7.5280,
                minLon = 110.4150,
                maxLon = 110.4700,
                drawableResName = DEMO_RASTER_RES_NAME
            )
        )

        // 3. Vector GeoJSON-style survey zones & trails layer
        val vectorLayerId = dao.insertLayer(
            SpatialLayerEntity(
                name = "Batas Blok & Jalur Patroli (Contoh)",
                sourceType = LayerSourceType.GEOJSON.name,
                colorHex = "#1B6B52",
                isVisible = true,
                isEditable = false,
                minLat = -7.556,
                maxLat = -7.534,
                minLon = 110.424,
                maxLon = 110.460
            )
        )

        val blokA = listOf(
            LatLon(-7.5385, 110.4270),
            LatLon(-7.5365, 110.4405),
            LatLon(-7.5450, 110.4430),
            LatLon(-7.5475, 110.4310),
            LatLon(-7.5385, 110.4270)
        )
        val blokB = listOf(
            LatLon(-7.5435, 110.4455),
            LatLon(-7.5405, 110.4570),
            LatLon(-7.5515, 110.4585),
            LatLon(-7.5535, 110.4470),
            LatLon(-7.5435, 110.4455)
        )
        val jalurPatroli = listOf(
            LatLon(-7.5520, 110.4260, 720.0),
            LatLon(-7.5480, 110.4335, 748.0),
            LatLon(-7.5445, 110.4420, 795.0),
            LatLon(-7.5425, 110.4495, 830.0),
            LatLon(-7.5390, 110.4565, 882.0)
        )

        dao.insertFeatures(
            listOf(
                SpatialFeatureEntity(
                    layerId = vectorLayerId,
                    geometryType = GeometryType.POLYGON.name,
                    name = "Blok A - Zona Hutan Lindung",
                    description = "Area tutupan kanopi rapat dengan kontur curam di sisi utara.",
                    category = SurveyCategory.VEGETASI.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(blokA)),
                    attributesJson = GeoMath.attributesToJson(
                        mapOf("Kode Blok" to "HL-01", "Luas Target" to "138 Ha", "Status" to "Terjaga")
                    ),
                    latitude = -7.5418,
                    longitude = 110.4354
                ),
                SpatialFeatureEntity(
                    layerId = vectorLayerId,
                    geometryType = GeometryType.POLYGON.name,
                    name = "Blok B - Agroforestri Masyarakat",
                    description = "Lahan penyangga campuran tanaman keras dan kopi rakyat.",
                    category = SurveyCategory.BATAS.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(blokB)),
                    attributesJson = GeoMath.attributesToJson(
                        mapOf("Kode Blok" to "AF-02", "Komoditas" to "Kopi & Sengon", "Kelompok" to "Tani Makmur")
                    ),
                    latitude = -7.5472,
                    longitude = 110.4520
                ),
                SpatialFeatureEntity(
                    layerId = vectorLayerId,
                    geometryType = GeometryType.LINESTRING.name,
                    name = "Jalur Akses Sungai & Transek Utama",
                    description = "Rute jalan setapak utama tim survei melintasi punggungan tengah.",
                    category = SurveyCategory.INFRASTRUKTUR.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(jalurPatroli)),
                    attributesJson = GeoMath.attributesToJson(
                        mapOf("Lebar Jalur" to "2.5 m", "Kondisi" to "Bisa dilalui motor trail")
                    ),
                    latitude = -7.5445,
                    longitude = 110.4420
                )
            )
        )

        // 4. Sample GPX track & waypoint file parsed via GpxKmlParserService
        val sampleGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="GeoSurvei">
              <metadata>
                <name>Patroli_Hulu_Sungai.gpx</name>
              </metadata>
              <wpt lat="-7.5398" lon="110.4335">
                <ele>826.0</ele>
                <name>Check Point Hulu-1</name>
                <desc>Titik pantau debit hulu sungai</desc>
              </wpt>
              <trk>
                <name>Jejak Patroli GPX</name>
                <trkseg>
                  <trkpt lat="-7.5378" lon="110.4285"><ele>810.0</ele></trkpt>
                  <trkpt lat="-7.5398" lon="110.4335"><ele>826.0</ele></trkpt>
                  <trkpt lat="-7.5426" lon="110.4388"><ele>804.0</ele></trkpt>
                  <trkpt lat="-7.5448" lon="110.4452"><ele>789.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val parsedGpx = GpxKmlParserService.Default.parseGpx("Patroli_Hulu_Sungai.gpx", sampleGpx)
        val gpxLayerId = dao.insertLayer(
            SpatialLayerEntity(
                name = parsedGpx.title,
                sourceType = LayerSourceType.GPX.name,
                colorHex = "#2563EB",
                isVisible = true,
                isEditable = false,
                minLat = parsedGpx.bounds?.minLat ?: -7.55,
                maxLat = parsedGpx.bounds?.maxLat ?: -7.53,
                minLon = parsedGpx.bounds?.minLon ?: 110.42,
                maxLon = parsedGpx.bounds?.maxLon ?: 110.45
            )
        )
        dao.insertFeatures(parsedGpx.features.map { it.toSpatialFeatureEntity(gpxLayerId) })

        // 5. Sample KML conservation zone & placemarks parsed via GpxKmlParserService
        val sampleKml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>Zona_Konservasi_Air.kml</name>
                <Style id="kmlZoneStyle">
                  <LineStyle><color>ff9333ea</color></LineStyle>
                </Style>
                <Placemark>
                  <name>Zona Penyangga Mata Air</name>
                  <styleUrl>#kmlZoneStyle</styleUrl>
                  <Polygon>
                    <outerBoundaryIs>
                      <LinearRing>
                        <coordinates>110.4405,-7.5390,0 110.4468,-7.5390,0 110.4468,-7.5435,0 110.4405,-7.5435,0 110.4405,-7.5390,0</coordinates>
                      </LinearRing>
                    </outerBoundaryIs>
                  </Polygon>
                </Placemark>
                <Placemark>
                  <name>Stasiun Curah Hujan KML</name>
                  <styleUrl>#kmlZoneStyle</styleUrl>
                  <Point><coordinates>110.4442,-7.5408,815</coordinates></Point>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()
        val parsedKml = GpxKmlParserService.Default.parseKml("Zona_Konservasi_Air.kml", sampleKml)
        val kmlLayerId = dao.insertLayer(
            SpatialLayerEntity(
                name = parsedKml.title,
                sourceType = LayerSourceType.KML.name,
                colorHex = parsedKml.preferredColorHex ?: "#7C3AED",
                isVisible = true,
                isEditable = false,
                minLat = parsedKml.bounds?.minLat ?: -7.55,
                maxLat = parsedKml.bounds?.maxLat ?: -7.53,
                minLon = parsedKml.bounds?.minLon ?: 110.43,
                maxLon = parsedKml.bounds?.maxLon ?: 110.46
            )
        )
        dao.insertFeatures(parsedKml.features.map { it.toSpatialFeatureEntity(kmlLayerId) })

        // 6. Sample waypoints in the editable survey layer
        dao.insertFeatures(
            listOf(
                SpatialFeatureEntity(
                    layerId = activeSurveyLayerId,
                    geometryType = GeometryType.POINT.name,
                    name = "BM-01 Patok Referensi Utama",
                    description = "Titik ikat beton permanen di simpang jalur utama dan pos pantau.",
                    category = SurveyCategory.BATAS.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(listOf(LatLon(-7.5462, 110.4368, 764.0)))),
                    attributesJson = GeoMath.attributesToJson(
                        mapOf("Material" to "Beton 20x20cm", "Surveyor" to "Tim A", "Kondisi" to "Baik")
                    ),
                    latitude = -7.5462,
                    longitude = 110.4368,
                    elevationMeters = 764.0,
                    accuracyMeters = 2.4f
                ),
                SpatialFeatureEntity(
                    layerId = activeSurveyLayerId,
                    geometryType = GeometryType.POINT.name,
                    name = "Mata Air Sendang Hulu",
                    description = "Debit stabil sepanjang musim kemarau, pH air jernih 7.1.",
                    category = SurveyCategory.AIR.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(listOf(LatLon(-7.5415, 110.4432, 808.0)))),
                    attributesJson = GeoMath.attributesToJson(
                        mapOf("Debit" to "4.2 L/detik", "pH" to "7.1", "Akses" to "Jalan setapak 150m")
                    ),
                    latitude = -7.5415,
                    longitude = 110.4432,
                    elevationMeters = 808.0,
                    accuracyMeters = 3.1f
                ),
                SpatialFeatureEntity(
                    layerId = activeSurveyLayerId,
                    geometryType = GeometryType.POINT.name,
                    name = "Titik Rawan Longsor Tebing Timur",
                    description = "Terdapat retakan tanah selebar 30 cm di lereng 42 derajat.",
                    category = SurveyCategory.BAHAYA.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(listOf(LatLon(-7.5495, 110.4535, 792.0)))),
                    attributesJson = GeoMath.attributesToJson(
                        mapOf("Kemiringan" to "42°", "Prioritas" to "Tinggi", "Mitigasi" to "Pasang patok pantau")
                    ),
                    latitude = -7.5495,
                    longitude = 110.4535,
                    elevationMeters = 792.0,
                    accuracyMeters = 2.9f
                )
            )
        )

        markSeeded()
    }
}
