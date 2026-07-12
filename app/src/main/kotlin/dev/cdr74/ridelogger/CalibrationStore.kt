package dev.cdr74.ridelogger

import android.content.Context

/**
 * Persists the last solved R_phone→bike across rides (ADR 0005 addendum): remount
 * repeatability was measured at ~1.25°, so the previous solution is valid from the
 * first second of the next ride, while the AutoCalibrator re-solves opportunistically.
 */
object CalibrationStore {
    private const val PREFS = "ridelogger_calib"

    fun save(context: Context, r: FloatArray, windows: Int, events: Int) {
        require(r.size == 9)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            for (i in 0..8) putFloat("r$i", r[i])
            putLong("solved_at_utc_ms", System.currentTimeMillis())
            putInt("cruise_windows", windows)
            putInt("accel_events", events)
        }.apply()
    }

    fun load(context: Context): FloatArray? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("r0")) return null
        return FloatArray(9) { prefs.getFloat("r$it", 0f) }
    }
}
