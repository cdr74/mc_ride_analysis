package dev.cdr74.ridelogger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pre-ride readiness (docs/ui-mockup.md S1, Q8): the home screen shows "Initializing…"
 * until everything the ride needs is available, then the START button appears — the
 * button appearing IS the ready signal. Anything the app cannot access produces a
 * blocking issue with a concrete fix action.
 *
 * Runs only while the app is idle in the foreground (started/stopped by the Activity);
 * costs one location listener and a 1.5 s sensor-rate probe per resume.
 */
class Preflight(private val context: Context) {

    sealed interface State {
        /** Something is still warming up; list is what we're waiting for. */
        data class Initializing(val waitingFor: List<String>) : State

        /** All checks green — ride can start. */
        data object Ready : State

        /** The app cannot access what it needs; each issue says how to fix it. */
        data class Blocked(val issues: List<Issue>) : State
    }

    data class Issue(val title: String, val detail: String, val action: Action)

    enum class Action { REQUEST_PERMISSIONS, OPEN_LOCATION_SETTINGS, OPEN_PRIVACY_SETTINGS, NONE }

    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow<State>(State.Initializing(listOf("checks")))

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    private var hasFix = false
    private var probeDone = false
    private var probeCount = 0
    private var probeStartNs = 0L
    private var rateCapped = false
    private var running = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (result.locations.isNotEmpty()) {
                hasFix = true
                recompute()
            }
        }
    }

    private val probeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (probeStartNs == 0L) probeStartNs = event.timestamp
            probeCount++
            val elapsedNs = event.timestamp - probeStartNs
            if (elapsedNs >= PROBE_WINDOW_NS) {
                val hz = probeCount * 1e9 / elapsedNs
                val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val expectedHz = if (accel != null && accel.minDelay > 0) 1e6 / accel.minDelay else hz
                // Android 12+ mic privacy toggle silently caps all sensors at 200 Hz
                // (DESIGN.md §11) — detectable as measured ≪ what the sensor advertises.
                rateCapped = expectedHz > 300 && hz < Config.LOW_RATE_WARN_HZ
                probeDone = true
                sensorManager.unregisterListener(this)
                recompute()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun permissionsGranted(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** (Re)start all checks. Safe to call repeatedly (onResume, after permission grants). */
    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        running = true
        hasFix = false
        probeDone = false
        probeCount = 0
        probeStartNs = 0L
        rateCapped = false

        if (permissionsGranted()) {
            fused.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(),
                locationCallback,
                Looper.getMainLooper(),
            )
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(probeListener, it, SensorManager.SENSOR_DELAY_FASTEST)
        } ?: run { probeDone = true }
        recompute()
    }

    fun stop() {
        if (!running) return
        running = false
        fused.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(probeListener)
    }

    private fun recompute() {
        val issues = mutableListOf<Issue>()

        if (!permissionsGranted()) {
            issues += Issue(
                title = "Permissions missing",
                detail = "RideLogger needs precise location (GPS speed and position are " +
                    "part of every recording) and notifications (the recording status). " +
                    "Grant both, with location set to \"Precise\".",
                action = Action.REQUEST_PERMISSIONS,
            )
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) {
            issues += Issue(
                title = "Location is off",
                detail = "Turn on device location — without GPS there is no speed, no " +
                    "calibration and no ride data worth keeping.",
                action = Action.OPEN_LOCATION_SETTINGS,
            )
        }
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null
        ) {
            issues += Issue(
                title = "Motion sensors unavailable",
                detail = "This device reports no accelerometer or gyroscope — RideLogger " +
                    "cannot work on it.",
                action = Action.NONE,
            )
        }
        if (probeDone && rateCapped) {
            issues += Issue(
                title = "Sensor rate capped at 200 Hz",
                detail = "The system microphone privacy toggle is off, which also caps " +
                    "all motion sensors. Enable microphone access under Privacy controls, " +
                    "then come back.",
                action = Action.OPEN_PRIVACY_SETTINGS,
            )
        }

        _state.value = when {
            issues.isNotEmpty() -> State.Blocked(issues)
            else -> {
                val waiting = buildList {
                    if (!hasFix) add("GPS fix")
                    if (!probeDone) add("sensors")
                }
                if (waiting.isEmpty()) State.Ready else State.Initializing(waiting)
            }
        }
    }

    companion object {
        private const val PROBE_WINDOW_NS = 1_500_000_000L

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
