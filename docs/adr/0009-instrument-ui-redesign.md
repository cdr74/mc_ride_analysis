# ADR 0009 — Instrument UI redesign (v0.5.0)

**Date:** 2026-07-19  
**Status:** Accepted

---

## Context

The v0.4.x UI was functional but visually plain — flat blue bars on a white
background, no sense of identity or urgency. Field use showed two friction
points: (1) it was hard to read at a glance with gloves on, and (2) it didn't
feel like a precision instrument, which undermined trust in the numbers.

The design goal was "modern, minimalist but not boring; professional, glove-usable,
fun to use" — inspired by high-end motorcycle dashboards and motorsport data
loggers.

---

## Decision

**Instrument design** (spec: `docs/ui-mockup.md`). Key choices:

### Visual language
**LED-strip segment bars** — solid color-blocks side-by-side with a hard boundary
between filled and empty, like a rev-counter redline strip. No gaps between
segments. ~10 segments per side (center-origin) or ~18–20 total (edge-origin
speed). The strip is the axis; no grid lines.

### Per-dimension identity colors
Each dimension has one saturated color. You never need to read the label.

| Dimension | Color | Hex |
|---|---|---|
| LEAN | deep orange-red | `#FF4500` |
| SPEED | electric blue | `#0D8FFF` |
| ACCEL | vivid green | `#00C853` |
| BRAKE | vivid red | `#FF1744` |
| PITCH | teal-cyan | `#00D4CC` |
| ELEVATION | violet | `#8B5CF6` |

### Safety gradient on all bars
Bars shift color as you approach your session maximum — identity color up to 70 %,
amber (`#FFAB00`) from 70–90 %, red (`#FF1744`) above 90 %. The ◆ watermark marks
the session high. This replaces the previous static thin tick.

### Dark/light theme
Default follows system dark mode. ☾/☀ toggle on the home screen persists a
manual override (`ThemeOverride`: SYSTEM / DARK / LIGHT).

### Button form
Full-width solid blocks, sharp edges (no rounding). START = deep green
(`#16A34A`), STOP = deep red (`#DC2626`). Large tap targets (96 dp / 80 dp),
glove-usable.

### App icon
Orange needle arc on a dark disc — no text, no numbers. Adaptive icon with
background + foreground vector layers. Recognisable at notification-icon size.

---

## Also in v0.5.0

**Float32 storage (schema v2):** IMU columns changed from `REAL` (SQLite 64-bit
float) to `INTEGER` (bit-pattern encoding via `Float.toBits()`). Saves ~40 % on
the dominant imu table with zero accuracy loss (sensor values are already float32
by hardware). Python analysis decodes with `struct.unpack('<f', struct.pack('<i', int(v)))`.

---

## Consequences

- All UI code (`LiveDisplay.kt`, `PostRideScreen.kt`, `MainActivity.kt`) updated to
  the new design. `Theme.kt` is the single source of truth for all color tokens.
- `SegmentBar` is a unified composable for all dimensions; the safety gradient is
  computed inside it.
- `CACHE_VERSION` in `RideAnalyzer` not bumped — analysis data format unchanged.
- Existing ride files are incompatible if schema_version=1 (float storage). The
  validator accepts both v1 and v2; v1 files display with undefined float values.
  Users upgrading from v0.4.x with a v1 file should re-record or keep the old APK
  for viewing older rides.
