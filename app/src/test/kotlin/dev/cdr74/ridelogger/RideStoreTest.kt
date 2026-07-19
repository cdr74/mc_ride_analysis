package dev.cdr74.ridelogger

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RoboConfig

@RunWith(RobolectricTestRunner::class)
@RoboConfig(sdk = [34])
class RideStoreTest {

    private fun newStore(ring: RingBuffer = RingBuffer(1 shl 10)): Pair<RideStore, RingBuffer> =
        RideStore.inMemory(ring) to ring

    @Test
    fun `schema contains all tables and the imu index`() {
        val db = SQLiteDatabase.create(null)
        RideStore.createSchema(db)
        val names = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master", null).use {
            while (it.moveToNext()) names.add(it.getString(0))
        }
        assertTrue(names.containsAll(listOf("meta", "imu", "gps", "gps_status", "gnss_raw", "marker", "idx_imu")))
        db.close()
    }

    @Test
    fun `flushOnce commits ring contents as a visible transaction`() {
        val (store, ring) = newStore()
        ring.offer(Config.STREAM_ACCEL, 1000L, floatArrayOf(1f, 2f, 3f, 0.1f, 0.2f, 0.3f), 3)
        ring.offer(Config.STREAM_BARO, 2000L, floatArrayOf(1013.25f), 3)

        assertEquals(2, store.flushOnce())

        store.query("SELECT t_ns, stream, v0, v1, v2, b0, b1, b2, acc FROM imu ORDER BY t_ns").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1000L, c.getLong(0))
            assertEquals(Config.STREAM_ACCEL, c.getInt(1))
            assertEquals(1.0, Float.fromBits(c.getInt(2)).toDouble(), 1e-6)
            assertEquals(3.0, Float.fromBits(c.getInt(4)).toDouble(), 1e-6)
            assertEquals(0.1, Float.fromBits(c.getInt(5)).toDouble(), 1e-6) // uncalibrated bias -> b0
            assertEquals(0.3, Float.fromBits(c.getInt(7)).toDouble(), 1e-6)
            assertEquals(3, c.getInt(8))

            assertTrue(c.moveToNext())
            assertEquals(Config.STREAM_BARO, c.getInt(1))
            assertEquals(1013.25, Float.fromBits(c.getInt(2)).toDouble(), 1e-4)
            assertTrue(c.isNull(3)) // baro has no v1/v2/bias
            assertTrue(c.isNull(5))
        }
    }

    @Test
    fun `calibrated fallback leaves bias columns NULL`() {
        val (store, ring) = newStore()
        ring.offer(Config.STREAM_GYRO, 1L, floatArrayOf(0.1f, 0.2f, 0.3f), 3)
        store.flushOnce()
        store.query("SELECT b0, b1, b2 FROM imu").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0) && c.isNull(1) && c.isNull(2))
        }
    }

    @Test
    fun `rotvec maps quaternion w and heading accuracy into b0 and b1`() {
        val (store, ring) = newStore()
        ring.offer(Config.STREAM_ROTVEC, 5L, floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f, 0.05f), 3)
        store.flushOnce()
        store.query("SELECT v0, v1, v2, b0, b1, b2 FROM imu").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.9, Float.fromBits(c.getInt(3)).toDouble(), 1e-6)
            assertEquals(0.05, Float.fromBits(c.getInt(4)).toDouble(), 1e-6)
            assertTrue(c.isNull(5))
        }
    }

    @Test
    fun `flushOnce caps a batch at maxRows and resumes on the next call`() {
        val (store, ring) = newStore(RingBuffer(1 shl 13))
        repeat(5000) { ring.offer(Config.STREAM_ACCEL, it.toLong(), floatArrayOf(0f), 0) }

        assertEquals(4096, store.flushOnce(4096))
        assertEquals(4096L, store.count("imu"))
        assertEquals(904, store.flushOnce(4096))
        assertEquals(5000L, store.count("imu"))
        assertEquals(0, store.flushOnce(4096))
    }

    @Test
    fun `gps status marker and gnss queues drain into their tables`() {
        val (store, _) = newStore()
        store.enqueueGps(
            RideStore.GpsFix(
                tNs = 1L, tUtcMs = 1700000000000L, lat = 47.0, lon = 8.0, alt = 430.0,
                speed = 23.5, speedAcc = 0.4, bearing = 180.0, bearingAcc = 2.0,
                hAcc = 3.0, vAcc = 5.0, provider = "fused",
            ),
        )
        store.enqueueGpsStatus(RideStore.GpsStatusRow(2L, 22, 14))
        store.enqueueGnssRaw(RideStore.GnssRawRow(3L, 5, 1, 41.0, -123.4, 0.2))
        store.enqueueMarker(RideStore.MarkerRow(4L, "calib_start", "static_level"))
        store.enqueueMarker(RideStore.MarkerRow(5L, "user", null))

        assertEquals(5, store.flushOnce())

        store.query("SELECT speed_acc, provider FROM gps").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.4, c.getDouble(0), 1e-6)
            assertEquals("fused", c.getString(1))
        }
        assertEquals(1L, store.count("gps_status"))
        assertEquals(1L, store.count("gnss_raw"))
        store.query("SELECT kind, note FROM marker ORDER BY t_ns").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("calib_start", c.getString(0))
            assertEquals("static_level", c.getString(1))
            assertTrue(c.moveToNext())
            assertEquals("user", c.getString(0))
            assertTrue(c.isNull(1))
        }
    }

    @Test
    fun `meta is upserted and drop counts are written per stream`() {
        val (store, ring) = newStore(RingBuffer(4))
        store.putMeta("schema_version", "1")
        store.putMeta("schema_version", "1") // replace, not duplicate
        assertEquals("1", store.getMeta("schema_version"))
        assertNull(store.getMeta("missing"))

        repeat(6) { ring.offer(Config.STREAM_ACCEL, it.toLong(), floatArrayOf(0f), 0) } // 2 overflow
        store.writeDropCounts()
        assertEquals(
            """{"accel":2,"gyro":0,"mag":0,"rotvec":0,"baro":0}""",
            store.getMeta("dropped_events"),
        )
    }

    @Test
    fun `ride file name follows the utc pattern`() {
        assertEquals("ride_19700101T000000Z_deadbeef.db", RideStore.rideFileName(0L, "deadbeef"))
        // 2026-07-11 09:30:05 UTC
        assertEquals(
            "ride_20260711T093005Z_0a1b2c3d.db",
            RideStore.rideFileName(1783762205000L, "0a1b2c3d"),
        )
        assertTrue(
            RideStore.rideFileName(System.currentTimeMillis(), "12345678")
                .matches(Regex("""ride_\d{8}T\d{6}Z_[0-9a-f]{8}\.db""")),
        )
    }
}
