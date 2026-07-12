package dev.cdr74.ridelogger

/**
 * The four displayable ride dimensions (docs/ui-mockup.md S2). Every dimension renders
 * with the same bar grammar: a fill bar on a recessed track plus session high-watermark
 * ticks. Center-origin bars grow both ways from the middle; edge-origin grows from 0.
 */
enum class Dimension(
    val label: String,
    val unit: String,
    /** Full-scale value: center-origin bars span ±range, edge-origin 0..range. */
    val range: Float,
    val centerOrigin: Boolean,
    /** Horizontal gridline spacing in the trace view (0.3.1 review: e.g. every 5° lean). */
    val gridStep: Float,
) {
    LEAN("LEAN", "°", 45f, true, 5f),
    ACCEL("ACCEL / BRAKE", "m/s²", 10f, true, 2f),
    PITCH("PITCH", "°", 20f, true, 5f),
    SPEED("SPEED", "km/h", 120f, false, 20f),
    ;

    companion object {
        fun fromName(name: String?): Dimension? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Live value of one dimension. `value == null` means "not available" (no GPS fix yet,
 * calibration pending, or below the lean speed cutoff) — the bar renders empty with "—".
 * Watermarks are per-ride session maxima; they move instantly and never retreat
 * (reset at ride start).
 */
data class LiveDim(
    val value: Float? = null,
    val watermarkNeg: Float? = null, // most-negative value so far (center-origin only)
    val watermarkPos: Float? = null, // most-positive value so far
) {
    fun update(v: Float): LiveDim = LiveDim(
        value = v,
        watermarkNeg = if (v < 0 && (watermarkNeg == null || v < watermarkNeg)) v else watermarkNeg,
        watermarkPos = if (v > 0 && (watermarkPos == null || v > watermarkPos)) v else watermarkPos,
    )
}

data class LiveMetrics(
    val speed: LiveDim = LiveDim(),
    val lean: LiveDim = LiveDim(),
    val accel: LiveDim = LiveDim(),
    val pitch: LiveDim = LiveDim(),
    /** False until a phone→bike calibration (persisted or solved this ride) exists. */
    val calibrated: Boolean = false,
) {
    operator fun get(dim: Dimension): LiveDim = when (dim) {
        Dimension.SPEED -> speed
        Dimension.LEAN -> lean
        Dimension.ACCEL -> accel
        Dimension.PITCH -> pitch
    }
}
