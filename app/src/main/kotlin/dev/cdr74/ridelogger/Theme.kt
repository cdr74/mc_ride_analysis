package dev.cdr74.ridelogger

import androidx.compose.ui.graphics.Color

/**
 * UI palette (one place, like Config for tunables). Direction per Chris's 0.3.1 review:
 * modern but clean, high contrast, white ground — chosen for sunlight readability on
 * the bike. Ink-dark structure, one vivid accent, semantic amber/red reserved for
 * "near your session max".
 */
object Ui {
    val Ink = Color(0xFF0F172A) // primary text, center lines, watermark ticks
    val Muted = Color(0xFF64748B) // labels, secondary text
    val Track = Color(0xFFE2E8F0) // bar tracks, minor gridlines
    val TrackEdge = Color(0xFFCBD5E1) // track border, labeled gridlines
    val Accent = Color(0xFF2563EB) // bar fills, traces
    val AccentSoft = Color(0xFF93C5FD) // secondary fills (summary range bars)
    val NearMax = Color(0xFFD97706) // fill past 85 % of session watermark
    val AtMax = Color(0xFFDC2626) // fill past 97 %
    val Stop = Color(0xFFDC2626)
    val Start = Color(0xFF16A34A)
}
