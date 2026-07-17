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
}
