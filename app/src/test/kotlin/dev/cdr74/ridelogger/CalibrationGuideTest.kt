package dev.cdr74.ridelogger

import dev.cdr74.ridelogger.CalibrationGuide.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationGuideTest {

    private data class Marker(val tNs: Long, val kind: String, val note: String)

    private class Fixture {
        val markers = mutableListOf<Marker>()
        val guide = CalibrationGuide { t, kind, note -> markers.add(Marker(t, kind, note)) }
        fun fix(tSec: Double, speed: Double?) = guide.onFix((tSec * 1e9).toLong(), speed)
    }

    private fun sec(t: Double): Long = (t * 1e9).toLong()

    @Test
    fun `full hands-free run emits balanced markers in order`() {
        val f = Fixture()
        f.guide.start()
        assertEquals(Phase.WAIT_STATIC, f.guide.phase.value)

        // 3 still fixes confirm stationary, hold starts on the third
        f.fix(0.0, 0.2); f.fix(1.0, 0.2); f.fix(2.0, 0.2)
        assertEquals(Phase.STATIC, f.guide.phase.value)

        // 10 s of stillness
        for (t in 3..12) f.fix(t.toDouble(), 0.1)
        assertEquals(Phase.WAIT_ACCEL, f.guide.phase.value)

        // idle at the line, then hard launch
        f.fix(13.0, 0.2)
        f.fix(14.0, 4.0) // dv=3.8 m/s² > 2.0, speed > 1.5 → ACCEL
        assertEquals(Phase.ACCEL, f.guide.phase.value)
        f.fix(15.0, 8.0); f.fix(16.0, 12.0); f.fix(17.0, 15.0)
        f.fix(18.0, 15.3); f.fix(19.0, 15.4) // two plateau fixes, > 3 s elapsed → done
        assertEquals(Phase.WAIT_BRAKE, f.guide.phase.value)

        // cruise, then hard braking to a stop
        f.fix(20.0, 15.4)
        f.fix(21.0, 12.0) // dv=-3.4 m/s² < -2.0 → BRAKE
        assertEquals(Phase.BRAKE, f.guide.phase.value)
        f.fix(22.0, 8.0); f.fix(23.0, 4.0)
        f.fix(24.0, 0.5) // below stationary threshold → done
        assertEquals(Phase.DONE, f.guide.phase.value)

        assertEquals(
            listOf(
                Marker(sec(2.0), "calib_start", "static_level"),
                Marker(sec(12.0), "calib_end", "static_level"),
                Marker(sec(14.0) - Config.CALIB_LEAD_IN_NS, "calib_start", "accel"),
                Marker(sec(19.0), "calib_end", "accel"),
                Marker(sec(21.0) - Config.CALIB_LEAD_IN_NS, "calib_start", "brake"),
                Marker(sec(24.0), "calib_end", "brake"),
            ),
            f.markers,
        )
    }

    @Test
    fun `movement during the static hold closes the segment and retries`() {
        val f = Fixture()
        f.guide.start()
        f.fix(0.0, 0.1); f.fix(1.0, 0.1); f.fix(2.0, 0.1)
        assertEquals(Phase.STATIC, f.guide.phase.value)

        f.fix(5.0, 2.0) // rider moved 5 s in → abort, retry
        assertEquals(Phase.WAIT_STATIC, f.guide.phase.value)
        assertEquals(
            listOf("calib_start", "calib_end"),
            f.markers.map { it.kind },
        )

        // second attempt succeeds
        f.fix(6.0, 0.1); f.fix(7.0, 0.1); f.fix(8.0, 0.1)
        for (t in 9..18) f.fix(t.toDouble(), 0.1)
        assertEquals(Phase.WAIT_ACCEL, f.guide.phase.value)
        // markers stay balanced: start/end, start/end
        assertEquals(listOf("calib_start", "calib_end", "calib_start", "calib_end"), f.markers.map { it.kind })
    }

    @Test
    fun `positioning creep is treated as a false start and retried`() {
        val f = Fixture()
        f.guide.start()
        f.fix(0.0, 0.1); f.fix(1.0, 0.1); f.fix(2.0, 0.1)
        for (t in 3..12) f.fix(t.toDouble(), 0.1)
        assertEquals(Phase.WAIT_ACCEL, f.guide.phase.value)

        f.fix(13.0, 2.5) // brief lurch forward…
        assertEquals(Phase.ACCEL, f.guide.phase.value)
        f.fix(14.0, 0.5) // …but immediately back to a crawl → false start
        assertEquals(Phase.WAIT_ACCEL, f.guide.phase.value)

        f.fix(15.0, 5.0) // now the real launch
        assertEquals(Phase.ACCEL, f.guide.phase.value)
        // still balanced
        assertEquals(0, f.markers.count { it.kind == "calib_start" } - f.markers.count { it.kind == "calib_end" } - 1)
    }

    @Test
    fun `accel phase ends at the hard timeout even without a plateau`() {
        val f = Fixture()
        f.guide.start()
        f.fix(0.0, 0.1); f.fix(1.0, 0.1); f.fix(2.0, 0.1)
        for (t in 3..12) f.fix(t.toDouble(), 0.1)
        f.fix(13.0, 4.0)
        assertEquals(Phase.ACCEL, f.guide.phase.value)
        var v = 4.0
        var t = 14.0
        while (f.guide.phase.value == Phase.ACCEL && t < 40.0) {
            v += 3.0
            f.fix(t, v) // keeps accelerating forever
            t += 1.0
        }
        assertEquals(Phase.WAIT_BRAKE, f.guide.phase.value)
        val end = f.markers.last()
        assertEquals("calib_end", end.kind)
        assertEquals("accel", end.note)
    }

    @Test
    fun `cancel closes an open segment so markers stay balanced`() {
        val f = Fixture()
        f.guide.start()
        f.fix(0.0, 0.1); f.fix(1.0, 0.1); f.fix(2.0, 0.1)
        assertEquals(Phase.STATIC, f.guide.phase.value)

        f.guide.cancel()
        assertEquals(Phase.IDLE, f.guide.phase.value)
        assertEquals(listOf("calib_start", "calib_end"), f.markers.map { it.kind })
    }

    @Test
    fun `fixes without speed are ignored`() {
        val f = Fixture()
        f.guide.start()
        f.fix(0.0, 0.1); f.fix(1.0, null); f.fix(2.0, 0.1); f.fix(3.0, 0.1)
        assertEquals(Phase.STATIC, f.guide.phase.value) // null fix did not reset the count
    }
}
