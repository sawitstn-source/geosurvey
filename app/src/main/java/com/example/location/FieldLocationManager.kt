package com.example.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.data.local.LiveGpsState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class FieldLocationManager(private val context: Context) {

    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val systemLocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }
    private val sensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    private val _gpsState = MutableStateFlow(LiveGpsState())
    val gpsState: StateFlow<LiveGpsState> = _gpsState.asStateFlow()

    private val _compassAzimuthDeg = MutableStateFlow(0f)
    val compassAzimuthDeg: StateFlow<Float> = _compassAzimuthDeg.asStateFlow()

    private var isLocationRunning = false
    private var isCompassRunning = false

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { updateLocation(it, "GPS Fused") }
        }
    }

    private val nativeListener = LocationListener { location ->
        updateLocation(location, location.provider?.uppercase() ?: "GPS")
    }

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasMag = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotMatrix, orientation)
                    val azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                    smoothAzimuth(azimuth)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                    hasGravity = true
                    computeFallbackOrientation()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                    hasMag = true
                    computeFallbackOrientation()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun computeFallbackOrientation() {
        if (!hasGravity || !hasMag) return
        val r = FloatArray(9)
        val i = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(r, orientation)
            val azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            smoothAzimuth(azimuth)
        }
    }

    private fun smoothAzimuth(target: Float) {
        val current = _compassAzimuthDeg.value
        var diff = target - current
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        if (abs(diff) > 0.8f) {
            _compassAzimuthDeg.value = ((current + diff * 0.25f) + 360f) % 360f
        }
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun startUpdates() {
        val granted = hasLocationPermission()
        _gpsState.value = _gpsState.value.copy(
            isPermissionGranted = granted,
            provider = if (granted) "Mencari satelit GPS..." else "Izin lokasi belum aktif"
        )
        startCompass()

        if (!granted || isLocationRunning) return
        isLocationRunning = true

        runCatching {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) updateLocation(loc, "GPS Cache")
            }
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
                .setMinUpdateIntervalMillis(800L)
                .setMinUpdateDistanceMeters(1.0f)
                .build()
            fusedClient.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
        }

        // Also register native GPS provider as offline hardware fallback
        runCatching {
            val lm = systemLocationManager ?: return@runCatching
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    1.5f,
                    nativeListener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    fun stopUpdates() {
        if (isLocationRunning) {
            isLocationRunning = false
            runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
            runCatching { systemLocationManager?.removeUpdates(nativeListener) }
        }
        stopCompass()
    }

    private fun startCompass() {
        if (isCompassRunning) return
        val sm = sensorManager ?: return
        isCompassRunning = true
        val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor != null) {
            sm.registerListener(sensorListener, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun stopCompass() {
        if (!isCompassRunning) return
        isCompassRunning = false
        sensorManager?.unregisterListener(sensorListener)
    }

    private fun updateLocation(location: Location, providerLabel: String) {
        _gpsState.value = LiveGpsState(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            altitudeMeters = if (location.hasAltitude()) location.altitude else null,
            speedMps = if (location.hasSpeed()) location.speed else null,
            bearingDeg = if (location.hasBearing()) location.bearing else null,
            provider = providerLabel,
            timestampMs = location.time,
            hasFix = true,
            isPermissionGranted = true
        )
    }
}
