package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.CoordFormat
import com.example.data.local.LatLon
import com.example.data.local.MapThemeMode
import com.example.spatial.ExportFormat
import com.example.ui.GeoSurveiViewModel
import com.example.ui.components.AddEditFeatureDialog
import com.example.ui.components.CompassTargetDialog
import com.example.ui.components.FeatureDetailSheet
import com.example.ui.components.ImageGeoreferenceDialog
import com.example.ui.components.MbTilesViewerDialog
import com.example.ui.components.RasterCalibrationDialog
import com.example.ui.screens.LayersScreen
import com.example.ui.screens.MapWorkspaceScreen
import com.example.ui.screens.SurveyDataScreen
import com.example.ui.screens.ToolsExportScreen
import com.example.ui.theme.GeoSurveiTheme

const val ROUTE_MAP = "map"
const val ROUTE_LAYERS = "layers"
const val ROUTE_SURVEY = "survey"
const val ROUTE_TOOLS = "tools"

private data class NavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val TopLevelDestinations = listOf(
    NavDestination(ROUTE_MAP, R.string.nav_map, Icons.Filled.Map, Icons.Outlined.Map, "nav_map"),
    NavDestination(ROUTE_LAYERS, R.string.nav_layers, Icons.Filled.Layers, Icons.Outlined.Layers, "nav_layers"),
    NavDestination(ROUTE_SURVEY, R.string.nav_survey, Icons.Filled.ListAlt, Icons.Outlined.ListAlt, "nav_survey"),
    NavDestination(ROUTE_TOOLS, R.string.nav_tools, Icons.Filled.Build, Icons.Outlined.Build, "nav_tools")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GeoSurveiApp()
        }
    }
}

@Composable
fun GeoSurveiApp(viewModel: GeoSurveiViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_MAP

    val isDark = when (state.prefs.mapThemeMode) {
        MapThemeMode.SYSTEM -> isSystemInDarkTheme()
        MapThemeMode.LIGHT -> false
        MapThemeMode.DARK -> true
    }

    // Location permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.startLocationServices()
            viewModel.showMessage("GPS lapangan diaktifkan.")
        } else {
            viewModel.showMessage("Izin GPS ditolak. Mode survei manual dengan crosshair peta tetap tersedia.")
        }
    }

    // Spatial file import launcher (GeoJSON, KML, KMZ, GPX, CSV, MBTiles, Raster Images)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importSpatialFiles(uris)
        }
    }

    // Zero-permission Photo Picker for local image georeferencing (Control Points)
    val pickGeorefImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.startLocalImageGeoreferencing(uri)
        }
    }
    val launchGeorefImagePicker = {
        pickGeorefImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // Pending export state for CreateDocument SAF contract
    var pendingExportFormat by remember { mutableStateOf(ExportFormat.GEOJSON) }
    var pendingExportLayerId by remember { mutableStateOf<Long?>(null) }

    val createExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            viewModel.exportToUri(uri, pendingExportLayerId, pendingExportFormat)
        }
    }

    // Lifecycle start/stop for GPS and compass sensor
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.startLocationServices()
                Lifecycle.Event.ON_STOP -> viewModel.stopLocationServices()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request location on first entry if not granted yet
    LaunchedEffect(Unit) {
        if (!viewModel.locationManager.hasLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.clearStatusMessage()
        }
    }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    GeoSurveiTheme(darkTheme = isDark) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                contentWindowInsets = WindowInsets(0),
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.navigationBarsPadding()
                    )
                },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            TopLevelDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTo(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(
                            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
                        ) {
                            Spacer(Modifier.height(12.dp))
                            TopLevelDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTo(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag(dest.testTag)
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_MAP,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(ROUTE_MAP) {
                            MapWorkspaceScreen(
                                state = state,
                                isDarkMap = isDark,
                                mbTilesReader = viewModel.mbTilesReader,
                                getRasterBitmap = viewModel::getRasterImageBitmap,
                                onRequestLocationPermission = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                onImportClick = { importLauncher.launch(arrayOf("*/*")) },
                                onPanZoom = viewModel::panAndZoomBy,
                                onZoomIn = viewModel::zoomIn,
                                onZoomOut = viewModel::zoomOut,
                                onCenterGps = viewModel::centerOnGpsOrRequest,
                                onCycleCoordFormat = {
                                    val formats = CoordFormat.entries
                                    val next = formats[(formats.indexOf(state.prefs.coordFormat) + 1) % formats.size]
                                    viewModel.setCoordFormat(next)
                                },
                                onSelectToolMode = viewModel::setToolMode,
                                onAddDraftVertex = viewModel::addDraftVertex,
                                onUndoDraftVertex = viewModel::undoDraftVertex,
                                onClearDraft = viewModel::clearDraftVertices,
                                onSaveDraftFeature = viewModel::saveDraftAsFeature,
                                onOpenAddWaypoint = viewModel::openAddWaypointDialog,
                                onOpenAddWaypointAtCoord = viewModel::openAddWaypointAtCoord,
                                onStartTrack = viewModel::startTrackRecording,
                                onAddManualTrackPoint = viewModel::addManualTrackPointAtCenter,
                                onTogglePauseTrack = viewModel::togglePauseTrack,
                                onSaveTrack = viewModel::stopAndSaveTrack,
                                onDiscardTrack = viewModel::discardTrack,
                                onSelectFeature = viewModel::selectFeature,
                                onOpenCompass = viewModel::openCompassTarget,
                                onToggleLayerVisibility = viewModel::toggleLayerVisibility,
                                onSetLayersVisibility = viewModel::setLayersVisibility,
                                onZoomToLayer = viewModel::zoomToLayer,
                                onGeorefImageClick = launchGeorefImagePicker
                            )
                        }

                        composable(ROUTE_LAYERS) {
                            LayersScreen(
                                layers = state.layers,
                                features = state.features,
                                tileCountsByLayer = state.tileCountsByLayer,
                                isImporting = state.isImporting,
                                onImportSpatialClick = { importLauncher.launch(arrayOf("*/*")) },
                                onGeoreferenceImageClick = launchGeorefImagePicker,
                                onToggleVisibility = viewModel::toggleLayerVisibility,
                                onChangeColor = viewModel::updateLayerColor,
                                onZoomToLayer = { layer ->
                                    viewModel.zoomToLayer(layer)
                                    navigateTo(ROUTE_MAP)
                                },
                                onOpenTileViewer = viewModel::openMbTilesViewer,
                                onShareLayer = { layer, format ->
                                    viewModel.createShareIntent(layer.id, format)?.let { intent ->
                                        context.startActivity(
                                            android.content.Intent.createChooser(intent, "Bagikan ${layer.name}")
                                        )
                                    }
                                },
                                onDeleteLayer = viewModel::deleteLayer
                            )
                        }

                        composable(ROUTE_SURVEY) {
                            val origin = if (state.gpsState.hasFix && state.gpsState.latitude != null && state.gpsState.longitude != null) {
                                LatLon(state.gpsState.latitude!!, state.gpsState.longitude!!)
                            } else {
                                LatLon(state.viewport.centerLat, state.viewport.centerLon)
                            }
                            SurveyDataScreen(
                                features = state.features,
                                layers = state.layers,
                                searchQuery = state.searchQuery,
                                selectedCategory = state.selectedCategoryFilter,
                                coordFormat = state.prefs.coordFormat,
                                distanceUnit = state.prefs.distanceUnit,
                                referenceOrigin = origin,
                                onSearchChange = viewModel::updateSearchQuery,
                                onCategoryFilterChange = viewModel::setCategoryFilter,
                                onZoomToFeature = { feat ->
                                    viewModel.zoomToFeature(feat)
                                    navigateTo(ROUTE_MAP)
                                },
                                onOpenCompass = viewModel::openCompassTarget,
                                onOpenDetail = viewModel::selectFeature,
                                onAddNewWaypoint = { viewModel.openAddWaypointDialog(state.gpsState.hasFix) }
                            )
                        }

                        composable(ROUTE_TOOLS) {
                            ToolsExportScreen(
                                prefs = state.prefs,
                                featureCount = state.features.size,
                                layerCount = state.layers.size,
                                onSetCoordFormat = viewModel::setCoordFormat,
                                onSetDistanceUnit = viewModel::setDistanceUnit,
                                onSetThemeMode = viewModel::setMapThemeMode,
                                onSetShowGrid = viewModel::setShowGridLines,
                                onSetShowCrosshair = viewModel::setShowCrosshair,
                                onSaveExportFile = { fmt ->
                                    pendingExportFormat = fmt
                                    pendingExportLayerId = null
                                    createExportLauncher.launch("geosurvei_export.${fmt.extension}")
                                },
                                onShareExport = { fmt ->
                                    viewModel.createShareIntent(null, fmt)?.let { intent ->
                                        context.startActivity(
                                            android.content.Intent.createChooser(intent, "Bagikan Data Survei")
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Modal Dialogs & Bottom Sheets
            if (state.showAddWaypointDialog) {
                val initCoord = state.waypointInitialCoord ?: LatLon(state.viewport.centerLat, state.viewport.centerLon)
                AddEditFeatureDialog(
                    initialCoord = initCoord,
                    editingFeature = state.editingFeature,
                    onDismiss = viewModel::dismissWaypointDialog,
                    onSave = viewModel::saveWaypointOrFeature
                )
            }

            state.selectedFeature?.let { selected ->
                val layerName = state.layers.find { it.id == selected.layerId }?.name ?: "Layer Survei"
                FeatureDetailSheet(
                    feature = selected,
                    layerName = layerName,
                    coordFormat = state.prefs.coordFormat,
                    distanceUnit = state.prefs.distanceUnit,
                    onDismiss = { viewModel.selectFeature(null) },
                    onNavigateCompass = {
                        viewModel.openCompassTarget(selected)
                    },
                    onEdit = {
                        viewModel.selectFeature(null)
                        viewModel.openEditFeatureDialog(selected)
                    },
                    onDelete = {
                        viewModel.deleteFeature(selected)
                    }
                )
            }

            state.compassTargetFeature?.let { target ->
                val origin = if (state.gpsState.hasFix && state.gpsState.latitude != null && state.gpsState.longitude != null) {
                    LatLon(state.gpsState.latitude!!, state.gpsState.longitude!!)
                } else {
                    LatLon(state.viewport.centerLat, state.viewport.centerLon)
                }
                CompassTargetDialog(
                    target = target,
                    currentOrigin = origin,
                    isFromGps = state.gpsState.hasFix,
                    deviceAzimuthDeg = state.compassAzimuthDeg,
                    distanceUnit = state.prefs.distanceUnit,
                    onDismiss = { viewModel.openCompassTarget(null) }
                )
            }

            state.pendingRasterCalibration?.let { pending ->
                RasterCalibrationDialog(
                    pending = pending,
                    onCancel = viewModel::cancelRasterCalibration,
                    onSelectPdfPage = viewModel::selectPdfPage,
                    onSwitchToGcp = viewModel::openGcpFromRasterCalibration,
                    onConfirm = viewModel::confirmRasterCalibration
                )
            }

            state.pendingImageGeoreference?.let { pendingGcp ->
                ImageGeoreferenceDialog(
                    pending = pendingGcp,
                    initialViewport = state.viewport,
                    layers = state.layers,
                    features = state.features,
                    gpsState = state.gpsState,
                    isDarkMap = isDark,
                    isSaving = state.isImporting,
                    mbTilesReader = viewModel.mbTilesReader,
                    getRasterBitmap = viewModel::getRasterImageBitmap,
                    onCancel = viewModel::cancelImageGeoreferencing,
                    onConfirm = viewModel::confirmImageGeoreferencing
                )
            }

            state.viewingMbTilesLayer?.let { tileLayer ->
                MbTilesViewerDialog(
                    layer = tileLayer,
                    mapTileDao = viewModel.mapTileDao,
                    mbTilesReader = viewModel.mbTilesReader,
                    isDarkMap = isDark,
                    onZoomToOnMainMap = { target ->
                        viewModel.closeMbTilesViewer()
                        viewModel.zoomToLayer(target)
                        navigateTo(ROUTE_MAP)
                    },
                    onDismiss = viewModel::closeMbTilesViewer
                )
            }
        }
    }
}
