package dev.cdr74.ridelogger

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Per-dimension identity colors ──────────────────────────────────────────

object DimColor {
    val Lean      = Color(0xFFFF4500)   // deep orange-red
    val Speed     = Color(0xFF0D8FFF)   // electric blue
    val Accel     = Color(0xFF00C853)   // vivid green  (positive = power)
    val Brake     = Color(0xFFFF1744)   // vivid red    (negative = decel)
    val Pitch     = Color(0xFF00D4CC)   // teal-cyan
    val Elevation = Color(0xFF8B5CF6)   // violet
}

// ── Safety gradient breakpoints ────────────────────────────────────────────

object SafetyGradient {
    val Warn   = Color(0xFFFFAB00)  // amber  70–90 % of session max
    val Danger = Color(0xFFFF1744)  // red    >90 %
}

// ── Theme data ─────────────────────────────────────────────────────────────

data class RideColors(
    val background: Color,
    val surface: Color,
    val track: Color,        // empty segment color
    val textPrimary: Color,
    val textMuted: Color,
    val divider: Color,
    val watermark: Color,    // segment that marks session max
    val startFill: Color = Color(0xFF16A34A),
    val stopFill:  Color = Color(0xFFDC2626),
    val isDark: Boolean,
)

val DarkRideColors = RideColors(
    background  = Color(0xFF111318),
    surface     = Color(0xFF1C1F2B),
    track       = Color(0xFF252934),
    textPrimary = Color(0xFFE8ECF4),
    textMuted   = Color(0xFF6B7280),
    divider     = Color(0xFF252934),
    watermark   = Color.White,
    isDark      = true,
)

val LightRideColors = RideColors(
    background  = Color(0xFFF4F6FA),
    surface     = Color(0xFFFFFFFF),
    track       = Color(0xFFDDE1EA),
    textPrimary = Color(0xFF111318),
    textMuted   = Color(0xFF9CA3AF),
    divider     = Color(0xFFDDE1EA),
    watermark   = Color(0xFF111318),
    isDark      = false,
)

val LocalRideColors = compositionLocalOf { DarkRideColors }

// ── Theme composable ───────────────────────────────────────────────────────

enum class ThemeOverride { SYSTEM, DARK, LIGHT }

@Composable
fun RideLoggerTheme(override: ThemeOverride, content: @Composable () -> Unit) {
    val isDark = when (override) {
        ThemeOverride.DARK   -> true
        ThemeOverride.LIGHT  -> false
        ThemeOverride.SYSTEM -> isSystemInDarkTheme()
    }
    val rideColors = if (isDark) DarkRideColors else LightRideColors
    val colorScheme = if (isDark) darkColorScheme(
        background   = DarkRideColors.background,
        surface      = DarkRideColors.surface,
        onBackground = DarkRideColors.textPrimary,
        onSurface    = DarkRideColors.textPrimary,
    ) else lightColorScheme(
        background   = LightRideColors.background,
        surface      = LightRideColors.surface,
        onBackground = LightRideColors.textPrimary,
        onSurface    = LightRideColors.textPrimary,
    )
    CompositionLocalProvider(LocalRideColors provides rideColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

// ── Legacy palette (kept for internal compatibility during transition) ──────
// All new code should use LocalRideColors / DimColor; these survive only as a
// backstop so any file we miss still compiles.
@Deprecated("Use LocalRideColors or DimColor", level = DeprecationLevel.WARNING)
object Ui {
    val Ink        = DarkRideColors.textPrimary
    val Muted      = DarkRideColors.textMuted
    val Track      = DarkRideColors.track
    val TrackEdge  = Color(0xFF3A3F50)
    val Accent     = DimColor.Speed
    val AccentSoft = DimColor.Speed.copy(alpha = 0.5f)
    val NearMax    = SafetyGradient.Warn
    val AtMax      = SafetyGradient.Danger
    val Stop       = DarkRideColors.stopFill
    val Start      = DarkRideColors.startFill
}
