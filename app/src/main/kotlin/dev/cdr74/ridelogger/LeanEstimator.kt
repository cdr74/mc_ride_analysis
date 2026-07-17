package dev.cdr74.ridelogger

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Causal live estimator for lean / accel / pitch — the Kotlin port of the validated
 * offline chain in `analysis/fusion/compare_ride.py` (fused_causal), which is the
 * reference implementation; the port is verified against its traces in
 * LeanEstimatorPortTest. Pure Kotlin on purpose: no Android dependencies.
 *
 * Chain (all filters causal):
 *   accel/gyro @ native rate → 4th-order Butterworth LP 15 Hz (anti-alias for the
 *   22–105 Hz engine-vibration band) → 100 Hz steps → bike frame via R_phone→bike →
 *     lean: gyro roll-rate integration + complementary correction (τ = 2 s) toward
 *           kinematic lean atan(v·ψ̇/g) (LP 1.5 Hz) when moving, gravity roll when slow;
 *           NOT PRODUCED below 18 km/h (bar-turn coupling, DESIGN.md §11)
 *     accel: longitudinal specific force, LP 1.5 Hz for a flicker-free bar
 *     pitch: Euler pitch-rate integration (θ̇ = -wy·cosφ + wz·sinφ, using the current
 *            roll — raw -wy alone reads leaned turns as phantom nose-up, ADR 0007)
 *            + slow correction (τ = 5 s) toward gravity pitch. The DISPLAYED pitch is
 *            bike-relative-to-road: θ minus the road grade estimated from the
 *            barometer climb rate ÷ GPS speed (ADR 0008); without a barometer the
 *            grade stays 0 and pitch falls back to absolute. Wheelie band still
 *            unvalidated until real wheelie data exists
 *
 * Threading: onAccel/onGyro are called on the sensor thread (no allocation, plain
 * float math); onGpsFix from the main thread (volatile hand-off); the [Output] is
 * published through [onOutput] at ~10 Hz from the sensor thread.
 */
class LeanEstimator(
    private val onOutput: (Output) -> Unit,
    /**
     * Called once per 100 Hz step with the anti-aliased PHONE-frame accel and gyro —
     * the AutoCalibrator's input. Runs even before any calibration exists (that is
     * the point: the first-ever solve needs it). The arrays are reused; do not retain.
     */
    private val onStepSample: ((acc: DoubleArray, gyr: DoubleArray) -> Unit)? = null,
) {
    data class Output(
        val tNs: Long, // elapsedRealtimeNanos of the step this output belongs to
        val leanDeg: Float?, // null below the speed cutoff or while uncalibrated
        val accelMs2: Float?, // null while uncalibrated
        val pitchDeg: Float?, // null while uncalibrated
    )

    /** One biquad section, RBJ-cookbook low-pass. */
    private class Biquad(fc: Double, fs: Double, q: Double) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        private var primed = false

        init {
            val w0 = 2.0 * PI * fc / fs
            val alpha = sin(w0) / (2.0 * q)
            val cw = cos(w0)
            val a0 = 1.0 + alpha
            b0 = (1.0 - cw) / 2.0 / a0
            b1 = (1.0 - cw) / a0
            b2 = (1.0 - cw) / 2.0 / a0
            a1 = -2.0 * cw / a0
            a2 = (1.0 - alpha) / a0
        }

        fun filter(x: Double): Double {
            if (!primed) { // start from steady state at the first sample, not from 0
                x1 = x; x2 = x; y1 = x; y2 = x; primed = true
            }
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }

    /** 4th-order Butterworth = two cascaded biquads with the standard Q pair. */
    private class Lp4(fc: Double, fs: Double) {
        private val s1 = Biquad(fc, fs, 0.541196)
        private val s2 = Biquad(fc, fs, 1.306563)
        fun filter(x: Double): Double = s2.filter(s1.filter(x))
    }

    private class Lp2(fc: Double, fs: Double) {
        private val s = Biquad(fc, fs, 0.707107)
        fun filter(x: Double): Double = s.filter(x)
    }

    // --- calibration (R rows = bike x/y/z in phone frame); null until first solve
    @Volatile private var calibration: FloatArray? = null

    fun setCalibration(rPhoneToBike: FloatArray) {
        require(rPhoneToBike.size == 9)
        calibration = rPhoneToBike
    }

    // --- GPS hand-off (main thread → sensor thread)
    @Volatile private var gpsSpeedMps: Float = -1f // <0 = no fix yet
    @Volatile private var gpsALong: Float = 0f // GPS-derived longitudinal accel, m/s²
    private var prevFixSpeed = -1f
    private var prevFixTNs = 0L

    fun onGpsFix(tNs: Long, speedMps: Float?) {
        if (speedMps == null) {
            gpsSpeedMps = -1f
            prevFixSpeed = -1f
            gpsALong = 0f
            return
        }
        gpsSpeedMps = speedMps
        val dt = (tNs - prevFixTNs) / 1e9
        gpsALong = if (prevFixSpeed >= 0 && dt in 0.2..3.0) {
            ((speedMps - prevFixSpeed) / dt).toFloat()
        } else {
            0f
        }
        prevFixSpeed = speedMps
        prevFixTNs = tNs
    }

    // --- road grade from the barometer (bike-only pitch, ADR 0008). Sensor thread
    // only: baro callbacks arrive on the same HandlerThread as the 100 Hz steps.
    private var baroPrevTNs = 0L
    private var altLp1 = Double.NaN // two cascaded one-pole LPs, τ = 4 s
    private var altLp2 = Double.NaN
    private var altPrev = Double.NaN
    private var climbRate = 0.0 // m/s, one-pole τ = 2 s
    private var grade = 0.0 // rad, one-pole τ = 2 s while moving; decays to θ stopped

    fun onBaro(tNs: Long, hPa: Float) {
        val alt = 44330.0 * (1.0 - Math.pow(hPa / 1013.25, 0.1903)) // ISA, relative use
        var dt = (tNs - baroPrevTNs) / 1e9
        baroPrevTNs = tNs
        if (dt !in 0.005..2.0) dt = 0.08
        if (altLp1.isNaN()) {
            altLp1 = alt
            altLp2 = alt
        }
        altLp1 += dt / (TAU_ALT_S + dt) * (alt - altLp1)
        altLp2 += dt / (TAU_ALT_S + dt) * (altLp1 - altLp2)
        if (!altPrev.isNaN()) {
            climbRate += dt / (TAU_CLIMB_S + dt) * ((altLp2 - altPrev) / dt - climbRate)
            val v = gpsSpeedMps.toDouble()
            if (v > GRADE_MIN_SPEED_MPS) {
                val g = atan2(climbRate, v).coerceIn(-GRADE_MAX_RAD, GRADE_MAX_RAD)
                grade += dt / (TAU_CLIMB_S + dt) * (g - grade)
            }
        }
        altPrev = altLp2
    }

    // --- input-rate estimation, then anti-alias filters designed for that rate
    private var rateSamples = 0
    private var rateT0 = 0L
    private var fsIn = 0.0
    private var aaFilters: Array<Lp4>? = null // ax ay az gx gy gz

    // latest anti-aliased phone-frame samples
    private val acc = DoubleArray(3)
    private val gyr = DoubleArray(3)
    private var haveAccel = false

    // 100 Hz complementary-step state
    private var lastStepNs = 0L
    private var phi = 0.0 // roll, rad, + = right lean
    private var theta = 0.0 // pitch, rad, + = nose up
    private var kinLp: Lp2? = null
    private var accLp: Lp2? = null
    private var aLongLp: Lp2? = null
    private var stepsSincePublish = 0

    fun onAccel(tNs: Long, x: Float, y: Float, z: Float) {
        val f = ensureFilters(tNs) ?: return
        acc[0] = f[0].filter(x.toDouble())
        acc[1] = f[1].filter(y.toDouble())
        acc[2] = f[2].filter(z.toDouble())
        haveAccel = true
    }

    fun onGyro(tNs: Long, x: Float, y: Float, z: Float) {
        val f = ensureFilters(tNs) ?: return
        gyr[0] = f[3].filter(x.toDouble())
        gyr[1] = f[4].filter(y.toDouble())
        gyr[2] = f[5].filter(z.toDouble())
        maybeStep(tNs)
    }

    /** Measure the delivered rate over the first ~200 samples, then build the AA chain. */
    private fun ensureFilters(tNs: Long): Array<Lp4>? {
        aaFilters?.let { return it }
        if (rateSamples == 0) rateT0 = tNs
        rateSamples++
        if (rateSamples < RATE_MEASURE_SAMPLES || tNs <= rateT0) return null
        fsIn = (rateSamples - 1) * 1e9 / (tNs - rateT0)
        val fs = fsIn.coerceAtLeast(4 * AA_HZ) // AA filter needs headroom; slow sensors pass through
        aaFilters = Array(6) { Lp4(AA_HZ, fs) }
        kinLp = Lp2(KIN_HZ, STEP_HZ)
        accLp = Lp2(KIN_HZ, STEP_HZ)
        aLongLp = Lp2(KIN_HZ, STEP_HZ)
        return aaFilters
    }

    private fun maybeStep(tNs: Long) {
        if (!haveAccel) return
        if (lastStepNs == 0L) {
            lastStepNs = tNs
            return
        }
        val dtNs = tNs - lastStepNs
        if (dtNs < STEP_NS) return
        lastStepNs = tNs
        val dt = (dtNs.coerceAtMost(4 * STEP_NS)) / 1e9

        onStepSample?.invoke(acc, gyr)

        val r = calibration ?: run { publishNull(); return }

        // rotate to bike frame: rows of R are bike axes in phone coords
        fun row(i: Int, v: DoubleArray) =
            r[3 * i] * v[0] + r[3 * i + 1] * v[1] + r[3 * i + 2] * v[2]
        val fx = row(0, acc)
        val fy = row(1, acc)
        val fz = row(2, acc)
        val wx = row(0, gyr)
        val wy = row(1, gyr)
        val wz = row(2, gyr)

        val v = gpsSpeedMps.toDouble()
        val gravRoll = atan2(fy, fz)
        val kinRaw = if (v > 0) atan2(-v * wz, G) else 0.0
        val kin = kinLp!!.filter(kinRaw)

        phi += wx * dt
        val target = if (v > LEAN_MIN_SPEED_MPS) kin else gravRoll
        phi += dt / TAU_ROLL_S * (target - phi)

        // pitch: + = nose up. The specific force along x contains the bike's actual
        // longitudinal acceleration — uncorrected, a 6 m/s² launch reads as asin(6/g)
        // ≈ 38° of phantom wheelie (observed as +46° in the field, 0.3.1 review).
        // Subtract the GPS-derived longitudinal acceleration (smoothed) before
        // extracting gravity pitch — the same GPS-aiding idea as the lean channel.
        val aLong = aLongLp!!.filter(gpsALong.toDouble())
        val fMag = sqrt(fx * fx + fy * fy + fz * fz)
        val gravPitch = if (fMag > 1.0) {
            asin(((fx - aLong) / fMag).coerceIn(-1.0, 1.0))
        } else {
            0.0
        }
        // Euler pitch rate, not raw body rate: in a leaned turn the yaw rate projects
        // into the bike y-axis (wy = ψ̇·sinφ), so integrating -wy alone turns every
        // corner into Δheading·sin(lean) of phantom nose-up — +30–39° on a commute
        // (observed in the field, 0.3.2 review, ADR 0007). The wz·sinφ term cancels it.
        theta += (-wy * cos(phi) + wz * sin(phi)) * dt
        theta += dt / TAU_PITCH_S * (gravPitch - theta)

        // stopped: a held road grade goes stale (you may have stopped on a different
        // slope) — decay it toward the measured pitch so the display settles to 0,
        // mirroring the lean channel's correct-toward-gravity-when-slow philosophy
        if (v < GRADE_MIN_SPEED_MPS) {
            grade += dt / TAU_GRADE_STOP_S * (theta - grade)
        }

        // longitudinal specific force (raw unit, m/s²), smoothed for a flicker-free bar
        val accDisp = accLp!!.filter(fx)

        if (++stepsSincePublish >= publishEverySteps) {
            stepsSincePublish = 0
            onOutput(
                Output(
                    tNs = tNs,
                    leanDeg = if (v > LEAN_MIN_SPEED_MPS) Math.toDegrees(phi).toFloat() else null,
                    accelMs2 = accDisp.toFloat(),
                    // bike-relative pitch: the road grade is subtracted (ADR 0008)
                    pitchDeg = Math.toDegrees(theta - grade).toFloat(),
                ),
            )
        }
    }

    private fun publishNull() {
        if (++stepsSincePublish >= publishEverySteps) {
            stepsSincePublish = 0
            onOutput(Output(lastStepNs, null, null, null))
        }
    }

    /** Test hook: publish every step instead of every 10th. */
    internal var publishEverySteps = PUBLISH_EVERY_STEPS

    companion object {
        const val LEAN_MIN_SPEED_MPS = 5.0 // 18 km/h — below this lean is not produced
        private const val G = 9.80665
        private const val AA_HZ = 15.0
        private const val KIN_HZ = 1.5
        private const val STEP_HZ = 100.0
        private const val STEP_NS = 10_000_000L
        private const val TAU_ROLL_S = 2.0
        private const val TAU_PITCH_S = 5.0
        private const val TAU_ALT_S = 4.0 // baro altitude smoothing (×2 cascaded)
        private const val TAU_CLIMB_S = 2.0 // climb-rate and grade smoothing
        private const val TAU_GRADE_STOP_S = 10.0 // stale-grade decay at standstill
        private const val GRADE_MIN_SPEED_MPS = 3.0 // grade undefined below this
        private const val GRADE_MAX_RAD = 0.21 // ±12° — steeper than any paved road
        private const val RATE_MEASURE_SAMPLES = 200
        private const val PUBLISH_EVERY_STEPS = 10 // ~10 Hz UI updates
    }
}
