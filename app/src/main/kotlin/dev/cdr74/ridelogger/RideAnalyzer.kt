package dev.cdr74.ridelogger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

    /** Load the cached analysis or compute (seconds of work — call off the main thread). */
    fun get(context: Context, ride: File): Analysis {
        val cache = cacheFile(ride)
        if (cache.exists()) {
            runCatching { return fromJson(JSONObject(cache.readText())) }
            // unreadable/stale cache falls through to recompute
        }
        val analysis = analyze(context, ride)
        runCatching { cache.writeText(toJson(analysis).toString()) }
        return analysis
    }

    private fun analyze(context: Context, ride: File): Analysis {
        // pass 1: best calibration for this ride
        var solved: FloatArray? = null
        val calibrator = AutoCalibrator { r, _, _ -> solved = r }
        run {
            val est = LeanEstimator(
                onOutput = {},
                onStepSample = { a, g -> calibrator.onSample(a[0], a[1], a[2], g[0], g[1], g[2]) },
            )
            replay(ride, est, calibrator)
        }
        val r = solved ?: CalibrationStore.load(context)
        if (solved != null) CalibrationStore.save(context, solved!!, 0, 0)

        // pass 2: traces
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
        val gpsTrace = replay(ride, est, calibrator = null)

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

    /** Streams IMU + GPS rows through the estimator/calibrator; returns the speed trace. */
    private fun replay(ride: File, est: LeanEstimator, calibrator: AutoCalibrator?): Trace {
        val db = SQLiteDatabase.openDatabase(ride.path, null, SQLiteDatabase.OPEN_READONLY)
        val gpsT = ArrayList<Float>(4000)
        val gpsV = ArrayList<Float>(4000)
        db.use {
            // GPS fixes, consumed in time order alongside the IMU scan
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
            var gpsT0 = -1L

            it.rawQuery(
                "SELECT t_ns, stream, v0, v1, v2, b0, b1, b2 FROM imu WHERE stream IN (0,1) ORDER BY t_ns",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val t = c.getLong(0)
                    while (gi < gps.size && gps[gi].first <= t) {
                        val (gt, speed, bearing) = gps[gi]
                        est.onGpsFix(speed)
                        calibrator?.onGpsFix(gt, speed, bearing)
                        if (speed != null) {
                            if (gpsT0 < 0) gpsT0 = gt
                            gpsT.add((gt - gpsT0) / 1e9f)
                            gpsV.add(speed * 3.6f)
                        }
                        gi++
                    }
                    val bx = if (c.isNull(5)) 0f else c.getFloat(5)
                    val by = if (c.isNull(6)) 0f else c.getFloat(6)
                    val bz = if (c.isNull(7)) 0f else c.getFloat(7)
                    val x = c.getFloat(2) - bx
                    val y = c.getFloat(3) - by
                    val z = c.getFloat(4) - bz
                    if (c.getInt(1) == Config.STREAM_ACCEL) est.onAccel(t, x, y, z) else est.onGyro(t, x, y, z)
                }
            }
        }
        return Trace(gpsT.toFloatArray(), gpsV.toFloatArray())
    }

    // --- JSON cache (sidecar file, version-gated)

    private const val CACHE_VERSION = 1

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
