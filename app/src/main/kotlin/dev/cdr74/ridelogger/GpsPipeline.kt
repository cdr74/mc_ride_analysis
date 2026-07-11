package dev.cdr74.ridelogger

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fused location at max rate + GNSS status + raw GNSS measurements where supported
 * (design.md §4). All rows go into RideStore side queues; callbacks stay on the
 * main looper — rates here are ~1 Hz, trivial.
 */
class GpsPipeline(
    context: Context,
    private val store: RideStore,
    private val onFix: (Location) -> Unit = {},
) {

    private val fused: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager: LocationManager =
        context.getSystemService(LocationManager::class.java)

    @Volatile var lastFix: Location? = null
        private set
    @Volatile var satsTotal: Int = 0
        private set
    @Volatile var satsUsed: Int = 0
        private set
    var gnssRawSupported: Boolean = false
        private set

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                lastFix = loc
                store.enqueueGps(
                    RideStore.GpsFix(
                        tNs = loc.elapsedRealtimeNanos,
                        tUtcMs = loc.time,
                        lat = loc.latitude,
                        lon = loc.longitude,
                        alt = if (loc.hasAltitude()) loc.altitude else null,
                        speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                        speedAcc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else null,
                        bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                        bearingAcc = if (loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees.toDouble() else null,
                        hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        vAcc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else null,
                        provider = loc.provider,
                    ),
                )
                onFix(loc)
            }
        }
    }

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsTotal = status.satelliteCount
            satsUsed = used
            store.enqueueGpsStatus(
                RideStore.GpsStatusRow(SystemClock.elapsedRealtimeNanos(), status.satelliteCount, used),
            )
        }
    }

    private val measurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val tNs = SystemClock.elapsedRealtimeNanos()
            for (m in event.measurements) {
                store.enqueueGnssRaw(
                    RideStore.GnssRawRow(
                        tNs = tNs,
                        svid = m.svid,
                        constellation = m.constellationType,
                        cn0 = m.cn0DbHz,
                        prr = m.pseudorangeRateMetersPerSecond,
                        prrUnc = m.pseudorangeRateUncertaintyMetersPerSecond,
                    ),
                )
            }
        }
    }

    /** Caller must hold ACCESS_FINE_LOCATION (the UI gates service start on it). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0L)
            .setMinUpdateIntervalMillis(0L)
            .build()
        fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        val handler = Handler(Looper.getMainLooper())
        @Suppress("DEPRECATION") // Executor overload needs API 30; minSdk is 29
        locationManager.registerGnssStatusCallback(statusCallback, handler)
        @Suppress("DEPRECATION")
        gnssRawSupported = locationManager.registerGnssMeasurementsCallback(measurementsCallback, handler)
        return gnssRawSupported
    }

    fun stop() {
        fused.removeLocationUpdates(locationCallback)
        locationManager.unregisterGnssStatusCallback(statusCallback)
        locationManager.unregisterGnssMeasurementsCallback(measurementsCallback)
    }
}
