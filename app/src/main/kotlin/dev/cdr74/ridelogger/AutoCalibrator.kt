package dev.cdr74.ridelogger

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streaming on-device port of the automatic calibration solver (ADR 0004; reference:
 * `analysis/calibrate.py solve_auto`). Solves R_phone→bike from phases detected in
 * normal riding:
 *
 *   bike z (up)      ← duration-weighted mean specific force over straight
 *                      steady-cruise windows (never stops — bars turn at a standstill)
 *   bike x (forward) ← magnitude-weighted horizontal specific-force direction over
 *                      straight-line acceleration events
 *
 * Fed at 1 Hz from GPS fixes; the between-fix accel/gyro means come from the
 * anti-aliased stream (same 15 Hz chain the estimator uses). Emits a refreshed R
 * through [onSolved] whenever a new window or event improves the solution; the
 * service persists it and hands it to the estimator.
 *
 * Streaming deviation from the batch reference: acceleration events are only
 * evaluated once ≥ [MIN_CRUISE_WINDOWS] cruise windows exist (the z estimate must be
 * stable before events are projected against it); the batch solver does two passes.
 *
 * Pure Kotlin, single-threaded (call everything from one thread).
 */
class AutoCalibrator(
    private val onSolved: (r: FloatArray, nWindows: Int, nEvents: Int) -> Unit,
) {
    // accumulated between GPS fixes by the estimator thread, drained at each fix
    private val accSum = DoubleArray(3)
    private val gyrSum = DoubleArray(3)
    private var sumCount = 0

    // cruise-window accumulation → z
    private val fSum = DoubleArray(3) // Σ dur · mean specific force
    private var nWindows = 0
    private var cruiseStartNs = 0L
    private val cruiseAcc = DoubleArray(3)
    private var cruiseCount = 0

    // accel-event accumulation → x
    private val hSum = DoubleArray(3)
    private var nEvents = 0
    private var eventStartNs = 0L
    private var eventStartBearing = 0.0
    private val eventAcc = DoubleArray(3)
    private val eventGyr = DoubleArray(3)
    private var eventCount = 0

    private var prevTNs = 0L
    private var prevSpeed = -1.0
    private var prevBearing = Double.NaN

    /** Called per anti-aliased sample (sensor thread hands means over via [onGpsFix]'s drain). */
    @Synchronized
    fun onSample(ax: Double, ay: Double, az: Double, gx: Double, gy: Double, gz: Double) {
        accSum[0] += ax; accSum[1] += ay; accSum[2] += az
        gyrSum[0] += gx; gyrSum[1] += gy; gyrSum[2] += gz
        sumCount++
    }

    @Synchronized
    fun onGpsFix(tNs: Long, speedMps: Float?, bearingDeg: Float?) {
        val v = speedMps?.toDouble() ?: run { resetRuns(); return }
        val meanAcc = DoubleArray(3)
        val meanGyr = DoubleArray(3)
        if (sumCount > 0) {
            for (i in 0..2) {
                meanAcc[i] = accSum[i] / sumCount
                meanGyr[i] = gyrSum[i] / sumCount
                accSum[i] = 0.0
                gyrSum[i] = 0.0
            }
            sumCount = 0
        }

        val dt = if (prevTNs > 0) (tNs - prevTNs) / 1e9 else 0.0
        val dvdt = if (dt in 0.2..3.0 && prevSpeed >= 0) (v - prevSpeed) / dt else Double.NaN
        val bearing = bearingDeg?.toDouble() ?: Double.NaN
        val hdgRate = if (!bearing.isNaN() && !prevBearing.isNaN() && dt in 0.2..3.0) {
            abs(wrapDeg(bearing - prevBearing)) / dt
        } else {
            Double.NaN
        }
        prevTNs = tNs
        prevSpeed = v
        val prevB = prevBearing
        prevBearing = bearing

        // --- cruise windows → z (v > 8, |dv/dt| < 0.3, heading rate < 1 °/s, ≥ 5 s)
        val cruising = v > CRUISE_MIN_MPS && !dvdt.isNaN() && abs(dvdt) < CRUISE_MAX_DVDT &&
            !hdgRate.isNaN() && hdgRate < CRUISE_MAX_HDG_DEG_S
        if (cruising) {
            if (cruiseStartNs == 0L) cruiseStartNs = tNs
            for (i in 0..2) cruiseAcc[i] += meanAcc[i]
            cruiseCount++
        } else {
            endCruiseRun(tNs)
        }

        // --- accel events → x (dv/dt > 1.2 for ≥ 2 s, straight)
        val accelerating = !dvdt.isNaN() && dvdt > EVENT_MIN_DVDT
        if (accelerating) {
            if (eventStartNs == 0L) {
                eventStartNs = tNs
                eventStartBearing = if (prevB.isNaN()) bearing else prevB
                eventCount = 0
                java.util.Arrays.fill(eventAcc, 0.0)
                java.util.Arrays.fill(eventGyr, 0.0)
            }
            for (i in 0..2) {
                eventAcc[i] += meanAcc[i]
                eventGyr[i] += meanGyr[i]
            }
            eventCount++
        } else {
            endAccelEvent(tNs, bearing)
        }
    }

    private fun endCruiseRun(tNs: Long) {
        if (cruiseStartNs != 0L) {
            val dur = (tNs - cruiseStartNs) / 1e9
            if (dur >= CRUISE_MIN_S && cruiseCount > 0) {
                for (i in 0..2) fSum[i] += dur * cruiseAcc[i] / cruiseCount
                nWindows++
                solve()
            }
        }
        cruiseStartNs = 0L
        cruiseCount = 0
        java.util.Arrays.fill(cruiseAcc, 0.0)
    }

    private fun endAccelEvent(tNs: Long, bearing: Double) {
        val startNs = eventStartNs
        eventStartNs = 0L
        if (startNs == 0L || eventCount == 0) return
        val dur = (tNs - startNs) / 1e9
        if (dur < EVENT_MIN_S || nWindows < MIN_CRUISE_WINDOWS) return
        val dBrg = if (bearing.isNaN() || eventStartBearing.isNaN()) {
            Double.MAX_VALUE
        } else {
            abs(wrapDeg(bearing - eventStartBearing))
        }
        if (dBrg > EVENT_MAX_BRG_DEG) return

        val z = normalized(fSum) ?: return
        val g = DoubleArray(3) { eventGyr[it] / eventCount }
        if (abs(g[0] * z[0] + g[1] * z[1] + g[2] * z[2]) > EVENT_MAX_YAW_RAD_S) return

        val f = DoubleArray(3) { eventAcc[it] / eventCount }
        val fz = f[0] * z[0] + f[1] * z[1] + f[2] * z[2]
        val h = DoubleArray(3) { f[it] - fz * z[it] }
        val hn = sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2])
        if (hn < EVENT_MIN_H_MS2) return
        for (i in 0..2) hSum[i] += h[i] // magnitude-weighted: h is not normalized
        nEvents++
        solve()
    }

    private fun resetRuns() {
        cruiseStartNs = 0L
        cruiseCount = 0
        eventStartNs = 0L
        eventCount = 0
        prevSpeed = -1.0
        prevBearing = Double.NaN
    }

    private fun solve() {
        if (nWindows < MIN_CRUISE_WINDOWS || nEvents < 1) return
        val z = normalized(fSum) ?: return
        val hz = hSum[0] * z[0] + hSum[1] * z[1] + hSum[2] * z[2]
        val x = normalized(DoubleArray(3) { hSum[it] - hz * z[it] }) ?: return
        // y = z × x, then re-orthogonalize x = y × z
        val y = doubleArrayOf(
            z[1] * x[2] - z[2] * x[1],
            z[2] * x[0] - z[0] * x[2],
            z[0] * x[1] - z[1] * x[0],
        )
        val yn = normalized(y) ?: return
        val xo = doubleArrayOf(
            yn[1] * z[2] - yn[2] * z[1],
            yn[2] * z[0] - yn[0] * z[2],
            yn[0] * z[1] - yn[1] * z[0],
        )
        val r = FloatArray(9)
        for (i in 0..2) {
            r[i] = xo[i].toFloat()
            r[3 + i] = yn[i].toFloat()
            r[6 + i] = z[i].toFloat()
        }
        onSolved(r, nWindows, nEvents)
    }

    private fun normalized(v: DoubleArray): DoubleArray? {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (n > 1e-9) doubleArrayOf(v[0] / n, v[1] / n, v[2] / n) else null
    }

    private fun wrapDeg(d: Double): Double {
        var w = (d + 180.0) % 360.0
        if (w < 0) w += 360.0
        return w - 180.0
    }

    companion object {
        // thresholds mirror analysis/calibrate.py — keep in sync with the reference
        private const val CRUISE_MIN_MPS = 8.0
        private const val CRUISE_MAX_DVDT = 0.3
        private const val CRUISE_MAX_HDG_DEG_S = 1.0
        private const val CRUISE_MIN_S = 5.0
        private const val EVENT_MIN_DVDT = 1.2
        private const val EVENT_MIN_S = 2.0
        private const val EVENT_MAX_BRG_DEG = 10.0
        private const val EVENT_MAX_YAW_RAD_S = 0.05
        private const val EVENT_MIN_H_MS2 = 0.8
        private const val MIN_CRUISE_WINDOWS = 3
    }
}
