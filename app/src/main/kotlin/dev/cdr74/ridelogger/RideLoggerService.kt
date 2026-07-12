package dev.cdr74.ridelogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GpsUiStatus(
    val hasFix: Boolean,
    val hAccM: Float?,
    val speedMps: Float?,
    val satsUsed: Int,
    val satsTotal: Int,
)

data class SessionStatus(
    val running: Boolean = false,
    val rideFileName: String? = null,
    val elapsedMs: Long = 0,
    val ratesHz: List<Double> = List(Config.STREAM_COUNT) { 0.0 },
    val drops: Long = 0,
    val gps: GpsUiStatus? = null,
    val fileBytes: Long = 0,
)

/**
 * Foreground service (type=location) owning the whole logging session
 * (design.md §6): wakelock, ride file, pipelines, writer coroutine, notification.
 */
class RideLoggerService : Service() {

    private var scope: CoroutineScope? = null
    private var ring: RingBuffer? = null
    private var store: RideStore? = null
    private var sensors: SensorPipeline? = null
    private var gps: GpsPipeline? = null
    private var writerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoCalibrator: AutoCalibrator? = null
    private var startElapsedMs = 0L
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (store == null) startLogging()
            ACTION_STOP -> stopLogging()
            // START_STICKY restart after a kill: never resume the old file (§6.5).
            null -> stopSelf()
        }
        return START_STICKY
    }

    private fun startLogging() {
        val nowUtcMs = System.currentTimeMillis()
        startElapsedMs = SystemClock.elapsedRealtime()
        stopping = false

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RideLogger::recording").also {
            it.acquire(12 * 60 * 60 * 1000L) // safety cap well above any ride length
        }

        val ring = RingBuffer().also { this.ring = it }
        val store = RideStore.create(this, ring, nowUtcMs).also { this.store = it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { this.scope = it }

        // Meta + first clock anchor before any sensor row (design.md §5.3).
        store.putMeta("schema_version", Config.SCHEMA_VERSION.toString())
        store.putMeta("app_version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        store.putMeta("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        store.putMeta("ride_start_utc_ms", nowUtcMs.toString())
        store.putMeta("clock_anchor", clockAnchorJson())
        store.putMeta("mount", Config.MOUNT_DESCRIPTION)

        // Live estimator chain (ui-mockup S2, ADR 0005): causal fused lean + accel +
        // pitch, calibration persisted across rides and re-solved opportunistically.
        // None of this touches the logging path — raw data stays authoritative.
        val estimator = LeanEstimator(
            onOutput = { out ->
                live.update { m ->
                    m.copy(
                        lean = out.leanDeg?.let { m.lean.update(it) } ?: m.lean.copy(value = null),
                        accel = out.accelMs2?.let { m.accel.update(it) } ?: m.accel.copy(value = null),
                        pitch = out.pitchDeg?.let { m.pitch.update(it) } ?: m.pitch.copy(value = null),
                    )
                }
            },
            onStepSample = { acc, gyr ->
                autoCalibrator?.onSample(acc[0], acc[1], acc[2], gyr[0], gyr[1], gyr[2])
            },
        )
        autoCalibrator = AutoCalibrator { r, windows, events ->
            CalibrationStore.save(this, r, windows, events)
            estimator.setCalibration(r)
            live.update { it.copy(calibrated = true) }
        }
        val storedCalibration = CalibrationStore.load(this)
        if (storedCalibration != null) estimator.setCalibration(storedCalibration)
        live.value = LiveMetrics(calibrated = storedCalibration != null)

        val sensors = SensorPipeline(
            getSystemService(Context.SENSOR_SERVICE) as SensorManager, ring,
            tap = object : SensorPipeline.Tap {
                override fun onAccel(tNs: Long, x: Float, y: Float, z: Float) =
                    estimator.onAccel(tNs, x, y, z)
                override fun onGyro(tNs: Long, x: Float, y: Float, z: Float) =
                    estimator.onGyro(tNs, x, y, z)
            },
        ).also { this.sensors = it }
        val streamInfo = sensors.start()

        val gps = GpsPipeline(this, store, onFix = { loc ->
            val speed = if (loc.hasSpeed()) loc.speed else null
            estimator.onGpsFix(speed)
            autoCalibrator?.onGpsFix(
                loc.elapsedRealtimeNanos, speed,
                if (loc.hasBearing()) loc.bearing else null,
            )
            if (speed != null) {
                live.update { it.copy(speed = it.speed.update(speed * 3.6f)) }
            }
        }).also { this.gps = it }
        val rawSupported = gps.start()
        store.putMeta("gnss_raw_supported", rawSupported.toString())

        writerJob = store.startWriter(CoroutineScope(scope.coroutineContext + Dispatchers.IO))

        startForeground(
            NOTIFICATION_ID, buildNotification("starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        // Measured delivered rate over the first 10 s -> meta (design.md §3.1).
        scope.launch {
            val c0 = sensors.countsSnapshot()
            delay(Config.RATE_MEASURE_WINDOW_MS)
            val c1 = sensors.countsSnapshot()
            for (info in streamInfo) {
                val hz = (c1[info.stream] - c0[info.stream]) * 1000.0 / Config.RATE_MEASURE_WINDOW_MS
                val variant = if (info.uncalibrated) "uncalibrated" else "calibrated"
                store.putMeta(
                    "sensor_${Config.STREAM_NAMES[info.stream]}",
                    "${info.sensorName} ($variant), requested ${info.requested}, measured ${"%.1f".format(hz)} Hz",
                )
            }
        }

        // 1 Hz status for the UI; notification refreshed every 5 s.
        scope.launch {
            var prev = sensors.countsSnapshot()
            var prevT = SystemClock.elapsedRealtime()
            var tick = 0
            while (isActive) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val cur = sensors.countsSnapshot()
                val dt = (now - prevT).coerceAtLeast(1)
                val rates = List(Config.STREAM_COUNT) { (cur[it] - prev[it]) * 1000.0 / dt }
                prev = cur
                prevT = now
                val fix = gps.lastFix
                status.value = SessionStatus(
                    running = true,
                    rideFileName = store.file?.name,
                    elapsedMs = now - startElapsedMs,
                    ratesHz = rates,
                    drops = ring.totalDrops(),
                    gps = GpsUiStatus(
                        hasFix = fix != null,
                        hAccM = fix?.takeIf { it.hasAccuracy() }?.accuracy,
                        speedMps = fix?.takeIf { it.hasSpeed() }?.speed,
                        satsUsed = gps.satsUsed,
                        satsTotal = gps.satsTotal,
                    ),
                    fileBytes = store.fileBytes(),
                )
                if (++tick % 5 == 0) {
                    val el = (now - startElapsedMs) / 1000
                    notify(buildNotification("%d:%02d:%02d · drops %d".format(el / 3600, el / 60 % 60, el % 60, ring.totalDrops())))
                }
            }
        }
    }

    private fun stopLogging() {
        val store = this.store ?: run { stopSelf(); return }
        if (stopping) return
        stopping = true

        sensors?.stop()
        gps?.stop()

        // Orderly close (design.md §6.4) off the main thread, then tear the service down.
        val closer = CoroutineScope(Dispatchers.IO)
        closer.launch {
            writerJob?.cancelAndJoin()
            scope?.cancel()
            store.drainAll()
            store.writeDropCounts()
            store.putMeta("clock_anchor_stop", clockAnchorJson())
            store.putMeta("ride_end_utc_ms", System.currentTimeMillis().toString())
            store.putMeta("clean_close", "true")
            store.close()
            withContext(Dispatchers.Main) {
                status.value = SessionStatus(running = false)
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                this@RideLoggerService.store = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        // Belt and braces: if the system destroys us mid-run, release what we can.
        // Data safety comes from WAL, not from this path.
        scope?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun clockAnchorJson(): String {
        val gpsMs = gps?.lastFix?.time
        return JSONObject()
            .put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("utc_ms", System.currentTimeMillis())
            .put("gps_utc_ms", gpsMs ?: JSONObject.NULL)
            .toString()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ride logging", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("RideLogger recording")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)

    companion object {
        const val ACTION_START = "dev.cdr74.ridelogger.START"
        const val ACTION_STOP = "dev.cdr74.ridelogger.STOP"
        private const val CHANNEL_ID = "ride_logging"
        private const val NOTIFICATION_ID = 1

        /** UI observes this; static because the app is deliberately DI-free (CLAUDE.md). */
        val status: MutableStateFlow<SessionStatus> = MutableStateFlow(SessionStatus())

        /** Live bar values + session watermarks for the ride display (ui-mockup S2). */
        val live: MutableStateFlow<LiveMetrics> = MutableStateFlow(LiveMetrics())

        fun intent(context: Context, action: String): Intent =
            Intent(context, RideLoggerService::class.java).setAction(action)
    }
}
