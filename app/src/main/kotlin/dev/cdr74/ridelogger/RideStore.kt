package dev.cdr74.ridelogger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * SQLite writer for one ride file (design.md §5). Insert-only, WAL, batched
 * transactions committed every <= FLUSH_INTERVAL_MS or >= MAX_BATCH_ROWS.
 * All table writes happen on the single writer coroutine; `putMeta` may be
 * called from any thread (SQLiteDatabase serializes internally).
 */
class RideStore private constructor(
    private val db: SQLiteDatabase,
    val file: File?,
    private val ring: RingBuffer,
) {

    data class GpsFix(
        val tNs: Long, val tUtcMs: Long,
        val lat: Double, val lon: Double, val alt: Double?,
        val speed: Double?, val speedAcc: Double?,
        val bearing: Double?, val bearingAcc: Double?,
        val hAcc: Double?, val vAcc: Double?, val provider: String?,
    )

    data class GpsStatusRow(val tNs: Long, val satsTotal: Int, val satsUsed: Int)

    data class GnssRawRow(
        val tNs: Long, val svid: Int, val constellation: Int,
        val cn0: Double, val prr: Double, val prrUnc: Double,
    )

    data class MarkerRow(val tNs: Long, val kind: String, val note: String?)

    // Low-rate side channels drained by the writer coroutine alongside the ring buffer.
    private val gpsQueue = ConcurrentLinkedQueue<GpsFix>()
    private val gpsStatusQueue = ConcurrentLinkedQueue<GpsStatusRow>()
    private val gnssRawQueue = ConcurrentLinkedQueue<GnssRawRow>()
    private val markerQueue = ConcurrentLinkedQueue<MarkerRow>()

    private val imuStmt: SQLiteStatement =
        db.compileStatement("INSERT INTO imu(t_ns,stream,v0,v1,v2,b0,b1,b2,acc) VALUES(?,?,?,?,?,?,?,?,?)")
    private val gpsStmt: SQLiteStatement =
        db.compileStatement(
            "INSERT INTO gps(t_ns,t_utc_ms,lat,lon,alt,speed,speed_acc,bearing,bearing_acc,h_acc,v_acc,provider) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        )
    private val gpsStatusStmt: SQLiteStatement =
        db.compileStatement("INSERT INTO gps_status(t_ns,sats_total,sats_used) VALUES(?,?,?)")
    private val gnssRawStmt: SQLiteStatement =
        db.compileStatement("INSERT INTO gnss_raw(t_ns,svid,constellation,cn0,prr,prr_unc) VALUES(?,?,?,?,?,?)")
    private val markerStmt: SQLiteStatement =
        db.compileStatement("INSERT INTO marker(t_ns,kind,note) VALUES(?,?,?)")
    private val metaStmt: SQLiteStatement =
        db.compileStatement("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)")

    fun enqueueGps(fix: GpsFix) = gpsQueue.add(fix)
    fun enqueueGpsStatus(row: GpsStatusRow) = gpsStatusQueue.add(row)
    fun enqueueGnssRaw(row: GnssRawRow) = gnssRawQueue.add(row)
    fun enqueueMarker(row: MarkerRow) = markerQueue.add(row)

    @Synchronized
    fun putMeta(key: String, value: String) {
        metaStmt.clearBindings()
        metaStmt.bindString(1, key)
        metaStmt.bindString(2, value)
        metaStmt.executeInsert()
    }

    fun query(sql: String): android.database.Cursor = db.rawQuery(sql, null)

    fun count(table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst()
            it.getLong(0)
        }

    fun getMeta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /**
     * Drains pending data into one committed transaction. Returns the number of rows
     * written; a return of [maxRows] means more data is pending and the caller should
     * flush again immediately.
     */
    fun flushOnce(maxRows: Int = Config.MAX_BATCH_ROWS): Int {
        var rows = 0
        db.beginTransactionNonExclusive()
        try {
            rows += ring.drain(maxRows) { bindImu(it) }
            while (rows < maxRows) {
                val fix = gpsQueue.poll() ?: break
                bindGps(fix)
                rows++
            }
            while (rows < maxRows) {
                val s = gpsStatusQueue.poll() ?: break
                gpsStatusStmt.clearBindings()
                gpsStatusStmt.bindLong(1, s.tNs)
                gpsStatusStmt.bindLong(2, s.satsTotal.toLong())
                gpsStatusStmt.bindLong(3, s.satsUsed.toLong())
                gpsStatusStmt.executeInsert()
                rows++
            }
            while (rows < maxRows) {
                val r = gnssRawQueue.poll() ?: break
                gnssRawStmt.clearBindings()
                gnssRawStmt.bindLong(1, r.tNs)
                gnssRawStmt.bindLong(2, r.svid.toLong())
                gnssRawStmt.bindLong(3, r.constellation.toLong())
                gnssRawStmt.bindDouble(4, r.cn0)
                gnssRawStmt.bindDouble(5, r.prr)
                gnssRawStmt.bindDouble(6, r.prrUnc)
                gnssRawStmt.executeInsert()
                rows++
            }
            while (rows < maxRows) {
                val m = markerQueue.poll() ?: break
                markerStmt.clearBindings()
                markerStmt.bindLong(1, m.tNs)
                markerStmt.bindString(2, m.kind)
                if (m.note != null) markerStmt.bindString(3, m.note) else markerStmt.bindNull(3)
                markerStmt.executeInsert()
                rows++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return rows
    }

    /** Writer coroutine (design.md §5.1): flush immediately while saturated, else every 500 ms. */
    fun startWriter(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            val n = flushOnce()
            if (n < Config.MAX_BATCH_ROWS) delay(Config.FLUSH_INTERVAL_MS)
        }
    }

    /** Final drain — call after the writer job is cancelled and sensors are unregistered. */
    fun drainAll() {
        while (flushOnce() > 0) Unit
    }

    fun writeDropCounts() {
        val json = StringBuilder("{")
        for (i in 0 until Config.STREAM_COUNT) {
            if (i > 0) json.append(",")
            json.append("\"").append(Config.STREAM_NAMES[i]).append("\":").append(ring.drops.get(i))
        }
        json.append("}")
        putMeta("dropped_events", json.toString())
    }

    fun fileBytes(): Long {
        val f = file ?: return 0
        val wal = File(f.parentFile, f.name + "-wal")
        return f.length() + (if (wal.exists()) wal.length() else 0)
    }

    /** Checkpoints WAL and closes. The file must never be written again after this. */
    fun close() {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        db.close()
    }

    private fun bindImu(s: RingBuffer.Slot) {
        val st = imuStmt
        st.clearBindings()
        st.bindLong(1, s.tNs)
        st.bindLong(2, s.stream.toLong())
        st.bindDouble(3, s.values[0].toDouble())
        when (s.stream) {
            Config.STREAM_BARO -> Unit // v1,v2,b* stay NULL
            Config.STREAM_ROTVEC -> {
                // v0-v2 = quaternion x,y,z; b0 = w (scalar); b1 = est. heading accuracy (§5.2)
                if (s.n > 1) st.bindDouble(4, s.values[1].toDouble())
                if (s.n > 2) st.bindDouble(5, s.values[2].toDouble())
                if (s.n > 3) st.bindDouble(6, s.values[3].toDouble())
                if (s.n > 4) st.bindDouble(7, s.values[4].toDouble())
            }
            else -> {
                if (s.n > 1) st.bindDouble(4, s.values[1].toDouble())
                if (s.n > 2) st.bindDouble(5, s.values[2].toDouble())
                // *_UNCALIBRATED delivers 6 values; bias fields stay NULL for calibrated fallback
                if (s.n > 3) st.bindDouble(6, s.values[3].toDouble())
                if (s.n > 4) st.bindDouble(7, s.values[4].toDouble())
                if (s.n > 5) st.bindDouble(8, s.values[5].toDouble())
            }
        }
        st.bindLong(9, s.acc.toLong())
        st.executeInsert()
    }

    private fun bindGps(f: GpsFix) {
        val st = gpsStmt
        st.clearBindings()
        st.bindLong(1, f.tNs)
        st.bindLong(2, f.tUtcMs)
        st.bindDouble(3, f.lat)
        st.bindDouble(4, f.lon)
        f.alt?.let { st.bindDouble(5, it) }
        f.speed?.let { st.bindDouble(6, it) }
        f.speedAcc?.let { st.bindDouble(7, it) }
        f.bearing?.let { st.bindDouble(8, it) }
        f.bearingAcc?.let { st.bindDouble(9, it) }
        f.hAcc?.let { st.bindDouble(10, it) }
        f.vAcc?.let { st.bindDouble(11, it) }
        f.provider?.let { st.bindString(12, it) }
        st.executeInsert()
    }

    companion object {
        private val FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun rideFileName(utcMs: Long, hex8: String): String =
            "ride_${FILE_TS.format(Instant.ofEpochMilli(utcMs))}_$hex8.db"

        fun ridesDir(context: Context): File = File(context.filesDir, Config.RIDES_DIR)

        /** Creates a new ride file (one SQLite database per ride, never reused). */
        fun create(context: Context, ring: RingBuffer, utcMs: Long): RideStore {
            val dir = ridesDir(context)
            dir.mkdirs()
            val hex = (0 until 8).map { "0123456789abcdef".random() }.joinToString("")
            val file = File(dir, rideFileName(utcMs, hex))
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            configure(db)
            createSchema(db)
            return RideStore(db, file, ring)
        }

        /** In-memory store for unit tests. */
        fun inMemory(ring: RingBuffer): RideStore {
            val db = SQLiteDatabase.create(null)
            createSchema(db)
            return RideStore(db, null, ring)
        }

        private fun configure(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=NORMAL")
        }

        // Schema version 1 — mirrored in analysis/schema.md; bump schema_version on any change.
        fun createSchema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL(
                """CREATE TABLE imu (
                  t_ns   INTEGER NOT NULL,
                  stream INTEGER NOT NULL,
                  v0 REAL NOT NULL, v1 REAL, v2 REAL,
                  b0 REAL, b1 REAL, b2 REAL,
                  acc INTEGER
                )""",
            )
            db.execSQL("CREATE INDEX idx_imu ON imu(stream, t_ns)")
            db.execSQL(
                """CREATE TABLE gps (
                  t_ns INTEGER NOT NULL, t_utc_ms INTEGER NOT NULL,
                  lat REAL, lon REAL, alt REAL,
                  speed REAL, speed_acc REAL, bearing REAL, bearing_acc REAL,
                  h_acc REAL, v_acc REAL, provider TEXT
                )""",
            )
            db.execSQL("CREATE TABLE gps_status (t_ns INTEGER, sats_total INTEGER, sats_used INTEGER)")
            db.execSQL(
                """CREATE TABLE gnss_raw (t_ns INTEGER, svid INTEGER, constellation INTEGER,
                  cn0 REAL, prr REAL, prr_unc REAL)""",
            )
            db.execSQL(
                """CREATE TABLE marker (
                  t_ns INTEGER NOT NULL,
                  kind TEXT NOT NULL,
                  note TEXT
                )""",
            )
        }
    }
}
