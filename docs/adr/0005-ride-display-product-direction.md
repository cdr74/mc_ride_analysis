# ADR 0005 — Product direction: ride display version & phasing

Date: 2026-07-12 · Status: accepted · Extends the MVP scope (DESIGN.md §1.2 non-goals stay true for app ≤ 0.2.x)

The MVP's data-collection goal was met on 2026-07-12 (two validated rides, calibration
solved, §9 vibration checklist passed). The project now has a decided next version: the
app grows from a pure logger into a **ride display** — while remaining, at its core, the
same robust logging pipeline. Full UI specification: **`docs/ui-mockup.md`** (mockup-first
workflow: the .md mockup is iterated and approved before any UI code is written).

## Phasing (strict order)

1. **Offline fusion first** — Madgwick/Mahony + GPS-aided roll estimator in Python
   (`analysis/fusion/`), validated against the logged rotation-vector baseline on real
   rides. No app work starts before the roll estimator is trusted.
2. **In-app post-ride view** — fusion runs once on the closed ride file; summary of all
   dimensions, tap-through to per-dimension zoomable traces.
3. **Live ride display last** — the validated estimator drives the live bars.

## Decided UI principles (details in docs/ui-mockup.md)

- Safety first: zero required interaction while moving; minimal text; everything glanceable.
- Live display: **max 2 chosen dimensions** (lean / accel-brake / pitch / speed), each as
  a **bar with session high-watermark ticks** — center-origin for lean, accel/brake and
  pitch; edge-origin for speed. Plain always-visible STOP button.
- Startup: "Initializing…" until sensors + GPS are ready, then the START button appears;
  a comprehensive error screen if permissions/functionality are missing.
- Rider chooses screen-on live display or background capture (screen off, review after).
- Post-ride: all-dimension summary → per-dimension trace over time. **No map, no geo
  display — a ride is a data series over time.**
- **Marker concept removed altogether** (no button, no rows written; ground-truth labeling
  for algorithm work happens post-ride if ever needed).
- Units: m/s² for acceleration everywhere (same raw unit as the log).
- Export to PC stays developer-only; rides deletable anytime.

## What does not change

- The logging pipeline, data-integrity rules and schema v1 stay authoritative; the ride
  file remains the single source of truth, and on-device fusion for *display* never
  modifies logged raw data.
- Offline (Python) analysis remains the reference implementation; the in-app estimator is
  a port of the validated offline filter, compared against it per release.
