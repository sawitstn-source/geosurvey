package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.location.FieldLocationManager
import com.example.spatial.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Immutable
data class MapViewportState(
    val centerLat: Double = -7.5450,
    val centerLon: Double = 110.4425,
    val zoom: Double = 14.3,
    val followGps: Boolean = false
)

@Immutable
data class PendingRasterCalibration(
    val fileName: String,
    val localImagePath: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val isPdf: Boolean = false,
    val pdfFilePath: String? = null,
    val pageCount: Int = 1,
    val selectedPageIndex: Int = 0,
    val hasAutoGeoPdfBounds: Boolean = false
)

@Immutable
data class PendingImageGeoreference(
    val fileName: String,
    val localImagePath: String,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val isPdf: Boolean = false,
    val pdfFilePath: String? = null
)

@Immutable
data class ActiveTrackState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val trackName: String = "Jejak Survei Lapangan",
    val trackCategory: SurveyCategory = SurveyCategory.INFRASTRUKTUR,
    val intervalPreset: TrackIntervalPreset = TrackIntervalPreset.STANDARD,
    val points: List<LatLon> = emptyList(),
    val elapsedSeconds: Long = 0L,
    val totalDistanceMeters: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val lastRecordedTimeMs: Long = 0L
)

@Immutable
data class GeoSurveiUiState(
    val layers: List<SpatialLayerEntity> = emptyList(),
    val features: List<SpatialFeatureEntity> = emptyList(),
    val tileCountsByLayer: Map<Long, Int> = emptyMap(),
    val prefs: UserPreferences = UserPreferences(),
    val gpsState: LiveGpsState = LiveGpsState(),
    val compassAzimuthDeg: Float = 0f,
    val viewport: MapViewportState = MapViewportState(),
    val toolMode: MapToolMode = MapToolMode.PAN,
    val draftVertices: List<LatLon> = emptyList(),
    val activeTrack: ActiveTrackState = ActiveTrackState(),
    val selectedFeature: SpatialFeatureEntity? = null,
    val compassTargetFeature: SpatialFeatureEntity? = null,
    val editingFeature: SpatialFeatureEntity? = null,
    val viewingMbTilesLayer: SpatialLayerEntity? = null,
    val showAddWaypointDialog: Boolean = false,
    val waypointInitialCoord: LatLon? = null,
    val pendingRasterCalibration: PendingRasterCalibration? = null,
    val pendingImageGeoreference: PendingImageGeoreference? = null,
    val searchQuery: String = "",
    val selectedCategoryFilter: SurveyCategory? = null,
    val statusMessage: String? = null,
    val isImporting: Boolean = false
)

class GeoSurveiViewModel(application: Application) : AndroidViewModel(application) {

    private val db = GeoSurveiDatabase.getInstance(application)
    private val dao = db.spatialDao()
    val mapTileDao = db.mapTileDao()
    private val prefsRepo = UserPreferencesRepository(application)
    val locationManager = FieldLocationManager(application)
    val mbTilesReader = MbTilesReader(mapTileDao)

    private val rasterBitmapCache = mutableMapOf<String, ImageBitmap>()

    private val _viewport = MutableStateFlow(MapViewportState())
    private val _toolMode = MutableStateFlow(MapToolMode.PAN)
    private val _draftVertices = MutableStateFlow<List<LatLon>>(emptyList())
    private val _activeTrack = MutableStateFlow(ActiveTrackState())
    private val _selectedFeature = MutableStateFlow<SpatialFeatureEntity?>(null)
    private val _compassTarget = MutableStateFlow<SpatialFeatureEntity?>(null)
    private val _editingFeature = MutableStateFlow<SpatialFeatureEntity?>(null)
    private val _viewingMbTilesLayer = MutableStateFlow<SpatialLayerEntity?>(null)
    private val _showAddWaypoint = MutableStateFlow(false)
    private val _waypointInitialCoord = MutableStateFlow<LatLon?>(null)
    private val _pendingRaster = MutableStateFlow<PendingRasterCalibration?>(null)
    private val _pendingGcpGeoreference = MutableStateFlow<PendingImageGeoreference?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _categoryFilter = MutableStateFlow<SurveyCategory?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)

    private val tileCountsFlow: Flow<Map<Long, Int>> = mapTileDao.observeTileCountsByLayer()
        .map { list -> list.associate { it.layerId to it.tileCount } }

    val uiState: StateFlow<GeoSurveiUiState> = combine(
        dao.observeAllLayers(),
        dao.observeAllFeatures(),
        tileCountsFlow,
        prefsRepo.preferences,
        locationManager.gpsState,
        locationManager.compassAzimuthDeg,
        _viewport,
        _toolMode,
        _draftVertices,
        _activeTrack,
        _selectedFeature,
        _compassTarget,
        _editingFeature,
        _viewingMbTilesLayer,
        _showAddWaypoint,
        _waypointInitialCoord,
        _pendingRaster,
        _pendingGcpGeoreference,
        _searchQuery,
        _categoryFilter,
        _statusMessage,
        _isImporting
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        GeoSurveiUiState(
            layers = arr[0] as List<SpatialLayerEntity>,
            features = arr[1] as List<SpatialFeatureEntity>,
            tileCountsByLayer = arr[2] as Map<Long, Int>,
            prefs = arr[3] as UserPreferences,
            gpsState = arr[4] as LiveGpsState,
            compassAzimuthDeg = arr[5] as Float,
            viewport = arr[6] as MapViewportState,
            toolMode = arr[7] as MapToolMode,
            draftVertices = arr[8] as List<LatLon>,
            activeTrack = arr[9] as ActiveTrackState,
            selectedFeature = arr[10] as SpatialFeatureEntity?,
            compassTargetFeature = arr[11] as SpatialFeatureEntity?,
            editingFeature = arr[12] as SpatialFeatureEntity?,
            viewingMbTilesLayer = arr[13] as SpatialLayerEntity?,
            showAddWaypointDialog = arr[14] as Boolean,
            waypointInitialCoord = arr[15] as LatLon?,
            pendingRasterCalibration = arr[16] as PendingRasterCalibration?,
            pendingImageGeoreference = arr[17] as PendingImageGeoreference?,
            searchQuery = arr[18] as String,
            selectedCategoryFilter = arr[19] as SurveyCategory?,
            statusMessage = arr[20] as String?,
            isImporting = arr[21] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GeoSurveiUiState()
    )

    init {
        viewModelScope.launch {
            val prefs = prefsRepo.preferences.first()
            SampleSurveySeeder.seedIfNeeded(
                dao = dao,
                alreadySeeded = prefs.isSampleSeeded,
                markSeeded = { prefsRepo.markSampleSeeded() }
            )
            // Ensure demo offline basemap also has Room MBTiles tile pyramid stored for the MBTiles viewer
            val layers = dao.observeAllLayers().first()
            val demoRaster = layers.firstOrNull { it.drawableResName == SampleSurveySeeder.DEMO_RASTER_RES_NAME }
            if (demoRaster != null && mapTileDao.getTileCount(demoRaster.id) == 0) {
                withContext(Dispatchers.IO) {
                    val resId = getApplication<Application>().resources.getIdentifier(
                        SampleSurveySeeder.DEMO_RASTER_RES_NAME,
                        "drawable",
                        getApplication<Application>().packageName
                    )
                    if (resId != 0) {
                        val bmp = BitmapFactory.decodeResource(getApplication<Application>().resources, resId)
                        if (bmp != null) {
                            val (minZ, maxZ) = PdfMapProcessor.generateRoomTilesFromBitmap(
                                bitmap = bmp,
                                bounds = MapBounds(demoRaster.minLat, demoRaster.maxLat, demoRaster.minLon, demoRaster.maxLon),
                                layerId = demoRaster.id,
                                mapTileDao = mapTileDao,
                                maxTotalTiles = 80
                            )
                            bmp.recycle()
                            dao.updateLayer(demoRaster.copy(minZoom = minZ, maxZoom = maxZ))
                        }
                    }
                }
            }
        }
        // Track GPS points when recording is active
        viewModelScope.launch {
            locationManager.gpsState.collect { gps ->
                if (gps.hasFix && gps.latitude != null && gps.longitude != null) {
                    if (_viewport.value.followGps) {
                        _viewport.update {
                            it.copy(centerLat = gps.latitude, centerLon = gps.longitude)
                        }
                    }
                    val track = _activeTrack.value
                    if (track.isRecording && !track.isPaused) {
                        val pt = LatLon(gps.latitude, gps.longitude, gps.altitudeMeters)
                        val last = track.points.lastOrNull()
                        val dist = if (last != null) GeoMath.distanceMeters(last, pt) else 0.0
                        val now = System.currentTimeMillis()
                        val elapsedSinceLast = now - track.lastRecordedTimeMs
                        if (last == null || (dist >= track.intervalPreset.minDistanceMeters && elapsedSinceLast >= track.intervalPreset.minTimeMs)) {
                            val updatedPts = track.points + pt
                            _activeTrack.value = track.copy(
                                points = updatedPts,
                                totalDistanceMeters = GeoMath.polylineLengthMeters(updatedPts),
                                elevationGainMeters = GeoMath.elevationGainMeters(updatedPts),
                                lastRecordedTimeMs = now
                            )
                        }
                    }
                }
            }
        }
        // Track timer ticker
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                val t = _activeTrack.value
                if (t.isRecording && !t.isPaused) {
                    _activeTrack.value = t.copy(elapsedSeconds = t.elapsedSeconds + 1L)
                }
            }
        }
    }

    fun startLocationServices() {
        locationManager.startUpdates()
    }

    fun stopLocationServices() {
        locationManager.stopUpdates()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun showMessage(msg: String) {
        _statusMessage.value = msg
    }

    fun panAndZoomBy(dxPixels: Float, dyPixels: Float, zoomFactor: Float, canvasWidthPx: Float, canvasHeightPx: Float) {
        if (canvasWidthPx <= 0f || canvasHeightPx <= 0f) return
        _viewport.update { vp ->
            val newZoom = (vp.zoom + kotlin.math.log2(zoomFactor.toDouble())).coerceIn(2.0, 20.5)
            val worldSizePx = 256.0 * Math.pow(2.0, newZoom)
            val currentNormX = GeoMath.lonToNormX(vp.centerLon)
            val currentNormY = GeoMath.latToNormY(vp.centerLat)

            val newNormX = (currentNormX - dxPixels / worldSizePx).coerceIn(0.0, 1.0)
            val newNormY = (currentNormY - dyPixels / worldSizePx).coerceIn(0.0001, 0.9999)

            vp.copy(
                centerLat = GeoMath.normYToLat(newNormY),
                centerLon = GeoMath.normXToLon(newNormX),
                zoom = newZoom,
                followGps = false
            )
        }
    }

    fun zoomIn() {
        _viewport.update { it.copy(zoom = (it.zoom + 0.85).coerceIn(2.0, 20.5)) }
    }

    fun zoomOut() {
        _viewport.update { it.copy(zoom = (it.zoom - 0.85).coerceIn(2.0, 20.5)) }
    }

    fun centerOnGpsOrRequest() {
        val gps = locationManager.gpsState.value
        if (gps.hasFix && gps.latitude != null && gps.longitude != null) {
            _viewport.update {
                it.copy(
                    centerLat = gps.latitude,
                    centerLon = gps.longitude,
                    zoom = maxOf(it.zoom, 16.0),
                    followGps = true
                )
            }
        } else {
            _statusMessage.value = if (gps.isPermissionGranted) {
                "Menunggu sinyal GPS... Gunakan crosshair peta untuk survei manual."
            } else {
                "Izin lokasi diperlukan untuk melacak posisi GPS."
            }
        }
    }

    fun zoomToLayer(layer: SpatialLayerEntity) {
        val bounds = MapBounds(layer.minLat, layer.maxLat, layer.minLon, layer.maxLon)
        if (!bounds.isValid) return
        val latSpan = (bounds.maxLat - bounds.minLat).coerceAtLeast(0.001)
        val lonSpan = (bounds.maxLon - bounds.minLon).coerceAtLeast(0.001)
        val maxSpan = maxOf(latSpan, lonSpan)
        val estZoom = (kotlin.math.log2(360.0 / maxSpan) - 0.5).coerceIn(3.0, 18.0)
        _viewport.update {
            it.copy(
                centerLat = bounds.centerLat,
                centerLon = bounds.centerLon,
                zoom = estZoom,
                followGps = false
            )
        }
    }

    fun zoomToFeature(feature: SpatialFeatureEntity) {
        val rings = GeoMath.jsonToRings(feature.coordinatesJson)
        val allPts = rings.flatten()
        val bounds = GeoMath.computeBounds(allPts)
        if (bounds != null && (bounds.maxLat - bounds.minLat > 0.0005 || bounds.maxLon - bounds.minLon > 0.0005)) {
            val maxSpan = maxOf(bounds.maxLat - bounds.minLat, bounds.maxLon - bounds.minLon)
            val estZoom = (kotlin.math.log2(360.0 / maxSpan) - 0.8).coerceIn(4.0, 18.5)
            _viewport.update {
                it.copy(
                    centerLat = bounds.centerLat,
                    centerLon = bounds.centerLon,
                    zoom = estZoom,
                    followGps = false
                )
            }
        } else {
            _viewport.update {
                it.copy(
                    centerLat = feature.latitude,
                    centerLon = feature.longitude,
                    zoom = maxOf(it.zoom, 16.5),
                    followGps = false
                )
            }
        }
        _selectedFeature.value = feature
    }

    fun setToolMode(mode: MapToolMode) {
        _toolMode.value = mode
        if (mode == MapToolMode.PAN) {
            _draftVertices.value = emptyList()
        }
    }

    fun addDraftVertex(coord: LatLon) {
        _draftVertices.update { it + coord }
    }

    fun undoDraftVertex() {
        _draftVertices.update { if (it.isNotEmpty()) it.dropLast(1) else it }
    }

    fun clearDraftVertices() {
        _draftVertices.value = emptyList()
    }

    fun saveDraftAsFeature(name: String, category: SurveyCategory, description: String) {
        val pts = _draftVertices.value
        val mode = _toolMode.value
        val isPolygon = mode == MapToolMode.MEASURE_AREA || mode == MapToolMode.DRAW_POLYGON
        if (isPolygon && pts.size < 3) {
            _statusMessage.value = "Poligon membutuhkan minimal 3 titik sudut."
            return
        }
        if (!isPolygon && pts.size < 2) {
            _statusMessage.value = "Jalur membutuhkan minimal 2 titik."
            return
        }
        viewModelScope.launch {
            val layer = ensureEditableLayer()
            val ring = if (isPolygon) pts + pts.first() else pts
            val geomType = if (isPolygon) GeometryType.POLYGON else GeometryType.LINESTRING
            val bounds = GeoMath.computeBounds(ring)
            val attrs = buildMap {
                put("Panjang", GeoMath.formatDistance(GeoMath.polylineLengthMeters(ring), uiState.value.prefs.distanceUnit))
                if (isPolygon) {
                    put("Luas", GeoMath.formatArea(GeoMath.polygonAreaSquareMeters(ring), uiState.value.prefs.distanceUnit))
                }
            }
            dao.insertFeature(
                SpatialFeatureEntity(
                    layerId = layer.id,
                    geometryType = geomType.name,
                    name = name.ifBlank { if (isPolygon) "Area Survei" else "Jalur Survei" },
                    description = description,
                    category = category.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(ring)),
                    attributesJson = GeoMath.attributesToJson(attrs),
                    latitude = bounds?.centerLat ?: pts.first().latitude,
                    longitude = bounds?.centerLon ?: pts.first().longitude
                )
            )
            _draftVertices.value = emptyList()
            _toolMode.value = MapToolMode.PAN
            _statusMessage.value = "${geomType.label} berhasil disimpan ke ${layer.name}"
        }
    }

    fun openAddWaypointDialog(useGpsIfAvailable: Boolean) {
        val gps = locationManager.gpsState.value
        val vp = _viewport.value
        val coord = if (useGpsIfAvailable && gps.hasFix && gps.latitude != null && gps.longitude != null) {
            LatLon(gps.latitude, gps.longitude, gps.altitudeMeters)
        } else {
            LatLon(vp.centerLat, vp.centerLon, null)
        }
        openAddWaypointAtCoord(coord)
    }

    fun openAddWaypointAtCoord(coord: LatLon) {
        _editingFeature.value = null
        _waypointInitialCoord.value = coord
        _showAddWaypoint.value = true
    }

    fun openEditFeatureDialog(feature: SpatialFeatureEntity) {
        _editingFeature.value = feature
        _waypointInitialCoord.value = LatLon(feature.latitude, feature.longitude, feature.elevationMeters)
        _showAddWaypoint.value = true
    }

    fun dismissWaypointDialog() {
        _showAddWaypoint.value = false
        _editingFeature.value = null
    }

    fun saveWaypointOrFeature(
        name: String,
        description: String,
        category: SurveyCategory,
        lat: Double,
        lon: Double,
        elevation: Double?,
        photoUris: List<String>,
        attributes: Map<String, String>
    ) {
        viewModelScope.launch {
            val persistedPhotos = photoUris.mapNotNull { copyPhotoToInternalIfNeeded(it) }
            val encodedPhotos = GeoMath.encodePhotoUris(persistedPhotos)
            val editing = _editingFeature.value
            if (editing != null) {
                val updated = if (editing.geometryType == GeometryType.POINT.name) {
                    editing.copy(
                        name = name,
                        description = description,
                        category = category.name,
                        latitude = lat,
                        longitude = lon,
                        elevationMeters = elevation,
                        photoUri = encodedPhotos,
                        coordinatesJson = GeoMath.ringsToJson(listOf(listOf(LatLon(lat, lon, elevation)))),
                        attributesJson = GeoMath.attributesToJson(attributes)
                    )
                } else {
                    editing.copy(
                        name = name,
                        description = description,
                        category = category.name,
                        photoUri = encodedPhotos,
                        attributesJson = GeoMath.attributesToJson(attributes)
                    )
                }
                dao.updateFeature(updated)
                _selectedFeature.value = updated
                _statusMessage.value = "Perubahan '$name' disimpan offline."
            } else {
                val layer = ensureEditableLayer()
                val gps = locationManager.gpsState.value
                val entity = SpatialFeatureEntity(
                    layerId = layer.id,
                    geometryType = GeometryType.POINT.name,
                    name = name.ifBlank { "Titik Survei" },
                    description = description,
                    category = category.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(listOf(LatLon(lat, lon, elevation)))),
                    attributesJson = GeoMath.attributesToJson(attributes),
                    photoUri = encodedPhotos,
                    latitude = lat,
                    longitude = lon,
                    elevationMeters = elevation,
                    accuracyMeters = gps.accuracyMeters
                )
                dao.insertFeature(entity)
                _toolMode.value = MapToolMode.PAN
                _statusMessage.value = "Titik '${entity.name}' tersimpan offline."
            }
            _showAddWaypoint.value = false
            _editingFeature.value = null
        }
    }

    private suspend fun copyPhotoToInternalIfNeeded(uriStr: String?): String? {
        if (uriStr.isNullOrBlank()) return null
        if (uriStr.startsWith("/") || uriStr.startsWith("file://")) return uriStr
        return withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriStr)
                val dir = File(getApplication<Application>().filesDir, "photos").apply { mkdirs() }
                val dest = File(dir, "foto_${UUID.randomUUID()}.jpg")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                dest.absolutePath
            }.getOrNull() ?: uriStr
        }
    }

    fun startTrackRecording(
        trackName: String = "Jejak Survei Lapangan",
        category: SurveyCategory = SurveyCategory.INFRASTRUKTUR,
        intervalPreset: TrackIntervalPreset = TrackIntervalPreset.STANDARD
    ) {
        val gps = locationManager.gpsState.value
        val initialPts = if (gps.hasFix && gps.latitude != null && gps.longitude != null) {
            listOf(LatLon(gps.latitude, gps.longitude, gps.altitudeMeters))
        } else {
            emptyList()
        }
        _activeTrack.value = ActiveTrackState(
            isRecording = true,
            isPaused = false,
            trackName = trackName.ifBlank { "Jejak Survei Lapangan" },
            trackCategory = category,
            intervalPreset = intervalPreset,
            points = initialPts,
            elapsedSeconds = 0L,
            totalDistanceMeters = 0.0,
            elevationGainMeters = 0.0,
            lastRecordedTimeMs = System.currentTimeMillis()
        )
        _statusMessage.value = if (gps.hasFix) {
            "Pelacakan '${_activeTrack.value.trackName}' aktif (${intervalPreset.label})."
        } else {
            "Pelacakan offline dimulai — gunakan '+ Titik' atau ketuk crosshair untuk merekam titik jalur."
        }
    }

    fun addManualTrackPointAtCenter() {
        val vp = _viewport.value
        addTrackPoint(LatLon(vp.centerLat, vp.centerLon, null))
    }

    fun addTrackPoint(coord: LatLon) {
        _activeTrack.update { track ->
            if (!track.isRecording) return@update track
            val pts = track.points + coord
            track.copy(
                points = pts,
                totalDistanceMeters = GeoMath.polylineLengthMeters(pts),
                elevationGainMeters = GeoMath.elevationGainMeters(pts),
                lastRecordedTimeMs = System.currentTimeMillis()
            )
        }
    }

    fun togglePauseTrack() {
        _activeTrack.update { it.copy(isPaused = !it.isPaused) }
    }

    fun stopAndSaveTrack(trackName: String) {
        val track = _activeTrack.value
        val finalName = trackName.ifBlank { track.trackName }
        if (track.points.size < 2) {
            _activeTrack.value = ActiveTrackState()
            _statusMessage.value = "Pelacakan dibatalkan (minimal 2 titik jalur diperlukan)."
            return
        }
        viewModelScope.launch {
            val layer = ensureEditableLayer()
            val bounds = GeoMath.computeBounds(track.points)
            val avgSpeedKmh = if (track.elapsedSeconds > 0) {
                (track.totalDistanceMeters / track.elapsedSeconds) * 3.6
            } else 0.0
            val attrs = buildMap {
                put("Jarak", GeoMath.formatDistance(track.totalDistanceMeters, uiState.value.prefs.distanceUnit))
                put("Durasi", "${track.elapsedSeconds / 60}m ${track.elapsedSeconds % 60}d")
                put("Kecepatan Rata2", String.format(java.util.Locale.US, "%.1f km/j", avgSpeedKmh))
                if (track.elevationGainMeters > 0.5) {
                    put("Tanjakan (+)", "${track.elevationGainMeters.toInt()} m")
                }
                put("Interval", track.intervalPreset.label)
                put("Jumlah Titik", track.points.size.toString())
            }
            dao.insertFeature(
                SpatialFeatureEntity(
                    layerId = layer.id,
                    geometryType = GeometryType.LINESTRING.name,
                    name = finalName,
                    description = "Rekaman pelacakan survei lapangan offline.",
                    category = track.trackCategory.name,
                    coordinatesJson = GeoMath.ringsToJson(listOf(track.points)),
                    attributesJson = GeoMath.attributesToJson(attrs),
                    latitude = bounds?.centerLat ?: track.points.first().latitude,
                    longitude = bounds?.centerLon ?: track.points.first().longitude
                )
            )
            _activeTrack.value = ActiveTrackState()
            _statusMessage.value = "Jejak '$finalName' berhasil disimpan offline."
        }
    }

    fun discardTrack() {
        _activeTrack.value = ActiveTrackState()
    }

    fun selectFeature(feature: SpatialFeatureEntity?) {
        _selectedFeature.value = feature
    }

    fun openCompassTarget(feature: SpatialFeatureEntity?) {
        _compassTarget.value = feature
    }

    fun deleteFeature(feature: SpatialFeatureEntity) {
        viewModelScope.launch {
            dao.deleteFeatureById(feature.id)
            if (_selectedFeature.value?.id == feature.id) _selectedFeature.value = null
            if (_compassTarget.value?.id == feature.id) _compassTarget.value = null
            _statusMessage.value = "'${feature.name}' dihapus."
        }
    }

    fun toggleLayerVisibility(layer: SpatialLayerEntity) {
        viewModelScope.launch {
            dao.setLayerVisibility(layer.id, !layer.isVisible)
        }
    }

    fun setLayersVisibility(layerIds: List<Long>, visible: Boolean) {
        if (layerIds.isEmpty()) return
        viewModelScope.launch {
            dao.setLayersVisibility(layerIds, visible)
        }
    }

    fun updateLayerColor(layerId: Long, colorHex: String) {
        viewModelScope.launch {
            dao.setLayerColor(layerId, colorHex)
        }
    }

    fun deleteLayer(layer: SpatialLayerEntity) {
        viewModelScope.launch {
            layer.localFilePath?.let { path -> runCatching { File(path).delete() } }
            dao.deleteLayerById(layer.id)
            _statusMessage.value = "Layer '${layer.name}' dihapus."
        }
    }

    fun importSpatialFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            var importedCount = 0
            for (uri in uris) {
                val result = withContext(Dispatchers.IO) {
                    SpatialFileParser.parseUri(getApplication(), uri)
                }
                when (result) {
                    is ImportResult.Ready -> {
                        val parsed = result.layer
                        val layerColor = parsed.preferredColorHex ?: pickPaletteColor(uiState.value.layers.size + importedCount)
                        val bounds = parsed.bounds ?: MapBounds(-7.6, -7.4, 110.3, 110.6)
                        val layerEntity = SpatialLayerEntity(
                            name = parsed.name,
                            sourceType = parsed.sourceType.name,
                            colorHex = layerColor,
                            isVisible = true,
                            isEditable = false,
                            minLat = bounds.minLat,
                            maxLat = bounds.maxLat,
                            minLon = bounds.minLon,
                            maxLon = bounds.maxLon,
                            localFilePath = parsed.localFilePath,
                            minZoom = parsed.minZoom,
                            maxZoom = parsed.maxZoom
                        )
                        val featureEntities = parsed.features.map { feat ->
                            val firstPt = feat.rings.firstOrNull()?.firstOrNull()
                            val fBounds = GeoMath.computeBounds(feat.rings.flatten())
                            SpatialFeatureEntity(
                                layerId = 0L,
                                geometryType = feat.geometryType.name,
                                name = feat.name,
                                description = feat.description,
                                category = feat.category.name,
                                coordinatesJson = GeoMath.ringsToJson(feat.rings),
                                attributesJson = GeoMath.attributesToJson(feat.attributes),
                                latitude = fBounds?.centerLat ?: firstPt?.latitude ?: 0.0,
                                longitude = fBounds?.centerLon ?: firstPt?.longitude ?: 0.0,
                                elevationMeters = feat.elevationMeters
                            )
                        }
                        val newId = dao.insertLayerWithFeatures(layerEntity, featureEntities)
                        val insertedLayer = dao.getLayerById(newId)
                        if (parsed.sourceType == LayerSourceType.MBTILES && !parsed.localFilePath.isNullOrBlank()) {
                            val tileCount = mbTilesReader.ingestMbTilesIntoRoom(parsed.localFilePath, newId)
                            _statusMessage.value = "MBTiles '${parsed.name}' diimpor ($tileCount tile disimpan ke Room)."
                        } else {
                            _statusMessage.value = "Berhasil mengimpor '${parsed.name}' (${featureEntities.size} fitur)."
                        }
                        insertedLayer?.let { zoomToLayer(it) }
                        importedCount++
                    }
                    is ImportResult.NeedsGeoreference -> {
                        val vp = _viewport.value
                        val span = 0.025
                        val bounds = result.suggestedBounds ?: MapBounds(
                            minLat = vp.centerLat - span,
                            maxLat = vp.centerLat + span,
                            minLon = vp.centerLon - span,
                            maxLon = vp.centerLon + span
                        )
                        _pendingRaster.value = PendingRasterCalibration(
                            fileName = result.fileName,
                            localImagePath = result.localImagePath,
                            minLat = bounds.minLat,
                            maxLat = bounds.maxLat,
                            minLon = bounds.minLon,
                            maxLon = bounds.maxLon,
                            isPdf = result.isPdf,
                            pdfFilePath = result.pdfFilePath,
                            pageCount = result.pageCount,
                            selectedPageIndex = 0,
                            hasAutoGeoPdfBounds = result.suggestedBounds != null
                        )
                    }
                    is ImportResult.Error -> {
                        _statusMessage.value = result.message
                    }
                }
            }
            _isImporting.value = false
        }
    }

    fun selectPdfPage(pageIndex: Int) {
        val pending = _pendingRaster.value ?: return
        val pdfPath = pending.pdfFilePath ?: return
        viewModelScope.launch {
            val pdfFile = File(pdfPath)
            val pngFile = File(pending.localImagePath)
            val ok = PdfMapProcessor.renderPdfPageToPng(pdfFile, pageIndex, pngFile)
            if (ok) {
                rasterBitmapCache.remove(pending.localImagePath)
                _pendingRaster.update { it?.copy(selectedPageIndex = pageIndex) }
            }
        }
    }

    fun confirmRasterCalibration(
        name: String,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        generateRoomTiles: Boolean = true
    ) {
        val pending = _pendingRaster.value ?: return
        if (minLat >= maxLat || minLon >= maxLon) {
            _statusMessage.value = "Batas koordinat tidak valid (Selatan harus < Utara, Barat harus < Timur)."
            return
        }
        viewModelScope.launch {
            _isImporting.value = true
            val sourceType = if (pending.isPdf) LayerSourceType.PDF.name else LayerSourceType.RASTER.name
            val entity = SpatialLayerEntity(
                name = name.ifBlank { pending.fileName },
                sourceType = sourceType,
                colorHex = "#1B6B52",
                isVisible = true,
                isEditable = false,
                minLat = minLat,
                maxLat = maxLat,
                minLon = minLon,
                maxLon = maxLon,
                localFilePath = pending.localImagePath
            )
            val id = dao.insertLayer(entity)
            var tileCount = 0
            if (generateRoomTiles) {
                withContext(Dispatchers.IO) {
                    val bmp = BitmapFactory.decodeFile(pending.localImagePath)
                    if (bmp != null) {
                        val (minZ, maxZ) = PdfMapProcessor.generateRoomTilesFromBitmap(
                            bitmap = bmp,
                            bounds = MapBounds(minLat, maxLat, minLon, maxLon),
                            layerId = id,
                            mapTileDao = mapTileDao
                        )
                        bmp.recycle()
                        tileCount = mapTileDao.getTileCount(id)
                        dao.getLayerById(id)?.let { saved ->
                            dao.updateLayer(saved.copy(minZoom = minZ, maxZoom = maxZ))
                        }
                    }
                }
            }
            dao.getLayerById(id)?.let { zoomToLayer(it) }
            _pendingRaster.value = null
            _isImporting.value = false
            val label = if (pending.isPdf) "Peta PDF" else "Peta raster"
            _statusMessage.value = if (tileCount > 0) {
                "$label '${entity.name}' aktif ($tileCount tile offline disimpan ke Room)."
            } else {
                "$label '${entity.name}' berhasil dikalibrasi & disimpan offline."
            }
        }
    }

    fun cancelRasterCalibration() {
        _pendingRaster.value?.let {
            runCatching { File(it.localImagePath).delete() }
            it.pdfFilePath?.let { p -> runCatching { File(p).delete() } }
        }
        _pendingRaster.value = null
    }

    fun startLocalImageGeoreferencing(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            val pending = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val rawName = queryDisplayName(uri) ?: "peta_gambar"
                    val baseName = rawName.substringBeforeLast('.').ifBlank { "Peta Gambar" }
                    val ext = rawName.substringAfterLast('.', "png").lowercase()
                        .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "png"
                    val rasterDir = File(getApplication<Application>().filesDir, "rasters").apply { mkdirs() }
                    val destFile = File(rasterDir, "gcp_src_${UUID.randomUUID()}.$ext")
                    resolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@runCatching null

                    val dims = ImageGeoreferencer.readImageDimensions(destFile.absolutePath)
                    if (dims == null) {
                        destFile.delete()
                        return@runCatching null
                    }
                    PendingImageGeoreference(
                        fileName = baseName,
                        localImagePath = destFile.absolutePath,
                        imageWidthPx = dims.first,
                        imageHeightPx = dims.second
                    )
                }.getOrNull()
            }
            _isImporting.value = false
            if (pending != null) {
                _pendingGcpGeoreference.value = pending
            } else {
                _statusMessage.value = "Gagal membaca file gambar lokal. Pastikan format PNG/JPG/WebP valid."
            }
        }
    }

    fun openGcpFromRasterCalibration() {
        val current = _pendingRaster.value ?: return
        viewModelScope.launch {
            val dims = withContext(Dispatchers.IO) {
                ImageGeoreferencer.readImageDimensions(current.localImagePath)
            } ?: (1024 to 1024)
            _pendingRaster.value = null
            _pendingGcpGeoreference.value = PendingImageGeoreference(
                fileName = current.fileName,
                localImagePath = current.localImagePath,
                imageWidthPx = dims.first,
                imageHeightPx = dims.second,
                isPdf = current.isPdf,
                pdfFilePath = current.pdfFilePath
            )
        }
    }

    fun confirmImageGeoreferencing(
        layerName: String,
        controlPoints: List<ControlPoint>,
        generateRoomTiles: Boolean = true
    ) {
        val pending = _pendingGcpGeoreference.value ?: return
        val solution = ImageGeoreferencer.solve(controlPoints)
        if (solution == null) {
            _statusMessage.value = "Minimal 2 pasang titik kontrol yang tidak sejajar/berhimpit diperlukan."
            return
        }
        viewModelScope.launch {
            _isImporting.value = true
            val resultLayerId = withContext(Dispatchers.IO) {
                val srcBitmap = ImageGeoreferencer.decodeSampledBitmap(pending.localImagePath, maxEdgePx = 2048)
                    ?: return@withContext null
                val rasterDir = File(getApplication<Application>().filesDir, "rasters").apply { mkdirs() }
                val rectifiedFile = File(rasterDir, "rectified_${UUID.randomUUID()}.png")
                val rectifiedOk = ImageGeoreferencer.rectifyBitmapToPng(srcBitmap, solution, rectifiedFile)
                srcBitmap.recycle()

                if (!rectifiedOk) {
                    rectifiedFile.delete()
                    return@withContext null
                }

                // Clean up temporary raw copy once rectified
                runCatching { File(pending.localImagePath).delete() }

                val sourceType = if (pending.isPdf) LayerSourceType.PDF.name else LayerSourceType.RASTER.name
                val entity = SpatialLayerEntity(
                    name = layerName.ifBlank { pending.fileName },
                    sourceType = sourceType,
                    colorHex = "#1B6B52",
                    isVisible = true,
                    isEditable = false,
                    minLat = solution.bounds.minLat,
                    maxLat = solution.bounds.maxLat,
                    minLon = solution.bounds.minLon,
                    maxLon = solution.bounds.maxLon,
                    localFilePath = rectifiedFile.absolutePath
                )
                val id = dao.insertLayer(entity)
                var tileCount = 0
                if (generateRoomTiles) {
                    val recBmp = BitmapFactory.decodeFile(rectifiedFile.absolutePath)
                    if (recBmp != null) {
                        val (minZ, maxZ) = PdfMapProcessor.generateRoomTilesFromBitmap(
                            bitmap = recBmp,
                            bounds = solution.bounds,
                            layerId = id,
                            mapTileDao = mapTileDao
                        )
                        recBmp.recycle()
                        tileCount = mapTileDao.getTileCount(id)
                        dao.getLayerById(id)?.let { saved ->
                            dao.updateLayer(saved.copy(minZoom = minZ, maxZoom = maxZ))
                        }
                    }
                }
                id to tileCount
            }

            if (resultLayerId == null) {
                _isImporting.value = false
                _statusMessage.value = "Gagal memproses rektifikasi gambar peta."
                return@launch
            }

            val (savedId, tileCount) = resultLayerId
            dao.getLayerById(savedId)?.let { saved ->
                zoomToLayer(saved)
            }
            _pendingGcpGeoreference.value = null
            _isImporting.value = false
            val rmseStr = String.format(java.util.Locale.US, "%.1f m", solution.rmseMeters)
            _statusMessage.value = if (tileCount > 0) {
                "Georeferensi '${layerName.ifBlank { pending.fileName }}' berhasil (RMSE ±$rmseStr, $tileCount tile Room)."
            } else {
                "Georeferensi '${layerName.ifBlank { pending.fileName }}' berhasil (RMSE ±$rmseStr, ${solution.gcpCount} GCP)."
            }
        }
    }

    fun cancelImageGeoreferencing() {
        _pendingGcpGeoreference.value?.let {
            rasterBitmapCache.remove(it.localImagePath)
            runCatching { File(it.localImagePath).delete() }
            it.pdfFilePath?.let { p -> runCatching { File(p).delete() } }
        }
        _pendingGcpGeoreference.value = null
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    fun openMbTilesViewer(layer: SpatialLayerEntity) {
        _viewingMbTilesLayer.value = layer
    }

    fun closeMbTilesViewer() {
        _viewingMbTilesLayer.value = null
    }

    fun getRasterImageBitmap(layer: SpatialLayerEntity): ImageBitmap? {
        val key = layer.localFilePath ?: layer.drawableResName ?: return null
        rasterBitmapCache[key]?.let { return it }

        val bmp = when {
            layer.localFilePath != null -> {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                    BitmapFactory.decodeFile(layer.localFilePath, opts)?.asImageBitmap()
                }.getOrNull()
            }
            layer.drawableResName != null -> {
                val resId = getApplication<Application>().resources.getIdentifier(
                    layer.drawableResName,
                    "drawable",
                    getApplication<Application>().packageName
                )
                if (resId != 0) {
                    BitmapFactory.decodeResource(getApplication<Application>().resources, resId)?.asImageBitmap()
                } else null
            }
            else -> null
        }
        if (bmp != null) rasterBitmapCache[key] = bmp
        return bmp
    }

    fun exportToUri(uri: Uri, layerId: Long?, format: ExportFormat) {
        viewModelScope.launch {
            val state = uiState.value
            val targetFeatures = if (layerId != null) {
                state.features.filter { it.layerId == layerId }
            } else {
                state.features
            }
            if (targetFeatures.isEmpty()) {
                _statusMessage.value = "Tidak ada fitur vektor untuk diekspor."
                return@launch
            }
            val title = if (layerId != null) {
                state.layers.find { it.id == layerId }?.name ?: "GeoSurvei"
            } else {
                "Semua Data GeoSurvei"
            }
            val content = withContext(Dispatchers.Default) {
                SpatialExporter.export(targetFeatures, title, format)
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                    true
                }.getOrDefault(false)
            }
            _statusMessage.value = if (ok) {
                "Ekspor ${format.label} selesai (${targetFeatures.size} fitur)."
            } else {
                "Gagal menulis file ekspor."
            }
        }
    }

    fun createShareIntent(layerId: Long?, format: ExportFormat): Intent? {
        val state = uiState.value
        val targetFeatures = if (layerId != null) {
            state.features.filter { it.layerId == layerId }
        } else {
            state.features
        }
        if (targetFeatures.isEmpty()) {
            _statusMessage.value = "Tidak ada fitur vektor untuk dibagikan."
            return null
        }
        val title = if (layerId != null) {
            state.layers.find { it.id == layerId }?.name ?: "geosurvei"
        } else {
            "geosurvei_semua"
        }
        val safeName = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "geosurvei" }
        val exportDir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, "${safeName}.${format.extension}")
        outFile.writeText(SpatialExporter.export(targetFeatures, title, format), Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            outFile
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Data Spasial GeoSurvei - $title")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun updateSearchQuery(q: String) {
        _searchQuery.value = q
    }

    fun setCategoryFilter(category: SurveyCategory?) {
        _categoryFilter.value = category
    }

    fun setCoordFormat(format: CoordFormat) {
        viewModelScope.launch { prefsRepo.setCoordFormat(format) }
    }

    fun setDistanceUnit(unit: DistanceUnit) {
        viewModelScope.launch { prefsRepo.setDistanceUnit(unit) }
    }

    fun setMapThemeMode(mode: MapThemeMode) {
        viewModelScope.launch { prefsRepo.setMapThemeMode(mode) }
    }

    fun setShowGridLines(show: Boolean) {
        viewModelScope.launch { prefsRepo.setShowGridLines(show) }
    }

    fun setShowCrosshair(show: Boolean) {
        viewModelScope.launch { prefsRepo.setShowCrosshair(show) }
    }

    private suspend fun ensureEditableLayer(): SpatialLayerEntity {
        dao.getDefaultEditableLayer()?.let { return it }
        val newId = dao.insertLayer(
            SpatialLayerEntity(
                name = "Survei Lapangan Aktif",
                sourceType = LayerSourceType.SURVEY.name,
                colorHex = "#D97706",
                isVisible = true,
                isEditable = true,
                minLat = _viewport.value.centerLat - 0.02,
                maxLat = _viewport.value.centerLat + 0.02,
                minLon = _viewport.value.centerLon - 0.02,
                maxLon = _viewport.value.centerLon + 0.02
            )
        )
        return dao.getLayerById(newId)!!
    }

    private fun pickPaletteColor(index: Int): String {
        val colors = listOf("#1B6B52", "#D97706", "#2563EB", "#7C3AED", "#0891B2", "#DC2626", "#65A30D")
        return colors[index % colors.size]
    }

    override fun onCleared() {
        super.onCleared()
        locationManager.stopUpdates()
        mbTilesReader.closeAll()
    }
}
