package dev.cdr74.ridelogger

/** All tunables in one place (design.md §8: no settings screen in MVP). */
object Config {
    const val SCHEMA_VERSION = 1

    // Stream discriminators for the imu table (design.md §5.2).
    const val STREAM_ACCEL = 0
    const val STREAM_GYRO = 1
    const val STREAM_MAG = 2
    const val STREAM_ROTVEC = 3
    const val STREAM_BARO = 4
    const val STREAM_COUNT = 5

    val STREAM_NAMES = arrayOf("accel", "gyro", "mag", "rotvec", "baro")

    /** Ring buffer capacity, must be a power of two (design.md §3.2). */
    const val RING_CAPACITY = 1 shl 16

    // Writer commits a transaction every <= 500 ms or >= 4096 rows (design.md §5.1).
    const val MAX_BATCH_ROWS = 4096
    const val FLUSH_INTERVAL_MS = 500L

    /** Delivered rates are measured over this window and written to meta (design.md §3.1). */
    const val RATE_MEASURE_WINDOW_MS = 10_000L

    const val RIDES_DIR = "rides"

    /** Free-text mount description recorded in meta (design.md §5.3). Must match the real mount. */
    const val MOUNT_DESCRIPTION =
        "SP Connect bar mount + anti-vibration module, top pointing forward, " +
            "screen tilted up ~TFT angle, bars straight at calib"

    /** Below this measured accel rate the UI warns about the mic privacy toggle cap (design.md §11). */
    const val LOW_RATE_WARN_HZ = 210.0

    // Guided hands-free calibration: phase detection from GPS speed (ADR 0003).
    // Thresholds only need to *bracket* the maneuvers — exact segments are solved offline.
    const val CALIB_STATIC_MAX_MPS = 0.8         // below this = stationary (GPS speed noise floor)
    const val CALIB_STATIC_CONFIRM_FIXES = 3     // consecutive still fixes before the hold starts
    const val CALIB_STATIC_DURATION_NS = 10_000_000_000L
    const val CALIB_MOVE_MIN_MPS = 1.5
    const val CALIB_ACCEL_START_MPS2 = 2.0       // hard launch detection
    const val CALIB_ACCEL_PLATEAU_MPS2 = 0.5     // accel considered over when below this
    const val CALIB_ACCEL_MIN_NS = 3_000_000_000L
    const val CALIB_ACCEL_MAX_NS = 15_000_000_000L
    const val CALIB_BRAKE_START_MPS2 = -2.0      // hard braking detection
    const val CALIB_BRAKE_EASE_MPS2 = -0.5       // braking considered over when above this
    const val CALIB_BRAKE_MIN_NS = 2_000_000_000L
    const val CALIB_BRAKE_MAX_NS = 12_000_000_000L
    const val CALIB_LEAD_IN_NS = 2_000_000_000L  // backdate start markers to cover 1 Hz detection lag
}
