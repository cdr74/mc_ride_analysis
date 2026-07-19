package dev.cdr74.ridelogger

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RoboConfig
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34])
class RideAnalyzerTest {

    /**
     * Regression for 0.4.0: the elevation trace came out empty on every ride (the
     * ~1 Hz decimation used a Long.MIN_VALUE sentinel, and `t - MIN_VALUE` overflows
     * negative for real elapsedRealtimeNanos), which silently hid the ELEVATION row.
     * A synthetic steady climb must produce a populated, rising elevation trace.
     */
    @Test
    fun elevationTraceIsPopulatedFromBaro() = runTest {
        val climbRate = 0.8 // m/s
        val durationS = 120
        val t0 = 4_000_000_000_000L // realistic elapsedRealtimeNanos magnitude

        val dir = File.createTempFile("ride", "").let { it.delete(); it.mkdir(); it }
        val file = File(dir, "ride_test.db")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        RideStore.createSchema(db)
        db.beginTransaction()
        try {
            // stationary accel/gyro at 50 Hz (replay needs stream 0 for its bounds)
            // Schema v2: v0-v2 stored as float32 bit patterns (INTEGER column)
            val zero = 0f.toBits()
            val g = 9.81f.toBits()
            var t = t0
            while (t < t0 + durationS * 1_000_000_000L) {
                db.execSQL("INSERT INTO imu (t_ns, stream, v0, v1, v2) VALUES ($t, 0, $zero, $zero, $g)")
                db.execSQL("INSERT INTO imu (t_ns, stream, v0, v1, v2) VALUES ($t, 1, $zero, $zero, $zero)")
                t += 20_000_000L
            }
            // baro at 12.5 Hz: pressure falling with a steady climb
            t = t0
            while (t < t0 + durationS * 1_000_000_000L) {
                val h = climbRate * (t - t0) / 1e9
                val hPa = (1013.25 * (1.0 - h / 44330.0).pow(1.0 / 0.1903)).toFloat().toBits()
                db.execSQL("INSERT INTO imu (t_ns, stream, v0) VALUES ($t, 4, $hPa)")
                t += 80_000_000L
            }
            // GPS at 1 Hz, steady 15 m/s
            t = t0
            while (t < t0 + durationS * 1_000_000_000L) {
                db.execSQL("INSERT INTO gps (t_ns, t_utc_ms, speed) VALUES ($t, 0, 15.0)")
                t += 1_000_000_000L
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.close()

        val a = RideAnalyzer.get(RuntimeEnvironment.getApplication(), file)

        val elev = a.elevation
        assertNotNull("elevation trace missing despite baro data", elev)
        assertTrue("expected ~1 point per second, got ${elev!!.t.size}", elev.t.size >= durationS - 10)
        val climbed = elev.v.last() - elev.v.first()
        val expected = climbRate * durationS
        assertTrue(
            "elevation should rise ~%.0f m, got %.1f m".format(expected, climbed),
            abs(climbed - expected) < 15.0, // smoothing lag (2 × τ=4 s) trails a few m
        )
    }
}
