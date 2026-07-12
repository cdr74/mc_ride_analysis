# UI mockup — RideLogger "ride display" version (draft for discussion)

Status: **draft, nothing implemented.** Iterate on this document until the design is
nailed; only then code. Product decisions already made (2026-07-12):

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

## Decision log & remaining questions

| # | Topic | Status |
|---|-------|--------|
| Q1 | Live display treatment | ✔ unified bars + session watermarks · *assumed:* numerals kept above bars, subtle color shift near watermark — say if wrong |
| Q2 | Stop mechanism | ✔ plain always-visible STOP button |
| Q3 | Post-ride structure | ✔ all-dimension summary → tap a dimension → full-screen trace over time |
| Q4 | Accel unit | ✔ m/s² |
| Q5 | Map / geo display | ✔ none — a ride is a time series |
| Q6 | Analytics scope | ✔ v1 as mocked; fancy analytics later |
| Q7 | Marker button | ✔ **removed altogether** — no marker button, no marker concept in the next version (ground-truth labeling for algorithm work, if ever needed, happens post-ride, not with a button while riding) |
| Q8 | Home ready-status | ✔ "Initializing…" until ready, then START appears; comprehensive error screen if permissions/functionality missing; diagnostics in dev builds only |
