package com.example

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.LayerSourceType
import com.example.data.local.SpatialLayerEntity
import com.example.ui.components.SpatialFilesPanel
import com.example.ui.theme.GeoSurveiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpatialFilesPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `lists loaded GPX KML GeoJSON files and toggles visibility independently`() {
        val layersState = mutableStateListOf(
            SpatialLayerEntity(
                id = 1L,
                name = "Patroli_Hulu.gpx",
                sourceType = LayerSourceType.GPX.name,
                colorHex = "#2563EB",
                isVisible = true
            ),
            SpatialLayerEntity(
                id = 2L,
                name = "Zona_Air.kml",
                sourceType = LayerSourceType.KML.name,
                colorHex = "#7C3AED",
                isVisible = true
            ),
            SpatialLayerEntity(
                id = 3L,
                name = "Batas_Blok.geojson",
                sourceType = LayerSourceType.GEOJSON.name,
                colorHex = "#1B6B52",
                isVisible = true
            )
        )

        composeTestRule.setContent {
            GeoSurveiTheme {
                SpatialFilesPanel(
                    layers = layersState,
                    features = emptyList(),
                    tileCountsByLayer = emptyMap(),
                    onToggleVisibility = { target ->
                        val idx = layersState.indexOfFirst { it.id == target.id }
                        if (idx >= 0) {
                            layersState[idx] = layersState[idx].copy(isVisible = !layersState[idx].isVisible)
                        }
                    },
                    onSetMultipleVisibility = { ids, visible ->
                        layersState.forEachIndexed { idx, item ->
                            if (item.id in ids) layersState[idx] = item.copy(isVisible = visible)
                        }
                    },
                    onZoomToLayer = {},
                    onImportClick = {},
                    onClose = {}
                )
            }
        }

        // All three loaded spatial files are displayed
        composeTestRule.onNodeWithText("Patroli_Hulu.gpx").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zona_Air.kml").assertIsDisplayed()
        composeTestRule.onNodeWithText("Batas_Blok.geojson").assertIsDisplayed()

        // Toggle GPX file visibility off independently
        composeTestRule.onNodeWithTag("toggle_spatial_file_1").performClick()
        assertFalse(layersState.first { it.id == 1L }.isVisible)
        assertTrue(layersState.first { it.id == 2L }.isVisible)
        assertTrue(layersState.first { it.id == 3L }.isVisible)

        // Filter to KML only and confirm GPX/GeoJSON are hidden from filtered list while KML stays shown
        composeTestRule.onNodeWithTag("spatial_filter_kml").performClick()
        composeTestRule.onNodeWithText("Zona_Air.kml").assertIsDisplayed()
        composeTestRule.onNodeWithTag("spatial_file_row_1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("spatial_file_row_3").assertDoesNotExist()

        // Switch back to All and verify bulk toggle hides all remaining layers
        composeTestRule.onNodeWithTag("spatial_filter_all").performClick()
        composeTestRule.onNodeWithTag("spatial_panel_toggle_all_button").performClick()
        assertTrue(layersState.all { it.isVisible }) // first bulk click made all visible (2/3 -> 3/3)
        composeTestRule.onNodeWithTag("spatial_panel_toggle_all_button").performClick()
        assertEquals(0, layersState.count { it.isVisible }) // second bulk click hid all
    }
}
