package dev.cdr74.ridelogger

import androidx.compose.ui.graphics.Color

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
    /**
     * Minimum autoscale half-span (max for edge-origin, minimum total span for
     * free-range) in the trace view — the y-scale never shrinks below this, so ordinary
     * wander on a straight doesn't fill the screen (0.3.3 review: ±2° of real steering
     * wander on a ±5 scale read as "twitchy").
     */
    val calmFloor: Float,
    /** Selectable as a live bar slot; post-ride-only dimensions never enter the picker. */
    val live: Boolean = true,
    /** Trace y-range follows the visible window's min..max instead of an origin. */
    val freeRange: Boolean = false,
    /** Per-dimension identity color (Theme.kt DimColor). */
    val color: Color = Color.Unspecified,
) {
    LEAN("LEAN", "°", 45f, true, 5f, 15f, color = DimColor.Lean),
    ACCEL("ACCEL / BRAKE", "m/s²", 10f, true, 2f, 3f, color = DimColor.Accel),
    PITCH("PITCH", "°", 20f, true, 5f, 10f, color = DimColor.Pitch),
    SPEED("SPEED", "km/h", 120f, false, 20f, 60f, color = DimColor.Speed),
    ELEVATION("ELEVATION", "m", 200f, false, 10f, 40f, live = false, freeRange = true, color = DimColor.Elevation),
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
        Dimension.ELEVATION -> LiveDim() // post-ride only, never a live slot
    }
}
