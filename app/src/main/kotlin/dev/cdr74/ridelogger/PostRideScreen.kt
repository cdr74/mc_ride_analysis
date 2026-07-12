package dev.cdr74.ridelogger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Post-ride views (ui-mockup S3): all-dimension summary (S3a), tap a dimension for
 * the full-screen zoomable trace (S3b). Fusion runs once per ride on first open
 * ("computing…"), cached by RideAnalyzer.
 */

private val TXT_MUTED = Color(0xFF898781)
private val TRACE = Color(0xFF2A78D6)
private val GRID = Color(0xFFE1E0D9)

@Composable
fun PostRideScreen(file: File, onClose: () -> Unit) {
    var detailDim by remember { mutableStateOf<Dimension?>(null) }
    BackHandler { if (detailDim != null) detailDim = null else onClose() }

    val context = androidx.compose.ui.platform.LocalContext.current
    var analysis by remember(file) { mutableStateOf<RideAnalyzer.Analysis?>(null) }
    LaunchedEffect(file) {
        analysis = withContext(Dispatchers.IO) { RideAnalyzer.get(context, file) }
    }

    val a = analysis
    when {
        a == null -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("computing…", color = TXT_MUTED)
        }

        detailDim != null -> DimensionDetail(
            dim = detailDim!!,
            trace = a.trace(detailDim!!),
            title = rideTitle(file),
            onBack = { detailDim = null },
        )

        else -> Summary(file, a, onOpen = { detailDim = it }, onClose = onClose)
    }
}

private fun rideTitle(file: File): String {
    // ride_20260712T085807Z_ab453d68.db → 2026-07-12 08:58
    val m = Regex("ride_(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})").find(file.name)
        ?: return file.name
    val (y, mo, d, h, mi) = m.destructured
    return "$y-$mo-$d $h:$mi UTC"
}

@Composable
private fun Summary(
    file: File,
    a: RideAnalyzer.Analysis,
    onOpen: (Dimension) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("←", fontSize = 22.sp) }
            Text(rideTitle(file), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "%.1f km · %s · avg %.0f km/h moving".format(
                a.distanceKm, formatDur(a.durationS), a.avgMovingKmh,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (!a.calibrated) {
            Text(
                "No calibration available for this ride — only speed is shown.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()

        DimensionRow(Dimension.SPEED, a.trace(Dimension.SPEED), "max %.0f km/h".format(a.maxSpeedKmh), onOpen)
        for (dim in listOf(Dimension.LEAN, Dimension.ACCEL, Dimension.PITCH)) {
            val t = a.trace(dim) ?: continue
            val stats = validStats(t)
            val label = when (dim) {
                Dimension.LEAN -> "◄ %.0f° / %.0f° ►".format(abs(stats.first), stats.second)
                else -> "%.1f / +%.1f %s".format(stats.first, stats.second, dim.unit)
            }
            DimensionRow(dim, t, label, onOpen)
        }
        Text(
            "tap a dimension for the trace ▸",
            style = MaterialTheme.typography.bodySmall,
            color = TXT_MUTED,
        )
    }
}

private fun formatDur(s: Float): String {
    val sec = s.toInt()
    return "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
}

private fun validStats(t: RideAnalyzer.Trace): Pair<Float, Float> {
    var mn = 0f
    var mx = 0f
    for (v in t.v) {
        if (v.isNaN()) continue
        mn = min(mn, v)
        mx = max(mx, v)
    }
    return mn to mx
}

@Composable
private fun DimensionRow(
    dim: Dimension,
    trace: RideAnalyzer.Trace?,
    statsLabel: String,
    onOpen: (Dimension) -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(enabled = trace != null) { onOpen(dim) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dim.label, fontWeight = FontWeight.SemiBold)
                Text(statsLabel, color = TXT_MUTED)
            }
            if (trace != null) {
                val stats = validStats(trace)
                WatermarkBar(
                    dim,
                    LiveDim(
                        value = null,
                        watermarkNeg = if (dim.centerOrigin) stats.first else null,
                        watermarkPos = stats.second,
                    ),
                    height = 18,
                )
            }
        }
    }
}

/** S3b: full-screen trace — pinch to zoom the time window, drag to pan, tap = readout. */
@Composable
private fun DimensionDetail(
    dim: Dimension,
    trace: RideAnalyzer.Trace?,
    title: String,
    onBack: () -> Unit,
) {
    if (trace == null || trace.t.isEmpty()) {
        onBack(); return
    }
    val tEnd = trace.t.last()
    var win by remember { mutableStateOf(0f..tEnd) }
    var readoutIdx by remember { mutableStateOf<Int?>(null) }

    // extremes for the jump list
    val extremes = remember(trace) {
        var mnI = -1
        var mxI = -1
        for (i in trace.v.indices) {
            val v = trace.v[i]
            if (v.isNaN()) continue
            if (mnI < 0 || v < trace.v[mnI]) mnI = i
            if (mxI < 0 || v > trace.v[mxI]) mxI = i
        }
        listOfNotNull(mnI.takeIf { it >= 0 }, mxI.takeIf { it >= 0 })
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Text("${dim.label} · $title", style = MaterialTheme.typography.titleMedium)
        }

        val idx = readoutIdx
        Text(
            if (idx != null && !trace.v[idx].isNaN()) {
                "%s  %.1f %s  @ %s".format(dim.label, trace.v[idx], dim.unit, formatDur(trace.t[idx]))
            } else {
                "pinch = zoom · drag = pan · tap = value"
            },
            color = TXT_MUTED,
        )

        TraceCanvas(
            dim = dim,
            trace = trace,
            win = win,
            readoutIdx = readoutIdx,
            onWindow = { win = it },
            onTap = { readoutIdx = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        HorizontalDivider()
        Text("EXTREMES", style = MaterialTheme.typography.labelMedium, color = TXT_MUTED)
        extremes.forEach { i ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val t = trace.t[i]
                        win = max(0f, t - 15f)..min(tEnd, t + 15f)
                        readoutIdx = i
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("%.1f %s".format(trace.v[i], dim.unit), fontWeight = FontWeight.SemiBold)
                Text("@ ${formatDur(trace.t[i])}", color = TXT_MUTED)
            }
        }
    }
}

@Composable
private fun TraceCanvas(
    dim: Dimension,
    trace: RideAnalyzer.Trace,
    win: ClosedFloatingPointRange<Float>,
    readoutIdx: Int?,
    onWindow: (ClosedFloatingPointRange<Float>) -> Unit,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tEnd = trace.t.last()

    Canvas(
        modifier
            .pointerInput(tEnd) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val w = size.width.toFloat()
                    val span = win.endInclusive - win.start
                    val tCentroid = win.start + centroid.x / w * span
                    val newSpan = (span / zoom).coerceIn(5f, tEnd)
                    var start = tCentroid - (tCentroid - win.start) / zoom - pan.x / w * newSpan
                    start = start.coerceIn(0f, tEnd - newSpan)
                    onWindow(start..(start + newSpan))
                }
            }
            .pointerInput(tEnd) {
                detectTapGestures { pos ->
                    val span = win.endInclusive - win.start
                    val t = win.start + pos.x / size.width * span
                    // trace.t is (nearly) uniform — binary search for the nearest sample
                    var lo = 0
                    var hi = trace.t.size - 1
                    while (hi - lo > 1) {
                        val mid = (lo + hi) / 2
                        if (trace.t[mid] < t) lo = mid else hi = mid
                    }
                    onTap(if (t - trace.t[lo] < trace.t[hi] - t) lo else hi)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val span = (win.endInclusive - win.start).coerceAtLeast(1e-3f)

        // y-range from the visible window
        var lo = 0
        while (lo < trace.t.size && trace.t[lo] < win.start) lo++
        var hi = lo
        var yMax = 5f
        var yMin = if (dim.centerOrigin) -5f else 0f
        while (hi < trace.t.size && trace.t[hi] <= win.endInclusive) {
            val v = trace.v[hi]
            if (!v.isNaN()) {
                if (dim.centerOrigin) {
                    yMax = max(yMax, abs(v)); yMin = -yMax
                } else {
                    yMax = max(yMax, v)
                }
            }
            hi++
        }
        if (dim.centerOrigin) yMin = -yMax
        fun px(t: Float) = (t - win.start) / span * w
        fun py(v: Float) = h - (v - yMin) / (yMax - yMin) * h

        // grid: zero line (center dims) + top/bottom quarter lines
        drawLine(GRID, Offset(0f, py(yMax * 0.5f + yMin * 0.5f)), Offset(w, py(yMax * 0.5f + yMin * 0.5f)))
        if (dim.centerOrigin) {
            drawLine(TXT_MUTED, Offset(0f, py(0f)), Offset(w, py(0f)), strokeWidth = 2f)
        }

        // trace polyline with NaN gaps, stride-decimated to ~2 points per pixel
        val visible = hi - lo
        val stride = max(1, visible / (2 * w.toInt().coerceAtLeast(1)))
        val path = Path()
        var pen = false
        var i = lo
        while (i < hi) {
            val v = trace.v[i]
            if (v.isNaN()) {
                pen = false
            } else {
                val x = px(trace.t[i])
                val y = py(v)
                if (pen) path.lineTo(x, y) else path.moveTo(x, y)
                pen = true
            }
            i += stride
        }
        drawPath(path, TRACE, style = Stroke(width = 3f))

        // readout marker
        if (readoutIdx != null && readoutIdx in lo until hi && !trace.v[readoutIdx].isNaN()) {
            val x = px(trace.t[readoutIdx])
            drawLine(TXT_MUTED, Offset(x, 0f), Offset(x, h))
            drawCircle(TRACE, radius = 8f, center = Offset(x, py(trace.v[readoutIdx])))
            drawCircle(Color.White, radius = 4f, center = Offset(x, py(trace.v[readoutIdx])))
        }

        // window position indicator (thin strip at the bottom)
        drawRect(GRID, Offset(0f, h - 4f), Size(w, 4f))
        drawRect(TRACE, Offset(win.start / tEnd * w, h - 4f), Size(span / tEnd * w, 4f))
    }
}
