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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Live ride display (docs/ui-mockup.md S2): two chosen dimensions, each as a huge
 * numeral plus a fill bar with session high-watermark ticks; a plain STOP button below.
 * All values arrive via [LiveMetrics]; a null value renders as an empty bar with "—"
 * (no GPS fix yet, calibration pending, or lean below the 18 km/h cutoff).
 */


@Composable
fun LiveDisplayScreen(
    slots: List<Dimension?>,
    metrics: LiveMetrics,
    diagLine: String?,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (diagLine != null) {
            Text(diagLine, fontSize = 12.sp, color = Ui.Muted)
        }
        slots.filterNotNull().ifEmpty { listOf(Dimension.SPEED) }.forEachIndexed { i, dim ->
            if (i > 0) HorizontalDivider()
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                DimensionSlot(dim, metrics[dim], metrics.calibrated)
            }
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ui.Stop),
        ) {
            Text("STOP", fontSize = 26.sp)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun DimensionSlot(dim: Dimension, live: LiveDim, calibrated: Boolean = true) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val hint = if (!calibrated && dim != Dimension.SPEED) " · calibrating…" else ""
        Text(dim.label + hint, fontSize = 14.sp, color = Ui.Muted, letterSpacing = 2.sp)
        Text(
            text = numeral(dim, live.value),
            fontSize = 84.sp,
            fontWeight = FontWeight.Black,
            color = if (live.value == null) Ui.Muted else Ui.Ink,
        )
        WatermarkBar(dim, live)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val left = if (dim.centerOrigin) "${dim.range.toInt()}" else "0"
            Text(left, fontSize = 12.sp, color = Ui.Muted)
            if (dim.centerOrigin) Text("0", fontSize = 12.sp, color = Ui.Muted)
            Text("${dim.range.toInt()} ${dim.unit}", fontSize = 12.sp, color = Ui.Muted)
        }
    }
}

private fun numeral(dim: Dimension, v: Float?): String = when {
    v == null -> "—"
    dim == Dimension.SPEED -> "${v.toInt()}"
    dim == Dimension.LEAN -> (if (v < 0) "◄ " else "► ") + "${abs(v).toInt()}°"
    else -> "%+.1f".format(v)
}

/**
 * The unified bar (ui-mockup S2): bordered track, fill from center or left edge,
 * session-watermark ticks that never retreat. The fill shifts color as it approaches
 * the watermark on its side ("near your max", Q1b). Per the 0.3.1 review: the center
 * origin is a pronounced ink line protruding above/below the track, and when no live
 * value exists but watermarks do (post-ride summary rows), the bar shows solid range
 * fills from the origin out to each extreme instead of bare tick lines.
 */
@Composable
fun WatermarkBar(dim: Dimension, live: LiveDim, height: Int = 44) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height.dp),
    ) {
        val w = size.width
        val h = size.height
        val inset = 4.dp.toPx() // track is inset so the center line can protrude
        val trackH = h - 2 * inset
        val r = CornerRadius(4.dp.toPx())
        // fraction of full width for a value
        fun frac(v: Float): Float = if (dim.centerOrigin) {
            0.5f + (v / dim.range) * 0.5f
        } else {
            v / dim.range
        }.coerceIn(0f, 1f)

        drawRoundRect(Ui.Track, topLeft = Offset(0f, inset), size = Size(w, trackH), cornerRadius = r)
        drawRoundRect(
            Ui.TrackEdge, topLeft = Offset(0f, inset), size = Size(w, trackH),
            cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        )

        val origin = if (dim.centerOrigin) 0.5f else 0f
        val v = live.value

        fun fill(from: Float, to: Float, color: Color) {
            val x0 = minOf(from, to) * w
            val x1 = maxOf(from, to) * w
            drawRoundRect(color, topLeft = Offset(x0, inset), size = Size(x1 - x0, trackH), cornerRadius = r)
        }

        if (v != null) {
            val mark = if (v >= 0) live.watermarkPos else live.watermarkNeg
            val ratio = if (mark != null && abs(mark) > 1e-3) abs(v) / abs(mark) else 0f
            val color = when {
                ratio > 0.97f -> Ui.AtMax
                ratio > 0.85f -> Ui.NearMax
                else -> Ui.Accent
            }
            fill(origin, frac(v), color)
        } else {
            // summary mode: show the session's reach as solid bars, not hairlines
            live.watermarkNeg?.let { fill(origin, frac(it), Ui.AccentSoft) }
            live.watermarkPos?.let { fill(origin, frac(it), Ui.Accent) }
        }

        for (mark in listOfNotNull(live.watermarkNeg, live.watermarkPos)) {
            val x = frac(mark) * w
            drawRect(Ui.Ink, topLeft = Offset(x - 2f, inset), size = Size(4f, trackH))
        }

        if (dim.centerOrigin) {
            // pronounced origin: full-height ink line, protrudes past the track
            drawRect(Ui.Ink, topLeft = Offset(w / 2 - 2f, 0f), size = Size(4f, h))
        }
    }
}
