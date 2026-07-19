# UI mockup — RideLogger "Instrument" redesign

Status: **implemented in v0.5.0 (2026-07-19).** This is the UI spec of record.
Any future UI change goes through this document first. The previous MVP spec (v0.3.0–v0.4.1)
is preserved in git history; this supersedes it entirely.

---

## Design brief

Modern, minimalist, professional. Inspired by high-end motorcycle dashboards and motorsport
data loggers — precision instruments, not consumer apps. Glove-usable for all live-ride
interactions. Post-ride review may use normal finger interaction.

**Five rules:**
1. Three things per screen while riding: value, bar, label. Nothing else.
2. Color = identity. Each dimension has one color; you never need to read the label.
3. Bar color = safety state. Segments shift amber → red as you approach your session max.
4. Buttons are bold solid blocks — full-width, sharp-edged, color = meaning.
5. Two type weights only: heavy for numbers, light+uppercase for labels.

---

## Color tokens

```
                        DARK            LIGHT
background:          #111318          #F4F6FA
surface (cards):     #1C1F2B          #FFFFFF
track (empty seg):   #252934          #DDE1EA
text-primary:        #E8ECF4          #111318
text-muted:          #6B7280          #9CA3AF
divider:             #252934          #DDE1EA

─── per-dimension identity ─────────────────────────────
LEAN:   #FF4500   deep orange-red    (heat, cornering risk)
SPEED:  #0D8FFF   electric blue      (neutral, informational)
ACCEL:  #00C853   vivid green        (power / forward)
BRAKE:  #FF1744   vivid red          (deceleration / rearward)
PITCH:  #00D4CC   teal-cyan
ELEV:   #8B5CF6   violet             (post-ride only, not a live channel)

─── safety gradient (bar segments, all dimensions) ──────
  0 – 70 % of session max  →  identity color (full opacity)
 70 – 90 % of session max  →  #FFAB00  amber
 90 – 100 %                →  #FF1744  red
 new session max set       →  brief brightness pulse, then watermark advances

─── action colors ───────────────────────────────────────
START button fill:  #16A34A  deep green
STOP button fill:   #DC2626  deep red
```

All identity colors are saturated enough to read at full contrast on both backgrounds.
The amber → red safety gradient is theme-independent.

---

## Segment bar grammar

One unified bar type for all dimensions.

**Style: LED strip.** No gaps between segments — segments are solid color blocks
side-by-side, like a rev counter redline strip. The boundary between filled and empty
is a hard color change, not a gap. ~10 segments per side for center-origin dimensions,
~18–20 total for edge-origin (speed).

```
filled (safe):   ████  identity color
filled (warn):   ████  #FFAB00 amber
filled (danger): ████  #FF1744 red
empty:           ████  track color (#252934 dark / #DDE1EA light)
watermark ◆:     ████  white on dark / near-black on light  (replaces one empty segment)
```

Two origin types — same strip, different fill direction:

- **Center-origin** (lean, accel/brake, pitch): strip sits at center when neutral,
  grows left or right. Watermark tick on each side (leftmost reached, rightmost reached).
  Left half = left/brake/dive; right half = right/accel/wheelie.

- **Edge-origin** (speed): strip grows from left edge. One watermark on the right.

Scale labels sit below the strip in muted small type. No axis lines — the strip IS the axis.

---

## Theme

**Default: follows system dark mode** (Android automatic day/night switching).
**Manual override:** ☾ / ☀ icon in the top-right of the home screen. Persisted.
One tap switches; tapping again returns to system-follow.

---

## Screen map

```
Home ──START──▶ Live display (screen-on mode)   ──STOP──▶ Post-ride summary
  │        └──▶ Background capture (notification) ──┘           │
  ├── ride list ──tap──▶ Post-ride summary ──tap dim──▶ trace    │
  └── long-press ride: delete (confirm) / share*          *dev builds only
```

No settings screen. Every choice lives where it is used.

---

## S1 · Home

```
┌──────────────────────────────┐
│  RIDELOGGER             ☾    │   app name 14sp · ☾/☀ 24dp tap target, muted
│                              │
│  ┌────────────────────────┐  │
│  │                        │  │   START — #16A34A fill · sharp edges · full width
│  │         START          │  │   28sp Bold · white · 96dp tall
│  │                        │  │   entire card is the tap target
│  └────────────────────────┘  │
│                              │
│  LIVE DISPLAY                │   11sp · muted · all-caps · tracked
│  ┌──────────┐  ┌──────────┐  │
│  │ ● LEAN   │  │ ● SPEED  │  │   ● = 8dp identity-color dot
│  └──────────┘  └────���─────┘  │   chip: 44dp tall · surface bg · tap cycles dimension
│  tap chips to change         │   11sp hint · muted (discoverable)
│                              │
│  CAPTURE  ◉ live  ○ bg       │   radio rows · 44dp min height
│                              │
│──────────────────────────────│   1px divider
│  RIDES                       │   11sp · muted · all-caps
│  Today    09:58   21 km  28m │   tap → post-ride · long-press → delete / share
│  Today    07:44   34 km  47m │
│  Jul 11   18:02   12 km  19m │
└──────────────────────────────┘
```

Startup states (unchanged from v0.4.x):
- **Initializing…** shown while sensors spin up and GPS acquires first fix. START absent.
- **START appears** when all checks pass — its appearance is the ready signal.
- **Error screen** lists each failed item and links to the relevant system setting.
- Dev-build diagnostics (Hz, drops) remain dev-only.

---

## S2 · Live display

Portrait, split horizontally. Two slots; each slot fills its half. Slot content is
determined by the LIVE DISPLAY picker on the home screen. Slot taps do nothing while
moving — only STOP is interactive.

```
┌──────────────────────────────┐
│                              │
│  ◄  38                       │   64sp Bold · identity color (#FF4500 for lean)
│  LEAN                    °   │   "LEAN" 10sp · muted · all-caps · "°" unit suffix
│                              │
│  ████████████████████░░░░░░░ │   LED strip · left=left lean, right=right lean
│  ◆                           │   ◆ = left session max watermark (white)
│  45          0           45  │   10sp · muted · scale ends and center
│                              │   (◆ for right watermark appears on right side)
├──────────────────────────────┤   1px divider
│                              │
│  95                          │   64sp Bold · #0D8FFF (speed blue)
│  SPEED               km/h    │
│                              │
│  ████████████████████████░░░ │   LED strip · edge-origin left→right
│                         ◆    │   ◆ = session max watermark
│  0                       120 │
│                              │
├──────────────────────────────┤
│                              │
│  ┌────────────────────────┐  │   #DC2626 fill · full width · 80dp
│  │          STOP          │  │   24sp Bold · white
│  └────────────────────────┘  │
└──────────────────────────────┘
```

### Segment safety coloring — worked example

Lean session max = 41°. Current = 38°. Range = 45°.
- 38/41 = 93% of session max → danger zone → bar color shifts to red.
- Segments left of 38° position are red. Segments between 38–41° are empty (track color)
  except the ◆ watermark at 41°. Segments beyond 41° are all empty.

### Calibration state

Lean/accel/pitch require R_phone→bike. While uncalibrated (first ride, no prior solve):
bar is empty, numeral shows "–". Once the ride auto-solves (a few minutes in), bars fill.

### Low-speed lean suppression

Lean and accel/pitch bars are empty and numeral shows "–" below 18 km/h
(bar-mount steering corrupts lean at low speed; same cutoff post-ride).

---

## S3a · Post-ride summary

Opens automatically after STOP (screen-on mode) or from the ride list.

```
┌──────────────────────────────┐
│ ←  Today 09:58           ⋮  │   back arrow · ⋮ = delete / share (share: dev only)
│                              │
│  21.1 km      27:48          │   distance · duration · 18sp medium
│  avg 50 km/h                 │   muted
│                              │
│  SPEED                max 91 │   label 10sp muted · stat right-aligned
│  ████████████████◆░░░░░      │   identity blue · ◆ at max
│                              │
│  LEAN          ◄ 41°  33° ►  │   orange · two stats
│  ░░░◆███████│████████◆░      │   filled range from center to each extreme
│                              │
│  ACCEL / BRAKE  −8.8 / +6.4  │   green left / red right of center
│  ░░◆████████│██████████◆░░   │
│                              │
│  PITCH          −3°  /  +11° │   teal
│  ░░░░░░◆░░░│████████████◆░░  │
│                              │
│  ELEVATION      126 m range  │   violet · no bar (free-range, not origin-based)
│                              │   hidden if device has no barometer
│                              │
│  tap any row to see trace ▸  │   11sp · muted
└──────────────────────────────┘
```

Summary bar semantics: fill = solid range from origin out to each session extreme
(same identity color, no safety gradient — this is a summary, not a live warning).

---

## S3b · Dimension trace

```
┌──────────────────────────────┐
│ ←  LEAN      Today 09:58     │
│                              │
│ 41┤    ╭╮     ╭─╮            │   line = identity color (#FF4500)
│   │   ╭╯╰╮   ╭╯ ╰╮  ╭╮      │   horizontal gridlines: dim (#252934 / #DDE1EA)
│  0┼───╯  ╰───╯   ╰──╯╰───   │   zero line: slightly brighter than gridlines
│  33┤                         │
│   └──┬────┬────┬────┬──      │   time axis with adaptive gridlines (1s…30min)
│     5m   10m  15m  20m       │   time labels bottom (m:ss, h:mm:ss for long rides)
│                              │
│  pinch=zoom · drag=pan       │   11sp · muted · span appended ("2 min 30 s shown")
│  tap=value readout           │
│                              │
│  EXTREMES                    │
│  ◄ max left   41°   12:41    │   ◄ in identity color · tap → jump to that moment
│  ► max right  33°   18:05    │
└──────────────────────────────┘
```

Y-axis autoscales to visible window with a per-dimension calm floor
(lean ±15°, pitch ±10°, accel ±3 m/s², speed 0–60 km/h minimum).
Edge-origin dimensions (speed, elevation) list only their max in EXTREMES —
"0 km/h" is not an extreme.

---

## App icon

Single needle arc on a dark disc. No text, no numbers.

```
  concept:  ╱──────╮
           ╱  ╱    │    arc segment: #FF4500 (lean orange)
           │ ╱     │    needle: white · tilted ~35° (lean angle)
           │╱      │    background: #111318 (dark, regardless of system theme)
```

Adaptive icon: dark-circle background layer + arc-and-needle foreground layer.
Recognisable at all sizes down to notification icon (24×24). The orange needle is the
app's visual fingerprint.

---

## Implementation notes (for when coding starts)

- All colors live in `Theme.kt` — one place.
- Segment bar is a single reusable `SegmentBar` composable: `segments`, `origin`,
  `filled`, `watermarkLeft`, `watermarkRight`, `color`, `modifier`. The safety
  gradient is computed inside: given `filled` (0..1) and `sessionMaxFraction` (0..1),
  each segment's color is determined by its distance from the origin vs. the max.
- `LeanEstimator`/`AutoCalibrator` tap path unchanged; only the display layer changes.
- Theme toggle: `ThemeOverride` enum (`SYSTEM`, `DARK`, `LIGHT`) persisted in
  `DataStore`; read in `MainActivity` before the first composition.
- `CACHE_VERSION` in `RideAnalyzer` does NOT need bumping — the analysis data format
  is unchanged; only display code changes.

---

## Decision log

| # | Topic | Decision |
|---|-------|----------|
| D1 | Visual language | Instrument/LED-strip — professional, motorsport-inspired |
| D2 | Theme | System dark mode + manual ☾/☀ override on home screen |
| D3 | Segment style | Rev-counter: no gaps, hard color boundary between filled/empty |
| D4 | Segments per side | ~10 (center-origin) · ~18–20 total (edge-origin speed) |
| D5 | Per-dimension color | Yes — identity color per dimension for instant recognition |
| D6 | Safety gradient | Identity → amber (#FFAB00) → red (#FF1744) at 70/90% of session max |
| D7 | Button form | Full-width solid block · sharp edges · START=#16A34A · STOP=#DC2626 |
| D8 | Icon | Orange needle arc on dark disc |
| D9 | Post-ride bar style | Solid range fill (no gradient) — summary, not live warning |
| Q1 | Watermark display | Session max only (all-time personal max faint tick: parked) |
| Q2 | Accel unit | m/s² (unchanged from v0.4.x) |
| Q3 | Map / geo | None — a ride is a time series (unchanged) |
