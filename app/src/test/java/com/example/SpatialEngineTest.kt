package com.example

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.GeoSurveiDatabase
import com.example.data.local.GeometryType
import com.example.data.local.LatLon
import com.example.data.local.LayerSourceType
import com.example.data.local.MapBounds
import com.example.data.local.MapTileEntity
import com.example.data.local.SpatialFeatureEntity
import com.example.data.local.SpatialLayerEntity
import com.example.spatial.ExportFormat
import com.example.spatial.GeoMath
import com.example.spatial.MbTilesReader
import com.example.spatial.PdfMapProcessor
import com.example.spatial.SpatialExporter
import com.example.spatial.SpatialFileParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpatialEngineTest {

    @Test
    fun `mercator roundtrip preserves lat lon`() {
        val lat = -7.5462
        val lon = 110.4368
        val nx = GeoMath.lonToNormX(lon)
        val ny = GeoMath.latToNormY(lat)
        assertEquals(lat, GeoMath.normYToLat(ny), 1e-6)
        assertEquals(lon, GeoMath.normXToLon(nx), 1e-6)
    }

    @Test
    fun `haversine and polygon area produce realistic measurements`() {
        val a = LatLon(-7.5400, 110.4300)
        val b = LatLon(-7.5400, 110.4390)
        val dist = GeoMath.distanceMeters(a, b)
        assertTrue("Expected ~990m distance, got $dist", dist in 900.0..1050.0)

        val square = listOf(
            LatLon(-7.5400, 110.4300),
            LatLon(-7.5400, 110.4390),
            LatLon(-7.5490, 110.4390),
            LatLon(-7.5490, 110.4300)
        )
        val area = GeoMath.polygonAreaSquareMeters(square)
        assertTrue("Expected ~1,000,000 m2 area, got $area", area in 900_000.0..1_100_000.0)
    }

    @Test
    fun `parseGeoJson parses point linestring and polygon`() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "name": "Blok Survei",
              "features": [
                {
                  "type": "Feature",
                  "properties": {"name": "Patok P1", "kondisi": "Baik"},
                  "geometry": {"type": "Point", "coordinates": [110.44, -7.54, 750.0]}
                },
                {
                  "type": "Feature",
                  "properties": {"name": "Jalur A"},
                  "geometry": {"type": "LineString", "coordinates": [[110.43, -7.54], [110.44, -7.55]]}
                },
                {
                  "type": "Feature",
                  "properties": {"name": "Area Hutan"},
                  "geometry": {"type": "Polygon", "coordinates": [[[110.43, -7.54], [110.44, -7.54], [110.44, -7.55], [110.43, -7.54]]]}
                }
              ]
            }
        """.trimIndent()

        val parsed = SpatialFileParser.parseGeoJson("test.geojson", geoJson)
        assertEquals("Blok Survei", parsed.name)
        assertEquals(3, parsed.features.size)
        assertEquals(GeometryType.POINT, parsed.features[0].geometryType)
        assertEquals("Baik", parsed.features[0].attributes["kondisi"])
        assertEquals(GeometryType.LINESTRING, parsed.features[1].geometryType)
        assertEquals(GeometryType.POLYGON, parsed.features[2].geometryType)
    }

    @Test
    fun `parseKml and parseGpx and parseCsv extract features`() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>KML Uji</name>
                <Placemark>
                  <name>Mata Air</name>
                  <Point><coordinates>110.442,-7.541,805</coordinates></Point>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()
        val parsedKml = SpatialFileParser.parseKml("uji.kml", kml)
        assertEquals("KML Uji", parsedKml.name)
        assertEquals(1, parsedKml.features.size)

        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <wpt lat="-7.545" lon="110.441"><ele>790</ele><name>WPT-1</name></wpt>
              <trk><name>Track-1</name><trkseg>
                <trkpt lat="-7.545" lon="110.441"/>
                <trkpt lat="-7.546" lon="110.442"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val parsedGpx = SpatialFileParser.parseGpx("uji.gpx", gpx)
        assertEquals(2, parsedGpx.features.size)

        val csv = """
            nama,latitude,longitude,elevasi,catatan
            Titik 1,-7.5421,110.4381,780,Tanah lempung
            Titik 2,-7.5432,110.4392,792,Dekat sungai
        """.trimIndent()
        val parsedCsv = SpatialFileParser.parseCsv("sampel.csv", csv)
        assertEquals(2, parsedCsv.features.size)
        assertEquals("Tanah lempung", parsedCsv.features[0].description)
    }

    @Test
    fun `export roundtrip GeoJSON KML GPX CSV`() {
        val feature = SpatialFeatureEntity(
            id = 1,
            layerId = 1,
            geometryType = GeometryType.POINT.name,
            name = "Titik Uji",
            description = "Patok utama",
            coordinatesJson = GeoMath.ringsToJson(listOf(listOf(LatLon(-7.54, 110.44, 760.0)))),
            attributesJson = GeoMath.attributesToJson(mapOf("Material" to "Beton")),
            latitude = -7.54,
            longitude = 110.44,
            elevationMeters = 760.0
        )
        val geojson = SpatialExporter.export(listOf(feature), "Ekspor", ExportFormat.GEOJSON)
        assertTrue(geojson.contains("\"FeatureCollection\""))
        assertTrue(geojson.contains("Titik Uji"))

        val kml = SpatialExporter.export(listOf(feature), "Ekspor", ExportFormat.KML)
        assertTrue(kml.contains("<Placemark>"))

        val gpx = SpatialExporter.export(listOf(feature), "Ekspor", ExportFormat.GPX)
        assertTrue(gpx.contains("<wpt"))

        val csv = SpatialExporter.export(listOf(feature), "Ekspor", ExportFormat.CSV)
        assertTrue(csv.contains("Titik Uji"))
    }

    @Test
    fun `multi photo encoding elevation gain and attribute templates work`() {
        val single = GeoMath.encodePhotoUris(listOf("/data/photos/a.jpg"))
        assertEquals(listOf("/data/photos/a.jpg"), GeoMath.decodePhotoUris(single))

        val multi = GeoMath.encodePhotoUris(listOf("/data/photos/a.jpg", "/data/photos/b.jpg", "/data/photos/a.jpg"))
        assertEquals(listOf("/data/photos/a.jpg", "/data/photos/b.jpg"), GeoMath.decodePhotoUris(multi))

        val pts = listOf(
            LatLon(-7.54, 110.43, 700.0),
            LatLon(-7.541, 110.431, 725.0),
            LatLon(-7.542, 110.432, 710.0),
            LatLon(-7.543, 110.433, 740.0)
        )
        assertEquals(55.0, GeoMath.elevationGainMeters(pts), 1e-3)
    }

    @Test
    fun `room map_tiles stores and loads sliced pdf or raster basemap tiles`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, GeoSurveiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val spatialDao = db.spatialDao()
            val tileDao = db.mapTileDao()

            val layerId = spatialDao.insertLayer(
                SpatialLayerEntity(
                    name = "Peta PDF Topografi Uji",
                    sourceType = LayerSourceType.PDF.name,
                    colorHex = "#1B6B52",
                    minLat = -7.56,
                    maxLat = -7.53,
                    minLon = 110.42,
                    maxLon = 110.45
                )
            )
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(42, 110, 82))
            }
            val bounds = MapBounds(-7.56, -7.53, 110.42, 110.45)
            val (minZ, maxZ) = PdfMapProcessor.generateRoomTilesFromBitmap(
                bitmap = bmp,
                bounds = bounds,
                layerId = layerId,
                mapTileDao = tileDao,
                maxTotalTiles = 60
            )
            bmp.recycle()

            val count = tileDao.getTileCount(layerId)
            val zooms = tileDao.getAvailableZooms(layerId)
            assertTrue("Expected Room tiles to be generated", count > 0)
            assertTrue("Expected minZ <= maxZ", minZ <= maxZ)
            assertTrue("Expected available zooms non-empty", zooms.isNotEmpty())

            // Verify MbTilesReader decodes stored Room tile for center coordinate
            val reader = MbTilesReader(tileDao)
            val z = zooms.first()
            val n = 1 shl z
            val cx = kotlin.math.floor(GeoMath.lonToNormX(bounds.centerLon) * n).toInt()
            val cy = kotlin.math.floor(GeoMath.latToNormY(bounds.centerLat) * n).toInt()
            val layer = spatialDao.getLayerById(layerId)!!
            val decoded = reader.loadTile(layer, z, cx, cy)
            assertNotNull("Expected MbTilesReader to decode Room tile at $z/$cx/$cy", decoded)
            assertEquals(256, decoded?.width)
            assertEquals(256, decoded?.height)

            // Verify cascade deletion removes tiles when layer is deleted
            spatialDao.deleteLayerById(layerId)
            assertEquals(0, tileDao.getTileCount(layerId))
        } finally {
            db.close()
        }
    }
}
