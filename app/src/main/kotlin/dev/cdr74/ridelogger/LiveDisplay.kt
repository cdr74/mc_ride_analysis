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

private val BAR_FILL = Color(0xFF2A78D6)
private val BAR_NEAR = Color(0xFFC98500) // fill past 85 % of the session watermark
private val BAR_AT = Color(0xFFD03B3B) // fill past 97 %
private val BAR_TRACK = Color(0xFFE9E8E2)
private val BAR_TICK = Color(0xFF0B0B0B)
private val TXT_MUTED = Color(0xFF898781)

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
            Text(diagLine, fontSize = 12.sp, color = TXT_MUTED)
        }
        slots.filterNotNull().ifEmpty { listOf(Dimension.SPEED) }.forEachIndexed { i, dim ->
            if (i > 0) HorizontalDivider()
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                DimensionSlot(dim, metrics[dim])
            }
        }
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
        ) {
            Text("STOP", fontSize = 26.sp)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun DimensionSlot(dim: Dimension, live: LiveDim) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(dim.label, fontSize = 14.sp, color = TXT_MUTED, letterSpacing = 2.sp)
        Text(
            text = numeral(dim, live.value),
            fontSize = 84.sp,
            fontWeight = FontWeight.Black,
            color = if (live.value == null) TXT_MUTED else Color(0xFF0B0B0B),
        )
        WatermarkBar(dim, live)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val left = if (dim.centerOrigin) "${dim.range.toInt()}" else "0"
            Text(left, fontSize = 12.sp, color = TXT_MUTED)
            if (dim.centerOrigin) Text("0", fontSize = 12.sp, color = TXT_MUTED)
            Text("${dim.range.toInt()} ${dim.unit}", fontSize = 12.sp, color = TXT_MUTED)
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
 * The unified bar (ui-mockup S2): recessed track, fill from center or left edge,
 * 2 dp session-watermark ticks that never retreat. The fill shifts color as it
 * approaches the watermark on its side (subtle "near your max" cue, Q1b).
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
        val r = CornerRadius(4.dp.toPx())
        // fraction of full width for a value
        fun frac(v: Float): Float = if (dim.centerOrigin) {
            0.5f + (v / dim.range) * 0.5f
        } else {
            v / dim.range
        }.coerceIn(0f, 1f)

        drawRoundRect(BAR_TRACK, size = Size(w, h), cornerRadius = r)

        val origin = if (dim.centerOrigin) 0.5f else 0f
        val v = live.value
        if (v != null) {
            val f = frac(v)
            val x0 = minOf(origin, f) * w
            val x1 = maxOf(origin, f) * w
            val mark = if (v >= 0) live.watermarkPos else live.watermarkNeg
            val ratio = if (mark != null && abs(mark) > 1e-3) abs(v) / abs(mark) else 0f
            val fill = when {
                ratio > 0.97f -> BAR_AT
                ratio > 0.85f -> BAR_NEAR
                else -> BAR_FILL
            }
            drawRoundRect(fill, topLeft = Offset(x0, 0f), size = Size(x1 - x0, h), cornerRadius = r)
        }

        if (dim.centerOrigin) {
            drawRect(BAR_TICK.copy(alpha = 0.35f), topLeft = Offset(w / 2 - 1, 0f), size = Size(2f, h))
        }
        for (mark in listOfNotNull(live.watermarkNeg, live.watermarkPos)) {
            val x = frac(mark) * w
            drawRect(BAR_TICK, topLeft = Offset(x - 2f, 0f), size = Size(4f, h))
        }
    }
}
