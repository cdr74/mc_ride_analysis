package dev.cdr74.ridelogger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Live ride display (docs/ui-mockup.md S2): two chosen dimensions, each as a huge
 * numeral plus a SegmentBar with session high-watermark; a plain STOP button below.
 * All values arrive via [LiveMetrics]; a null value renders as an empty bar with "—".
 */

private const val SEGS_PER_SIDE = 10   // center-origin: 10 left + 10 right
private const val EDGE_SEGS     = 18   // edge-origin (speed, elevation)

private fun segmentFillColor(
    segFrac: Float,        // this segment's position as fraction 0..1 of range (from origin)
    sessionMaxFrac: Float?,
    identity: Color,
): Color {
    val max = sessionMaxFrac ?: return identity
    if (max < 1e-3f) return identity
    return when {
        segFrac / max > 0.90f -> SafetyGradient.Danger
        segFrac / max > 0.70f -> SafetyGradient.Warn
        else -> identity
    }
}

@Composable
fun SegmentBar(dim: Dimension, live: LiveDim, modifier: Modifier = Modifier, height: Int = 44) {
    val colors = LocalRideColors.current
    // For ACCEL/BRAKE: positive side uses Accel color, negative side uses Brake color
    val identityPos = if (dim == Dimension.ACCEL) DimColor.Accel else dim.color
    val identityNeg = if (dim == Dimension.ACCEL) DimColor.Brake else dim.color

    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val w = size.width
        val h = size.height
        val v = live.value

        if (dim.centerOrigin) {
            val totalSegs = SEGS_PER_SIDE * 2
            val segW = w / totalSegs

            val filledR = if (v != null && v > 0)  ((v  / dim.range) * SEGS_PER_SIDE).toInt().coerceIn(0, SEGS_PER_SIDE) else 0
            val filledL = if (v != null && v < 0)  ((-v / dim.range) * SEGS_PER_SIDE).toInt().coerceIn(0, SEGS_PER_SIDE) else 0

            val wmPosFrac = live.watermarkPos?.let { it / dim.range }
            val wmNegFrac = live.watermarkNeg?.let { (-it) / dim.range }
            val wmPosSeg  = wmPosFrac?.let { (it * SEGS_PER_SIDE).toInt().coerceIn(0, SEGS_PER_SIDE - 1) }
            val wmNegSeg  = wmNegFrac?.let { (it * SEGS_PER_SIDE).toInt().coerceIn(0, SEGS_PER_SIDE - 1) }

            val summaryR = if (v == null) wmPosFrac?.let { (it * SEGS_PER_SIDE).toInt().coerceIn(0, SEGS_PER_SIDE) } ?: 0 else 0
            val summaryL = if (v == null) wmNegFrac?.let { (it * SEGS_PER_SIDE).toInt().coerceIn(0, SEGS_PER_SIDE) } ?: 0 else 0

            for (i in 0 until totalSegs) {
                val isRight = i >= SEGS_PER_SIDE
                val dist = if (isRight) i - SEGS_PER_SIDE else (SEGS_PER_SIDE - 1 - i)
                val segFrac = (dist + 0.5f) / SEGS_PER_SIDE

                val filled        = if (isRight) filledR else filledL
                val summary       = if (isRight) summaryR else summaryL
                val wmFrac        = if (isRight) wmPosFrac else wmNegFrac
                val wmSeg         = if (isRight) wmPosSeg  else wmNegSeg
                val identityColor = if (isRight) identityPos else identityNeg

                val color = when {
                    v != null && dist < filled ->
                        segmentFillColor(segFrac, wmFrac, identityColor)
                    v != null && wmSeg != null && dist == wmSeg && dist >= filled ->
                        colors.watermark
                    v == null && dist < summary ->
                        identityColor
                    v == null && wmSeg != null && dist == summary && dist == wmSeg ->
                        colors.watermark
                    else -> colors.track
                }
                drawRect(color, Offset(i * segW, 0f), Size(segW, h))
            }
        } else {
            val segW = w / EDGE_SEGS
            val filled  = if (v != null) ((v / dim.range) * EDGE_SEGS).toInt().coerceIn(0, EDGE_SEGS) else 0
            val wmFrac  = live.watermarkPos?.let { it / dim.range }
            val wmSeg   = wmFrac?.let { (it * EDGE_SEGS).toInt().coerceIn(0, EDGE_SEGS - 1) }
            val summaryFill = if (v == null) wmFrac?.let { (it * EDGE_SEGS).toInt().coerceIn(0, EDGE_SEGS) } ?: 0 else 0

            for (i in 0 until EDGE_SEGS) {
                val segFrac = (i + 0.5f) / EDGE_SEGS
                val color = when {
                    v != null && i < filled -> segmentFillColor(segFrac, wmFrac, identityPos)
                    v != null && wmSeg != null && i == wmSeg && i >= filled -> colors.watermark
                    v == null && i < summaryFill -> identityPos
                    v == null && wmSeg != null && i == summaryFill && i == wmSeg -> colors.watermark
                    else -> colors.track
                }
                drawRect(color, Offset(i * segW, 0f), Size(segW, h))
            }
        }
    }
}

/** Thin alias so PostRideScreen call sites continue to compile unchanged. */
@Composable
fun WatermarkBar(dim: Dimension, live: LiveDim, height: Int = 44) =
    SegmentBar(dim = dim, live = live, height = height)

@Composable
fun LiveDisplayScreen(
    slots: List<Dimension?>,
    metrics: LiveMetrics,
    diagLine: String?,
    onStop: () -> Unit,
) {
    val colors = LocalRideColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (diagLine != null) {
            Text(diagLine, fontSize = 12.sp, color = colors.textMuted)
        }
        slots.filterNotNull().ifEmpty { listOf(Dimension.SPEED) }.forEachIndexed { i, dim ->
            if (i > 0) HorizontalDivider(color = colors.divider)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                DimensionSlot(dim, metrics[dim], metrics.calibrated)
            }
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.stopFill),
        ) {
            Text("STOP", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun DimensionSlot(dim: Dimension, live: LiveDim, calibrated: Boolean = true) {
    val colors = LocalRideColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val hint = if (!calibrated && dim != Dimension.SPEED) " · calibrating…" else ""
        Text(dim.label + hint, fontSize = 14.sp, color = colors.textMuted, letterSpacing = 2.sp)
        Text(
            text = numeral(dim, live.value),
            fontSize = 84.sp,
            fontWeight = FontWeight.Black,
            color = if (live.value == null) colors.textMuted else dim.color,
        )
        SegmentBar(dim, live)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val left = if (dim.centerOrigin) "${dim.range.toInt()}" else "0"
            Text(left, fontSize = 12.sp, color = colors.textMuted)
            if (dim.centerOrigin) Text("0", fontSize = 12.sp, color = colors.textMuted)
            Text("${dim.range.toInt()} ${dim.unit}", fontSize = 12.sp, color = colors.textMuted)
        }
    }
}

private fun numeral(dim: Dimension, v: Float?): String = when {
    v == null -> "—"
    dim == Dimension.SPEED -> "${v.toInt()}"
    dim == Dimension.LEAN -> (if (v < 0) "◄ " else "► ") + "${abs(v).toInt()}°"
    else -> "%+.1f".format(v)
}
