package dev.cdr74.ridelogger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Post-ride analysis (ui-mockup S3, ADR 0005): replays a closed ride file through the
 * same causal estimator that drives the live display, and produces per-dimension
 * traces (~10 Hz) plus summary stats. Runs once per ride, cached as a JSON sidecar
 * (`<ride>.db.analysis.json`) — the ride file itself is never touched (CLAUDE.md rule 4).
 *
 * Two passes: pass 1 runs the AutoCalibrator over the whole ride (best possible R for
 * this exact ride; falls back to the persisted calibration if the ride yields no
 * solve), pass 2 computes the traces with that R.
 */
object RideAnalyzer {

    class Trace(
        /** elapsed seconds from ride start for each sample (uniform ~0.1 s for IMU dims) */
        val t: FloatArray,
        /** value per sample; NaN = not produced (lean below cutoff, uncalibrated) */
        val v: FloatArray,
    )

    class Analysis(
        val durationS: Float,
        val distanceKm: Float,
        val maxSpeedKmh: Float,
        val avgMovingKmh: Float,
        val calibrated: Boolean,
        val speed: Trace,
        val lean: Trace?,
        val accel: Trace?,
        val pitch: Trace?,
    ) {
        fun trace(dim: Dimension): Trace? = when (dim) {
            Dimension.SPEED -> speed
            Dimension.LEAN -> lean
            Dimension.ACCEL -> accel
            Dimension.PITCH -> pitch
        }
    }

    private fun cacheFile(ride: File) = File(ride.parentFile, ride.name + ".analysis.json")

    /**
     * Load the cached analysis or compute it. A 60-min ride is ~3 M IMU rows, so this
     * takes tens of seconds on first open: call off the main thread, report progress
     * (0..1 across both passes), and it cooperatively cancels with the caller's
     * coroutine (leaving the screen aborts the work; nothing partial is cached).
     */
    suspend fun get(context: Context, ride: File, onProgress: (Float) -> Unit = {}): Analysis {
        val cache = cacheFile(ride)
        if (cache.exists()) {
            runCatching { return fromJson(JSONObject(cache.readText())) }
            // unreadable/stale cache falls through to recompute
        }
        val ctx = currentCoroutineContext()
        val analysis = analyze(context, ride, onProgress) { ctx.ensureActive() }
        runCatching { cache.writeText(toJson(analysis).toString()) }
        return analysis
    }

    private fun analyze(
        context: Context,
        ride: File,
        onProgress: (Float) -> Unit,
        checkActive: () -> Unit,
    ): Analysis {
        // pass 1: best calibration for this ride — raw sample means between GPS fixes,
        // decimated ×4 (matches the analysis-side reference, calibrate.py, which also
        // averages unfiltered samples; the ≥5 s window means don't need full rate)
        var solved: FloatArray? = null
        val calibrator = AutoCalibrator { r, _, _ -> solved = r }
        var decim = 0
        val lastGyr = FloatArray(3)
        replay(
            ride,
            onImu = { stream, _, x, y, z ->
                if (stream == Config.STREAM_GYRO) {
                    lastGyr[0] = x; lastGyr[1] = y; lastGyr[2] = z
                } else if (++decim % 4 == 0) {
                    calibrator.onSample(
                        x.toDouble(), y.toDouble(), z.toDouble(),
                        lastGyr[0].toDouble(), lastGyr[1].toDouble(), lastGyr[2].toDouble(),
                    )
                }
            },
            onGps = { t, speed, bearing -> calibrator.onGpsFix(t, speed, bearing) },
            onProgress = { onProgress(it * 0.4f) },
            checkActive = checkActive,
        )
        val r = solved ?: CalibrationStore.load(context)
        if (solved != null) CalibrationStore.save(context, solved!!, 0, 0)

        // pass 2: traces through the causal estimator
        val tOut = ArrayList<Float>(20_000)
        val lean = ArrayList<Float>(20_000)
        val accel = ArrayList<Float>(20_000)
        val pitch = ArrayList<Float>(20_000)
        var t0Ns = -1L
        val est = LeanEstimator(onOutput = { out ->
            if (t0Ns < 0) t0Ns = out.tNs
            tOut.add((out.tNs - t0Ns) / 1e9f)
            lean.add(out.leanDeg ?: Float.NaN)
            accel.add(out.accelMs2 ?: Float.NaN)
            pitch.add(out.pitchDeg ?: Float.NaN)
        })
        if (r != null) est.setCalibration(r)
        val gpsT = ArrayList<Float>(4000)
        val gpsV = ArrayList<Float>(4000)
        var gpsT0 = -1L
        replay(
            ride,
            onImu = { stream, t, x, y, z ->
                if (stream == Config.STREAM_ACCEL) est.onAccel(t, x, y, z) else est.onGyro(t, x, y, z)
            },
            onGps = { t, speed, _ ->
                est.onGpsFix(t, speed)
                if (speed != null) {
                    if (gpsT0 < 0) gpsT0 = t
                    gpsT.add((t - gpsT0) / 1e9f)
                    gpsV.add(speed * 3.6f)
                }
            },
            onProgress = { onProgress(0.4f + it * 0.6f) },
            checkActive = checkActive,
        )
        val gpsTrace = Trace(gpsT.toFloatArray(), gpsV.toFloatArray())

        // speed stats from the GPS trace
        var dist = 0.0
        var moving = 0.0
        var maxV = 0f
        for (i in 1 until gpsTrace.t.size) {
            val dt = min(2f, gpsTrace.t[i] - gpsTrace.t[i - 1])
            val v = gpsTrace.v[i - 1] / 3.6f // km/h → m/s
            if (v > 1f) {
                dist += v * dt
                moving += dt.toDouble()
            }
            maxV = max(maxV, gpsTrace.v[i])
        }
        val dur = if (gpsTrace.t.isNotEmpty()) gpsTrace.t.last() else 0f

        return Analysis(
            durationS = dur,
            distanceKm = (dist / 1000).toFloat(),
            maxSpeedKmh = maxV,
            avgMovingKmh = if (moving > 1) (dist / moving * 3.6).toFloat() else 0f,
            calibrated = r != null,
            speed = gpsTrace,
            lean = if (r != null) Trace(tOut.toFloatArray(), lean.toFloatArray()) else null,
            accel = if (r != null) Trace(tOut.toFloatArray(), accel.toFloatArray()) else null,
            pitch = if (r != null) Trace(tOut.toFloatArray(), pitch.toFloatArray()) else null,
        )
    }

    /**
     * Streams bias-corrected accel/gyro rows and GPS fixes to the callbacks in time
     * order. Uses one indexed cursor PER stream and merges them in code — the
     * idx_imu(stream, t_ns) index cannot serve `WHERE stream IN (0,1) ORDER BY t_ns`,
     * and asking SQLite to externally sort millions of rows on flash is what froze
     * the first post-ride open of a long ride.
     */
    private fun replay(
        ride: File,
        onImu: (stream: Int, tNs: Long, x: Float, y: Float, z: Float) -> Unit,
        onGps: (tNs: Long, speedMps: Float?, bearingDeg: Float?) -> Unit,
        onProgress: (Float) -> Unit,
        checkActive: () -> Unit,
    ) {
        val db = SQLiteDatabase.openDatabase(ride.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            val bounds = it.rawQuery("SELECT MIN(t_ns), MAX(t_ns) FROM imu WHERE stream=0", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else return }
            val spanNs = (bounds.second - bounds.first).coerceAtLeast(1)

            val gps = ArrayList<Triple<Long, Float?, Float?>>(4000)
            it.rawQuery("SELECT t_ns, speed, bearing FROM gps ORDER BY t_ns", null).use { c ->
                while (c.moveToNext()) {
                    gps.add(
                        Triple(
                            c.getLong(0),
                            if (c.isNull(1)) null else c.getFloat(1),
                            if (c.isNull(2)) null else c.getFloat(2),
                        ),
                    )
                }
            }
            var gi = 0

            val sql = "SELECT t_ns, v0, v1, v2, b0, b1, b2 FROM imu WHERE stream=%d ORDER BY t_ns"
            it.rawQuery(sql.format(Config.STREAM_ACCEL), null).use { ca ->
                it.rawQuery(sql.format(Config.STREAM_GYRO), null).use { cg ->
                    var hasA = ca.moveToNext()
                    var hasG = cg.moveToNext()
                    var rows = 0
                    fun emit(c: android.database.Cursor, stream: Int) {
                        val t = c.getLong(0)
                        while (gi < gps.size && gps[gi].first <= t) {
                            val (gt, speed, bearing) = gps[gi]
                            onGps(gt, speed, bearing)
                            gi++
                        }
                        val bx = if (c.isNull(4)) 0f else c.getFloat(4)
                        val by = if (c.isNull(5)) 0f else c.getFloat(5)
                        val bz = if (c.isNull(6)) 0f else c.getFloat(6)
                        onImu(stream, t, c.getFloat(1) - bx, c.getFloat(2) - by, c.getFloat(3) - bz)
                        if (++rows % 100_000 == 0) {
                            checkActive()
                            onProgress(((t - bounds.first).toFloat() / spanNs).coerceIn(0f, 1f))
                        }
                    }
                    while (hasA || hasG) {
                        if (hasA && (!hasG || ca.getLong(0) <= cg.getLong(0))) {
                            emit(ca, Config.STREAM_ACCEL)
                            hasA = ca.moveToNext()
                        } else {
                            emit(cg, Config.STREAM_GYRO)
                            hasG = cg.moveToNext()
                        }
                    }
                }
            }
        }
        onProgress(1f)
    }

    // --- JSON cache (sidecar file, version-gated)

    // v2: GPS-aided pitch fix (phantom +46° under acceleration) — recompute old caches
    private const val CACHE_VERSION = 2

    private fun toJson(a: Analysis): JSONObject = JSONObject().apply {
        put("version", CACHE_VERSION)
        put("duration_s", a.durationS)
        put("distance_km", a.distanceKm)
        put("max_speed_kmh", a.maxSpeedKmh)
        put("avg_moving_kmh", a.avgMovingKmh)
        put("calibrated", a.calibrated)
        put("speed", trace(a.speed))
        a.lean?.let { put("lean", trace(it)) }
        a.accel?.let { put("accel", trace(it)) }
        a.pitch?.let { put("pitch", trace(it)) }
    }

    private fun trace(t: Trace): JSONObject = JSONObject().apply {
        put("t", JSONArray().apply { t.t.forEach { put(it.toDouble()) } })
        put("v", JSONArray().apply { t.v.forEach { put(if (it.isNaN()) JSONObject.NULL else it.toDouble()) } })
    }

    private fun fromJson(o: JSONObject): Analysis {
        require(o.getInt("version") == CACHE_VERSION) { "stale cache" }
        fun trace(key: String): Trace? {
            val t = o.optJSONObject(key) ?: return null
            val ts = t.getJSONArray("t")
            val vs = t.getJSONArray("v")
            return Trace(
                FloatArray(ts.length()) { ts.getDouble(it).toFloat() },
                FloatArray(vs.length()) { if (vs.isNull(it)) Float.NaN else vs.getDouble(it).toFloat() },
            )
        }
        return Analysis(
            durationS = o.getDouble("duration_s").toFloat(),
            distanceKm = o.getDouble("distance_km").toFloat(),
            maxSpeedKmh = o.getDouble("max_speed_kmh").toFloat(),
            avgMovingKmh = o.getDouble("avg_moving_kmh").toFloat(),
            calibrated = o.getBoolean("calibrated"),
            speed = trace("speed")!!,
            lean = trace("lean"),
            accel = trace("accel"),
            pitch = trace("pitch"),
        )
    }
}
