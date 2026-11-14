package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "geosurvei_prefs")

data class UserPreferences(
    val coordFormat: CoordFormat = CoordFormat.DD,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val mapThemeMode: MapThemeMode = MapThemeMode.SYSTEM,
    val showGridLines: Boolean = true,
    val showCrosshair: Boolean = true,
    val isSampleSeeded: Boolean = false
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val COORD_FORMAT = stringPreferencesKey("coord_format")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val MAP_THEME = stringPreferencesKey("map_theme")
        val SHOW_GRID = booleanPreferencesKey("show_grid")
        val SHOW_CROSSHAIR = booleanPreferencesKey("show_crosshair")
        val SAMPLE_SEEDED = booleanPreferencesKey("sample_seeded")
    }

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        UserPreferences(
            coordFormat = prefs[Keys.COORD_FORMAT]?.let {
                runCatching { CoordFormat.valueOf(it) }.getOrNull()
            } ?: CoordFormat.DD,
            distanceUnit = prefs[Keys.DISTANCE_UNIT]?.let {
                runCatching { DistanceUnit.valueOf(it) }.getOrNull()
            } ?: DistanceUnit.METRIC,
            mapThemeMode = prefs[Keys.MAP_THEME]?.let {
                runCatching { MapThemeMode.valueOf(it) }.getOrNull()
            } ?: MapThemeMode.SYSTEM,
            showGridLines = prefs[Keys.SHOW_GRID] ?: true,
            showCrosshair = prefs[Keys.SHOW_CROSSHAIR] ?: true,
            isSampleSeeded = prefs[Keys.SAMPLE_SEEDED] ?: false
        )
    }

    suspend fun setCoordFormat(format: CoordFormat) {
        context.dataStore.edit { it[Keys.COORD_FORMAT] = format.name }
    }

    suspend fun setDistanceUnit(unit: DistanceUnit) {
        context.dataStore.edit { it[Keys.DISTANCE_UNIT] = unit.name }
    }

    suspend fun setMapThemeMode(mode: MapThemeMode) {
        context.dataStore.edit { it[Keys.MAP_THEME] = mode.name }
    }

    suspend fun setShowGridLines(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_GRID] = show }
    }

    suspend fun setShowCrosshair(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_CROSSHAIR] = show }
    }

    suspend fun markSampleSeeded() {
        context.dataStore.edit { it[Keys.SAMPLE_SEEDED] = true }
    }
}
