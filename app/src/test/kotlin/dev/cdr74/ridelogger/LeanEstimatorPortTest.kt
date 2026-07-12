package dev.cdr74.ridelogger

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Port verification (CLAUDE.md M6 note): the Kotlin causal estimator must reproduce
 * the Python reference implementation (`analysis/fusion/compare_ride.py`,
 * fused_causal) over a real 120 s ride excerpt. Fixtures are generated from
 * ride_20260712T085807Z (see scratch script export_portcheck.py, committed assets in
 * src/test/assets/portcheck): bias-corrected IMU stream, GPS fixes, the solved
 * calibration, and the reference lean trace at 100 Hz.
 *
 * The implementations differ deliberately in minor ways (Python interpolates GPS
 * speed linearly and aligns samples on an exact grid; the port zero-order-holds
 * both), so the assertion is a tolerance, not equality: RMS < 0.5° after the 15 s
 * cold-start settle.
 */
class LeanEstimatorPortTest {

    private fun asset(name: String): File {
        // Gradle unit tests run with the module directory as working dir.
        val f = File("src/test/assets/portcheck/$name")
        check(f.exists()) { "missing fixture ${f.absolutePath}" }
        return f
    }

    private fun readGz(name: String): List<String> =
        GZIPInputStream(asset(name).inputStream()).bufferedReader().readLines()

    @Test
    fun leanMatchesPythonReference() {
        // calib.json is {"R": [9 floats]} — parsed by hand (org.json is stubbed in unit tests)
        val nums = Regex("-?\\d+\\.?\\d*(?:[eE]-?\\d+)?")
            .findAll(asset("calib.json").readText().substringAfter('['))
            .map { it.value.toFloat() }.toList()
        check(nums.size == 9) { "expected 9 calibration values, got ${nums.size}" }
        val r = nums.toFloatArray()

        // expected reference trace: t_ns -> lean deg (empty = below speed cutoff)
        val expT = ArrayList<Long>()
        val expLean = ArrayList<Float>() // NaN = not produced
        for (line in readGz("expected.csv.gz")) {
            val c = line.split(',')
            expT.add(c[0].toLong())
            expLean.add(if (c[1].isEmpty()) Float.NaN else c[1].toFloat())
        }

        val gps = readGz("gps.csv.gz").map { line ->
            val c = line.split(',')
            Triple(c[0].toLong(), c[1].toFloat(), c[2].toFloatOrNull())
        }

        val outputs = ArrayList<LeanEstimator.Output>()
        val estimator = LeanEstimator(onOutput = { outputs.add(it) })
        estimator.publishEverySteps = 1
        estimator.setCalibration(r)

        var gpsIdx = 0
        for (line in readGz("imu.csv.gz")) {
            val c = line.split(',')
            val t = c[1].toLong()
            while (gpsIdx < gps.size && gps[gpsIdx].first <= t) {
                estimator.onGpsFix(gps[gpsIdx].first, gps[gpsIdx].second)
                gpsIdx++
            }
            val x = c[2].toFloat()
            val y = c[3].toFloat()
            val z = c[4].toFloat()
            if (c[0] == "a") estimator.onAccel(t, x, y, z) else estimator.onGyro(t, x, y, z)
        }

        check(outputs.size > 5000) { "estimator produced only ${outputs.size} outputs" }

        // compare each output against the reference sample nearest in time
        val t0 = expT.first()
        val settleNs = t0 + 15_000_000_000L
        var sumSq = 0.0
        var maxAbs = 0.0
        var n = 0
        for (out in outputs) {
            if (out.tNs < settleNs || out.leanDeg == null) continue
            val idx = ((out.tNs - t0) / 10_000_000L).toInt()
            if (idx < 0 || idx >= expT.size) continue
            val ref = expLean[idx]
            if (ref.isNaN()) continue
            val d = (out.leanDeg!! - ref).toDouble()
            sumSq += d * d
            maxAbs = maxOf(maxAbs, abs(d))
            n++
        }
        check(n > 5000) { "only $n comparable samples" }
        val rms = sqrt(sumSq / n)
        println("port check: $n samples, RMS ${"%.3f".format(rms)}°, max ${"%.2f".format(maxAbs)}°")
        assertTrue("lean RMS vs Python reference too high: ${"%.3f".format(rms)}°", rms < 0.5)
        assertTrue("lean max deviation too high: ${"%.2f".format(maxAbs)}°", maxAbs < 3.0)
    }
}
