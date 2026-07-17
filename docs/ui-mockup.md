# UI mockup — RideLogger "ride display" version (draft for discussion)

Status: **implemented in v0.3.0 (2026-07-12).** This document is the UI spec of record —
it was iterated and approved before any code, and any future UI change goes through it
first. Product decisions made 2026-07-12:

- **No active calibration.** Guided calib flow (ADR 0003) is retired; calibration is solved
  automatically from ride phases. No calibration UI anywhere.
- **Phasing:** offline fusion validated first → in-app post-ride view → live display last.
  This mockup covers the end state so early phases build toward it.

## Design principles (from the wish list)

1. **Safety first.** Zero required interaction while moving. Everything glanceable, nothing readable-only.
2. **Minimal text during a ride.** Numbers and color, not words.
3. **Live display shows at most 2 dimensions**, chosen from: lean L/R · accel/brake · pitch fwd/back · speed.
4. **Rider chooses:** screen stays on with live display, or background capture (screen off), review after.
5. Post-ride analysis in-app (zoomable). Export to PC remains developer-only. Rides deletable anytime.

## Screen map

```
Home ──START──▶ Live display (screen-on mode)   ──stop──▶ Post-ride summary
  │        └──▶ Background capture (notification) ─┘            │
  ├── ride list ──tap──▶ Post-ride summary ──▶ traces (zoom)    │
  └── (long-press ride: delete / share*)              *dev builds only
```

No settings screen. Every choice lives where it's used.

---

## S1 · Home

```
┌──────────────────────────┐
│  RideLogger              │
│                          │
│  ┌────────────────────┐  │
│  │                    │  │
│  │       START        │  │   huge, glove-friendly (whole card is the target)
│  │                    │  │
│  └────────────────────┘  │
│                          │
│  LIVE   [LEAN] [SPEED]   │   ← 2 slots; tap a slot to cycle
│         lean·accel·      │     lean / accel / pitch / speed / off
│         pitch·speed      │
│                          │
│  SCREEN  ◉ live display  │   ← radio, persisted
│          ○ off (backgnd) │
│                          │
│──────────────────────────│
│  RIDES                   │
│  Today 09:58  21 km  28m │   tap → post-ride view
│  Today 07:44  34 km  47m │   long-press → delete (confirm) / share*
│  Jul 11 18:02 12 km  19m │
└──────────────────────────┘
```

- **✔ decided (Q8) — three-state startup, no status dot:**

  ```
  app start ──▶  Initializing …   ──all checks pass──▶  START button appears
                      │
                      └── anything inaccessible ──▶  error screen
  ```

  - **Initializing…** shown while sensors spin up and GPS acquires its first fix.
    No START button yet — it *appearing* is the ready signal.
  - **Error screen** when the app cannot access everything it needs (permission
    denied, location off, sensor rate capped by the mic privacy toggle, …):
    a comprehensive message listing each failed item and exactly how to fix it,
    with a button to the relevant system setting where possible. Re-checks
    automatically when the app returns to foreground.
  - Full Hz/fix/drop diagnostics block remains in dev builds only.
- If SCREEN = off: START minimizes the app, capture runs as today (foreground service +
  notification). Opening the app during capture shows the live display; leaving it again
  returns to background. Same code path, no separate mode.

---

## S2 · Live display — the 2-slot layout

Portrait, split horizontally. Slot content fills its half. While moving, touches on the
bar slots do nothing (no accidental slot-cycling); only STOP is active.

**Decided (2026-07-12): one unified bar grammar for all four dimensions.** Every slot is
a thick horizontal fill-bar on a recessed track, plus thin **session high-watermark
ticks** that persist for the ride. Two origin types:

- **Center-origin** (lean, accel/brake, pitch): bar sits at center when neutral, grows
  left/right. Watermark tick on *each* side.
- **Edge-origin** (speed): bar grows left→right from 0. One watermark.

```
glyphs:  ▓ current-value fill   │ center line   ╷ watermark tick

┌──────────────────────────┐
│  ◄ 38                    │   numeral stays huge above the bar
│                          │
│    ╷ ▓▓▓▓▓▓▓▓▓▓│         │   lean 38° left · watermarks 41°L / 33°R
│  ──┴───────────┼──────╷─ │
│  45           0        45│
│──────────────────────────│
│  95                      │
│                          │
│  ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ╷      │   speed 95 · watermark 104
│  ─────────────────┴───── │
│  0                    120│
└──────────────────────────┘
```

Per-dimension scales (constants, tune from ride data):

| dimension | origin | range | watermarks |
|---|---|---|---|
| lean | center | ±45° | left + right |
| accel / brake | center | ±10 m/s² (brake left, accel right) | both |
| pitch | center | ±20° (dive fwd, wheelie back) | both |
| speed | edge | 0–120 km/h | one |

Spec notes:

- Bar fill updates smoothed (~10 Hz visual, low-pass so it doesn't flicker with
  vibration); **watermark ticks move instantly** and never retreat during the ride.
- Watermarks reset at ride start. *(Option: additionally show the all-time personal max
  as an even fainter tick — say if wanted.)*
- Numeral above each bar is kept for the deliberate glance; the bar + watermark is the
  peripheral channel. *(Q1a: keep numerals, or bars only?)*
- **Lean below 18 km/h is not shown** (bar empty, numeral "—"): the bar-mounted phone
  moves with the steering assembly, and bar turn corrupts lean at low speed
  (DESIGN.md §11). Same cutoff post-ride: the lean trace has gaps below 18 km/h.
- **Calibration availability at ride start:** lean/accel/pitch bars need R_phone→bike.
  The app persists the last solved calibration and reuses it from the first second
  (valid — remount repeatability measured at ~1.25°), then re-solves opportunistically
  during the ride and swaps in the fresh solution. Very first ride on a device: those
  slots show "calibrating…" (blank bar) until the first solve lands, a few minutes in.
  Speed needs no calibration and always works.
- *(Q1b: should the bar change color as it approaches the watermark / a personal max —
  neutral → amber → red — or stay one high-contrast color?)*

### Stopping a ride

**✔ decided (Q2):** plain STOP button, always visible — no safety concern, stopping
happens when parked.

```
┌──────────────────────────┐
│  ◄ 38                    │
│  (lean bar)              │
│──────────────────────────│
│  95                      │
│  (speed bar)             │
│──────────────────────────│
│ ┌──────────────────────┐ │
│ │         STOP         │ │   full-width, glove-sized
│ └──────────────────────┘ │
└──────────────────────────┘
```

---

## S3 · Post-ride — summary of all dimensions, then per-dimension detail

**✔ decided (Q3, Q5, Q6):** a ride is a **data series over time** — no map, no geo
display. Post-ride opens with a summary of *all* dimensions; tapping one opens its
full-screen trace. Fancier analytics come later.

### S3a · Summary

Opens automatically after STOP (screen-on mode) or from the ride list. Each dimension
row reuses the live-display bar grammar (fill = session max reach, ticks = extremes).

```
┌──────────────────────────┐
│ ←  Today 09:58        ⋮  │   ⋮ = delete · share (share: dev builds only)
│                          │
│  21.1 km    27:48        │
│  avg 50 km/h             │
│                          │
│  SPEED        max 91     │
│  ▓▓▓▓▓▓▓▓▓▓▓▓▓╷          │
│                          │
│  LEAN       ◄ 41° / 33° ►│
│     ╷▓▓▓▓▓│▓▓▓▓╷         │
│                          │
│  ACCEL/BRAKE −8.8 / +6.4 │   (m/s²)
│      ╷▓▓▓▓│▓▓▓╷          │
│                          │
│  PITCH        −3° / +11° │
│         ╷▓│▓▓╷           │
│                          │
│  (tap any dimension ▸)   │
└──────────────────────────┘
```

### S3b · Dimension detail — visual over time

```
┌──────────────────────────┐
│ ←  LEAN    Today 09:58   │
│                          │
│ 41┤    ╭╮     ╭─╮        │
│   │   ╭╯╰╮   ╭╯ ╰╮  ╭╮   │   full-screen trace, pinch-zoom + pan,
│  0┼───╯  ╰───╯   ╰──╯╰── │   tap = value readout at that moment
│   │                      │
│ 33┤                      │
│   └──┬────┬────┬────┬─── │
│     5m   10m  15m  20m   │
│                          │
│  EXTREMES                │
│  ◄ max left   41°  12:41 │   ← tap → trace jumps there, zoomed in
│  ► max right  33°  18:05 │
└──────────────────────────┘
```

- Fusion (lean/pitch) runs once when the ride closes — progress spinner on first open
  ("computing…"), cached afterwards.
- Units: **m/s²** for accel/brake everywhere (✔ Q4) — same raw unit as the log.

---

## Field-review refinements (0.3.1 → 0.3.2, from Chris's first hands-on review)

- **Palette** (one place: `Theme.kt`): white ground for sunlight, ink `#0F172A`
  structure/text, slate muted/track tones, one vivid accent `#2563EB`; amber/red
  reserved for "near session max". Modern, clean, higher contrast than v0.3.0.
- **Bars:** center origin is a pronounced ink line protruding above/below the track;
  track has a hairline border; in the post-ride summary a bar shows **solid range
  fills** from the origin out to each session extreme (soft accent left, accent
  right), not bare tick lines.
- **Traces (S3b):** horizontal gridlines at each dimension's natural step (lean/pitch
  5°, accel 2 m/s², speed 20 km/h; step doubles when lines get denser than ~24 px),
  y-labels on every second gridline (readable, not cluttered), pronounced zero line.
- **Extremes list:** edge-origin dimensions (speed) list only their max — "0 km/h" is
  not an extreme.
- **Home:** one hint line under the picker chips ("tap the chips to change …") —
  chip-tapping wasn't discoverable.
- **Pitch estimator fix:** gravity pitch is corrected by GPS-derived longitudinal
  acceleration before use (a 6 m/s² launch otherwise reads as ~38° phantom wheelie;
  +46° was observed). Still a v1 channel until real wheelie data exists.

## Field-review refinements (0.3.2 → 0.3.3, from the commute-ride review)

No UI change. Estimator-only release: pitch integrates the Euler pitch rate
(θ̇ = −wy·cosφ + wz·sinφ) so leaned turns no longer read as +30–39° phantom nose-up
(ADR 0007); cached post-ride analyses recompute automatically on next open. Speed was
verified correct (the bike's speedometer over-reads — normal).

## Field-review refinements (0.3.3 → 0.3.4, trace readability — commute-ride review)

- **Time axis (closes a spec gap — the S3b sketch always had time labels, the
  implementation never drew them):** vertical time gridlines at an adaptive natural
  step (1 s … 30 min, chosen so ticks stay ≳ 72 px apart), time labels along the
  bottom (m:ss, h:mm:ss once a ride exceeds an hour), and the visible span appended
  to the hint line: "pinch = zoom · drag = pan · tap = value · 2 min 30 s shown".
- **Calm autoscale floor:** the y-scale still autosizes to the visible window, but the
  minimum half-span is per-dimension (lean ±15°, pitch ±10°, accel ±3 m/s², speed
  0–60 km/h) instead of the old global ±5. Zoomed into a straight, the ±1–2° of real
  sub-1-Hz steering wander no longer fills the screen (the "twitchy" impression from
  the 2026-07-17 field review); windows containing real cornering expand exactly as
  before.
- **No added smoothing** (considered, rejected with data): the wander is genuine
  sub-1-Hz content — display low-passes at 1–2 Hz barely reduce it (median 1-s p-p
  1.69° → 1.73° at 1 Hz on the commute rides) while adding 100–300 ms of lag.

## Field-review refinements (0.3.4 → 0.4.0, pitch semantics + elevation — Chris, 2026-07-17)

Decided after the "trying to understand the pitch" review (the absolute pitch trace is
dominated by road grade, which reads as noise unless you know the route's slopes):

- **PITCH is now bike-relative-to-road** — live bar AND post-ride trace (ADR 0008).
  The road grade (barometer climb rate ÷ GPS speed, causal chain in `LeanEstimator`)
  is subtracted, so the channel shows only dive, squat, and wheelies; baseline ≈ 0 on
  any road. At standstill a stale grade decays away (τ = 10 s) so the bar settles to 0
  at lights. Devices without a barometer fall back to absolute pitch.
- **New post-ride dimension: ELEVATION** (m, relative to ride start) — the smoothed
  barometric altitude profile as a fifth summary row + zoomable trace. Post-ride
  ONLY: it never appears in the live picker (the live chips still cycle
  lean → accel → pitch → speed → off). Free y-range (window min..max, ≥ 40 m span),
  gridlines every 10 m; the summary row shows the climb range ("126 m climb range")
  and no watermark bar (range bars are for origin-based dimensions). Hidden entirely
  when the ride has no baro data.
- Validated on both 2026-07-17 commute rides before implementation: bike-only pitch
  baseline 0.1–0.8° mean (was grade-dominated), stale-grade artifact at long stops
  eliminated by the standstill decay.

## Decision log & remaining questions

| # | Topic | Status |
|---|-------|--------|
| Q1 | Live display treatment | ✔ unified bars + session watermarks · *assumed:* numerals kept above bars, subtle color shift near watermark — say if wrong |
| Q2 | Stop mechanism | ✔ plain always-visible STOP button |
| Q3 | Post-ride structure | ✔ all-dimension summary → tap a dimension → full-screen trace over time |
| Q4 | Accel unit | ✔ m/s² |
| Q5 | Map / geo display | ✔ none — a ride is a time series |
| Q6 | Analytics scope | ✔ v1 as mocked; fancy analytics later |
| Q7 | Marker button | ✔ **removed altogether** in 0.3.0 — no marker button, no marker concept (ground-truth labeling for algorithm work, if ever needed, happens post-ride, not with a button while riding) |
| Q8 | Home ready-status | ✔ "Initializing…" until ready, then START appears; comprehensive error screen if permissions/functionality missing; diagnostics in dev builds only |
