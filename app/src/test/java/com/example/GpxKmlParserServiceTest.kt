package com.example.spatial

import com.example.data.local.GeometryType
import com.example.data.local.LayerSourceType
import com.example.data.local.SurveyCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GpxKmlParserServiceTest {

    private val service = GpxKmlParserService()

    @Test
    fun `parses rich KML with style folder multigeometry gxTrack and polygon boundaries into common structure`() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
              <Document>
                <name>Peta Survei Merapi</name>
                <description>Data survei lapangan</description>
                <Style id="trailStyle">
                  <LineStyle><color>ff2b77d9</color><width>3</width></LineStyle>
                </Style>
                <Folder>
                  <name>Zona Konservasi</name>
                  <Placemark>
                    <name>Mata Air Kalikuning</name>
                    <description><![CDATA[<b>Debit stabil</b> sepanjang musim]]></description>
                    <ExtendedData>
                      <Data name="pH"><value>7.2</value></Data>
                    </ExtendedData>
                    <Point><coordinates>110.442,-7.541,820</coordinates></Point>
                  </Placemark>
                  <Placemark>
                    <name>Blok Hutan A</name>
                    <Polygon>
                      <outerBoundaryIs>
                        <LinearRing>
                          <coordinates>110.43,-7.54,0 110.44,-7.54,0 110.44,-7.55,0 110.43,-7.55,0 110.43,-7.54,0</coordinates>
                        </LinearRing>
                      </outerBoundaryIs>
                      <innerBoundaryIs>
                        <LinearRing>
                          <coordinates>110.433,-7.543,0 110.436,-7.543,0 110.436,-7.546,0 110.433,-7.543,0</coordinates>
                        </LinearRing>
                      </innerBoundaryIs>
                    </Polygon>
                  </Placemark>
                  <Placemark>
                    <name>Jalur Transek</name>
                    <styleUrl>#trailStyle</styleUrl>
                    <gx:Track>
                      <when>2026-11-12T08:00:00Z</when>
                      <gx:coord>110.435 -7.542 790</gx:coord>
                      <gx:coord>110.438 -7.545 810</gx:coord>
                    </gx:Track>
                  </Placemark>
                </Folder>
              </Document>
            </kml>
        """.trimIndent()

        val doc = service.parse("merapi.kml", ByteArrayInputStream(kml.toByteArray()))
        assertEquals("Peta Survei Merapi", doc.title)
        assertEquals(VectorFormat.KML, doc.format)
        assertEquals("#D9772B", doc.preferredColorHex)
        assertEquals(3, doc.features.size)
        assertEquals(1, doc.pointCount)
        assertEquals(1, doc.polygonCount)
        assertEquals(1, doc.lineCount)
        assertNotNull(doc.bounds)

        val point = doc.features[0]
        assertEquals("Mata Air Kalikuning", point.name)
        assertEquals(SurveyCategory.AIR, point.category)
        assertEquals("7.2", point.attributes["pH"])
        assertEquals("Zona Konservasi", point.attributes["Folder"])
        assertEquals(820.0, point.elevationMeters ?: 0.0, 0.01)

        val polygon = doc.features[1]
        assertEquals(GeometryType.POLYGON, polygon.geometryType)
        assertEquals(1, polygon.coordinateRings.size) // innerBoundaryIs did not duplicate polygon

        val track = doc.features[2]
        assertEquals(GeometryType.LINESTRING, track.geometryType)
        assertEquals("#D9772B", track.styleColorHex)
        assertEquals("2026-11-12T08:00:00Z", track.timestampIso)

        // Verify conversion to common map rendering structure
        val layerData = doc.toParsedLayerData()
        assertEquals(LayerSourceType.KML, layerData.sourceType)
        assertEquals("#D9772B", layerData.preferredColorHex)
        assertEquals(3, layerData.features.size)

        val entity = track.toSpatialFeatureEntity(layerId = 7L)
        assertEquals(7L, entity.layerId)
        assertEquals(GeometryType.LINESTRING.name, entity.geometryType)
        assertTrue(GeoMath.jsonToRings(entity.coordinatesJson).first().size == 2)
    }

    @Test
    fun `parses GPX metadata waypoints multi-segment tracks and routes into common structure`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="FieldGPS">
              <metadata>
                <name>Survei Patroli Timur</name>
                <desc>Rekaman GPX lapangan</desc>
              </metadata>
              <wpt lat="-7.5415" lon="110.4392">
                <ele>845.5</ele>
                <time>2026-11-12T06:30:00Z</time>
                <name>Patok BM-01</name>
                <sym>Flag, Blue</sym>
                <type>Batas</type>
              </wpt>
              <trk>
                <name>Jalur Patroli</name>
                <trkseg>
                  <trkpt lat="-7.5415" lon="110.4392"><ele>845.0</ele></trkpt>
                  <trkpt lat="-7.5425" lon="110.4402"><ele>860.0</ele></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="-7.5430" lon="110.4410"><ele>870.0</ele></trkpt>
                  <trkpt lat="-7.5440" lon="110.4420"><ele>890.0</ele></trkpt>
                </trkseg>
              </trk>
              <rte>
                <name>Rute Evakuasi</name>
                <rtept lat="-7.5440" lon="110.4420"/>
                <rtept lat="-7.5460" lon="110.4440"/>
              </rte>
            </gpx>
        """.trimIndent()

        val doc = service.parse("patroli.gpx", ByteArrayInputStream(gpx.toByteArray()))
        assertEquals("Survei Patroli Timur", doc.title)
        assertEquals(VectorFormat.GPX, doc.format)
        assertEquals(3, doc.features.size)
        assertEquals(1, doc.pointCount)
        assertEquals(2, doc.lineCount)

        val wpt = doc.features[0]
        assertEquals("Patok BM-01", wpt.name)
        assertEquals(SurveyCategory.BATAS, wpt.category)
        assertEquals("Flag, Blue", wpt.attributes["Simbol"])
        assertEquals(845.5, wpt.elevationMeters ?: 0.0, 0.01)

        val trk = doc.features[1]
        assertEquals(2, trk.coordinateRings.size)
        assertEquals("2", trk.attributes["Segmen"])
        assertEquals("4", trk.attributes["Jumlah Titik"])
        assertTrue(trk.attributes.containsKey("Tanjakan (+)"))

        val layerData = doc.toParsedLayerData()
        assertEquals(LayerSourceType.GPX, layerData.sourceType)
        assertEquals(3, layerData.features.size)
    }

    @Test
    fun `parses KMZ archive containing doc kml`() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>Arsip KMZ</name>
                <Placemark>
                  <name>Pos Lapangan</name>
                  <Point><coordinates>110.445,-7.548,710</coordinates></Point>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("doc.kml"))
            zos.write(kml.toByteArray())
            zos.closeEntry()
        }

        val doc = service.parse("lapangan.kmz", ByteArrayInputStream(baos.toByteArray()))
        assertEquals("Arsip KMZ", doc.title)
        assertEquals(VectorFormat.KMZ, doc.format)
        assertEquals(1, doc.features.size)
        assertEquals(LayerSourceType.KML, doc.toParsedLayerData().sourceType)
    }

    @Test
    fun `parses KML MultiGeometry with multiple points and lines into distinct features`() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <name>Kompleks Pos</name>
                  <MultiGeometry>
                    <Point><coordinates>110.440,-7.540,700</coordinates></Point>
                    <Point><coordinates>110.441,-7.541,705</coordinates></Point>
                    <LineString><coordinates>110.440,-7.540,700 110.441,-7.541,705</coordinates></LineString>
                  </MultiGeometry>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val doc = service.parse("multi.kml", ByteArrayInputStream(kml.toByteArray()))
        assertEquals(2, doc.pointCount)
        assertEquals(1, doc.lineCount)
        assertEquals(3, doc.features.size)
    }
}
