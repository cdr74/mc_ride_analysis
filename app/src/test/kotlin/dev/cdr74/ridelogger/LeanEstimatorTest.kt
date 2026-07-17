package dev.cdr74.ridelogger

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Synthetic physics checks for the live estimator — properties the real-ride port
 * fixture (LeanEstimatorPortTest) cannot isolate.
 */
class LeanEstimatorTest {

    /**
     * A steady coordinated turn must NOT read as pitch (ADR 0007): the yaw rate
     * projects into the bike y-axis (wy = ψ̇·sinφ), and integrating -wy alone
     * accumulates Δheading·sin(lean) of phantom nose-up — ~+23° steady-state for
     * this turn with the pre-0.3.3 code. With Euler kinematics it stays near zero.
     *
     * Setup: identity calibration, 400 Hz IMU, right turn at v = 15 m/s with the
     * coordinated lean φ = atan(v·Ω/g); specific force g/cosφ along bike z.
     */
    @Test
    fun coordinatedTurnProducesNoPitch() {
        val g = 9.80665
        val v = 15.0                       // m/s, above the lean cutoff
        val phi = Math.toRadians(20.0)     // right lean
        val yawRate = -g * tan(phi) / v    // rad/s, negative = right turn (z up)
        val wy = (yawRate * sin(phi)).toFloat()
        val wz = (yawRate * cos(phi)).toFloat()
        val fz = (g / cos(phi)).toFloat()  // coordinated: resultant along bike z

        var lastPitch = Float.NaN
        var maxAbsPitchAfterSettle = 0.0
        var tNs = 0L
        val settleNs = 20_000_000_000L
        val estimator = LeanEstimator(onOutput = {
            if (it.pitchDeg != null) {
                lastPitch = it.pitchDeg!!
                if (it.tNs > settleNs) {
                    maxAbsPitchAfterSettle = maxOf(maxAbsPitchAfterSettle, abs(lastPitch.toDouble()))
                }
            }
        })
        estimator.setCalibration(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        )

        val stepNs = 2_500_000L // 400 Hz
        val durationNs = 60_000_000_000L // 60 s ≈ 2.3 full circles of heading
        var nextGpsNs = 0L
        while (tNs < durationNs) {
            if (tNs >= nextGpsNs) {
                estimator.onGpsFix(tNs, v.toFloat())
                nextGpsNs += 1_000_000_000L
            }
            estimator.onAccel(tNs, 0f, 0f, fz)
            estimator.onGyro(tNs, 0f, wy, wz)
            tNs += stepNs
        }

        assertTrue("estimator never produced pitch", !lastPitch.isNaN())
        assertTrue(
            "steady coordinated turn reads as pitch: max |pitch| = " +
                "%.2f° after settle".format(maxAbsPitchAfterSettle),
            maxAbsPitchAfterSettle < 3.0,
        )
    }

    /**
     * Pitch is bike-relative-to-road (ADR 0008): on a steady climb the road grade —
     * estimated from baro climb rate ÷ GPS speed — is subtracted, so displayed pitch
     * settles near 0. Without a barometer the grade stays 0 and the same climb reads
     * as the absolute grade angle (graceful fallback).
     */
    @Test
    fun steadyClimbReadsAsZeroPitchWithBaro() {
        val gradeDeg = 3.0
        val withBaro = settledClimbPitch(gradeDeg, feedBaro = true)
        val withoutBaro = settledClimbPitch(gradeDeg, feedBaro = false)
        assertTrue(
            "climb with baro should read ~0° pitch, got %.2f°".format(withBaro),
            abs(withBaro) < 1.0,
        )
        assertTrue(
            "climb without baro should read the grade (~%.1f°), got %.2f°".format(gradeDeg, withoutBaro),
            abs(withoutBaro - gradeDeg) < 0.5,
        )
    }

    /** Steady straight climb at constant grade, v = 15 m/s; returns the settled pitch. */
    private fun settledClimbPitch(gradeDeg: Double, feedBaro: Boolean): Double {
        val g = 9.80665
        val v = 15.0
        val grade = Math.toRadians(gradeDeg)
        val fx = (g * sin(grade)).toFloat() // nose-up gravity component on the climb
        val fz = (g * cos(grade)).toFloat()
        val climbRate = v * sin(grade) // m/s

        var pitch = Double.NaN
        val estimator = LeanEstimator(onOutput = { it.pitchDeg?.let { p -> pitch = p.toDouble() } })
        estimator.setCalibration(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        val stepNs = 2_500_000L // 400 Hz IMU
        var tNs = 0L
        var nextGpsNs = 0L
        var nextBaroNs = 0L
        while (tNs < 120_000_000_000L) {
            if (tNs >= nextGpsNs) {
                estimator.onGpsFix(tNs, v.toFloat())
                nextGpsNs += 1_000_000_000L
            }
            if (feedBaro && tNs >= nextBaroNs) {
                val h = climbRate * tNs / 1e9
                val hPa = 1013.25 * Math.pow(1.0 - h / 44330.0, 1.0 / 0.1903)
                estimator.onBaro(tNs, hPa.toFloat())
                nextBaroNs += 80_000_000L // 12.5 Hz
            }
            estimator.onAccel(tNs, fx, 0f, fz)
            estimator.onGyro(tNs, 0f, 0f, 0f)
            tNs += stepNs
        }
        assertTrue("estimator never produced pitch", !pitch.isNaN())
        return pitch
    }
}
