package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.GeoSurveiDatabase
import com.example.data.local.LiveGpsState
import com.example.spatial.ControlPoint
import com.example.spatial.GeoMath
import com.example.spatial.ImageGeoreferencer
import com.example.spatial.MbTilesReader
import com.example.ui.MapViewportState
import com.example.ui.PendingImageGeoreference
import com.example.ui.components.ImageGeoreferenceDialog
import com.example.ui.theme.GeoSurveiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class ImageGeoreferencerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `two point similarity transform handles scale and 90 degree rotation accurately`() {
        // Map (0, 0.5) -> (-7.55, 110.44) and (1, 0.5) -> (-7.53, 110.44) (north-up rotated 90 deg CCW)
        val p1 = ControlPoint(id = 1, imageNormX = 0.0f, imageNormY = 0.5f, mapLat = -7.55, mapLon = 110.44)
        val p2 = ControlPoint(id = 2, imageNormX = 1.0f, imageNormY = 0.5f, mapLat = -7.53, mapLon = 110.44)

        val sol = ImageGeoreferencer.solve(listOf(p1, p2))
        assertNotNull(sol)
        sol!!

        assertEquals(0.0, sol.rmseMeters, 0.5)
        assertEquals(2, sol.gcpCount)
        assertTrue(abs(abs(sol.rotationDeg) - 90.0) < 2.0)

        // Check midpoint stays centered near (-7.54, 110.44)
        val mid = sol.transform.imageNormToLatLon(0.5, 0.5)
        assertEquals(-7.54, mid.latitude, 0.001)
        assertEquals(110.44, mid.longitude, 0.001)
    }

    @Test
    fun `three plus point least squares affine solver computes transform and RMSE`() {
        val points = listOf(
            ControlPoint(id = 1, imageNormX = 0.1f, imageNormY = 0.1f, mapLat = -7.52, mapLon = 110.42),
            ControlPoint(id = 2, imageNormX = 0.9f, imageNormY = 0.1f, mapLat = -7.52, mapLon = 110.46),
            ControlPoint(id = 3, imageNormX = 0.9f, imageNormY = 0.9f, mapLat = -7.56, mapLon = 110.46),
            // Slight intentional offset on point 4 (~10-15m) so RMSE > 0
            ControlPoint(id = 4, imageNormX = 0.1f, imageNormY = 0.9f, mapLat = -7.5601, mapLon = 110.4201)
        )

        val sol = ImageGeoreferencer.solve(points)
        assertNotNull(sol)
        sol!!

        assertEquals(4, sol.gcpCount)
        assertTrue("RMSE should be small yet positive, got ${sol.rmseMeters}", sol.rmseMeters in 0.5..30.0)
        assertTrue(sol.bounds.minLat < -7.56)
        assertTrue(sol.bounds.maxLat > -7.52)
        assertTrue(sol.bounds.minLon < 110.42)
        assertTrue(sol.bounds.maxLon > 110.46)
    }

    @Test
    fun `rejects fewer than two paired points or collinear points in affine mode`() {
        // Only 1 paired point
        assertNull(
            ImageGeoreferencer.solve(
                listOf(
                    ControlPoint(id = 1, imageNormX = 0.2f, imageNormY = 0.2f, mapLat = -7.54, mapLon = 110.44),
                    ControlPoint(id = 2, imageNormX = 0.8f, imageNormY = 0.8f)
                )
            )
        )

        // 3 collinear points on image
        assertNull(
            ImageGeoreferencer.solve(
                listOf(
                    ControlPoint(id = 1, imageNormX = 0.1f, imageNormY = 0.1f, mapLat = -7.52, mapLon = 110.42),
                    ControlPoint(id = 2, imageNormX = 0.5f, imageNormY = 0.5f, mapLat = -7.54, mapLon = 110.44),
                    ControlPoint(id = 3, imageNormX = 0.9f, imageNormY = 0.9f, mapLat = -7.56, mapLon = 110.46)
                )
            )
        )
    }

    @Test
    fun `rectifyBitmapToPng outputs north-aligned PNG file with valid dimensions`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val srcBmp = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val sol = ImageGeoreferencer.solve(
            listOf(
                ControlPoint(1, 0f, 0f, -7.53, 110.43),
                ControlPoint(2, 1f, 1f, -7.55, 110.45)
            )
        )!!
        val outFile = File(context.cacheDir, "test_rectified.png").apply { delete() }

        val ok = ImageGeoreferencer.rectifyBitmapToPng(srcBmp, sol, outFile, maxEdgePx = 512)
        srcBmp.recycle()

        assertTrue(ok)
        assertTrue(outFile.exists())
        val decoded = BitmapFactory.decodeFile(outFile.absolutePath)
        assertNotNull(decoded)
        assertTrue(decoded.width >= 256)
        assertTrue(decoded.height >= 256)
        decoded.recycle()
        outFile.delete()
    }

    @Test
    fun `ImageGeoreferenceDialog enables apply button when GCPs are paired and returns confirmed points`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tempImg = File(context.cacheDir, "ui_georef_test.png")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        tempImg.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bmp.recycle()

        val db = Room.inMemoryDatabaseBuilder(context, GeoSurveiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val reader = MbTilesReader(db.mapTileDao())

        var confirmedName: String? = null
        var confirmedCount = 0

        composeTestRule.setContent {
            GeoSurveiTheme {
                ImageGeoreferenceDialog(
                    pending = PendingImageGeoreference(
                        fileName = "Peta_Drone_BlokA",
                        localImagePath = tempImg.absolutePath,
                        imageWidthPx = 64,
                        imageHeightPx = 64
                    ),
                    initialViewport = MapViewportState(centerLat = -7.54, centerLon = 110.44, zoom = 15.0),
                    layers = emptyList(),
                    features = emptyList(),
                    gpsState = LiveGpsState(),
                    isDarkMap = false,
                    isSaving = false,
                    mbTilesReader = reader,
                    getRasterBitmap = { null },
                    onCancel = {},
                    onConfirm = { name, pts, _ ->
                        confirmedName = name
                        confirmedCount = pts.count { it.isPaired }
                    },
                    initialControlPoints = listOf(
                        ControlPoint(id = 1, imageNormX = 0.1f, imageNormY = 0.1f, mapLat = -7.535, mapLon = 110.435),
                        ControlPoint(id = 2, imageNormX = 0.9f, imageNormY = 0.9f) // Missing map point initially
                    )
                )
            }
        }

        // Apply button disabled until 2nd GCP map coordinate is set
        composeTestRule.onNodeWithTag("apply_georef_gcp_button").assertIsNotEnabled()

        // Select GCP #2 and pair via Map Crosshair button
        composeTestRule.onNodeWithTag("gcp_chip_2").performClick()
        composeTestRule.onNodeWithTag("gcp_tab_map").performClick()
        composeTestRule.onNodeWithTag("use_crosshair_for_gcp_button").performClick()

        // Summary badge & Apply button now active
        composeTestRule.onNodeWithTag("georef_solution_summary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("apply_georef_gcp_button").assertIsEnabled()

        // Can add a 3rd GCP slot
        composeTestRule.onNodeWithTag("add_gcp_button").performClick()
        composeTestRule.onNodeWithTag("gcp_chip_3").assertIsDisplayed()

        // Confirm georeferencing
        composeTestRule.onNodeWithTag("apply_georef_gcp_button").performClick()
        assertEquals("Peta_Drone_BlokA", confirmedName)
        assertEquals(2, confirmedCount)

        db.close()
        tempImg.delete()
    }
}
