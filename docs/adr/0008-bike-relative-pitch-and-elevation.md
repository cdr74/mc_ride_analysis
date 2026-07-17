# ADR 0008 — Bike-relative pitch display + post-ride elevation graph

Date: 2026-07-17 · Status: accepted · Ships in v0.4.0 · Decided by Chris after the
pitch-comprehension review of the 2026-07-17 commute rides

## Problem

With ADR 0007 in place the pitch channel is *accurate* — and thereby dominated by road
grade: overlaying it with the barometer-derived slope showed 1.63° RMS agreement, i.e.
the trace is mostly an inclinometer. Unless the rider knows the route's slopes, that
reads as unexplained wander, and the interesting part (dive, squat, wheelies) hides
inside it. Chris's call: split the two — show the road as an elevation profile, show
the bike relative to the road.

## Decision

1. **The displayed pitch (live bar and post-ride trace) is bike-relative-to-road:**
   `θ_display = θ − grade`. The internal estimator state stays absolute; only the
   output changes.

   Road grade, estimated causally in `LeanEstimator` so live and post-ride use the
   identical chain:
   - barometer pressure → ISA altitude → two cascaded one-pole low-passes (τ = 4 s)
   - climb rate (one-pole, τ = 2 s) → `grade = atan2(climb, v_GPS)` when v > 3 m/s,
     clamped to ±12° (steeper than any paved road), one-pole τ = 2 s
   - **standstill (v < 3 m/s): grade decays toward the measured pitch (τ = 10 s)** —
     a grade held from before the stop may be wrong for where the bike now stands, so
     the display settles to 0 at lights (same philosophy as lean's
     correct-toward-gravity-when-slow)
   - no barometer → grade stays 0 → pitch falls back to absolute (pre-0.4.0 behavior)

2. **New post-ride-only dimension ELEVATION:** smoothed baro altitude relative to the
   ride start (same τ = 4 s smoothing), ~1 Hz trace, fifth summary row + zoomable
   detail with a free y-range (window min..max, minimum 40 m span). Never in the live
   picker. Hidden when the ride has no baro rows.

Validated by replaying both commute rides through the exact causal chain before
implementation: bike-only pitch mean +0.14°/+0.75°, std ~2.1° (absolute pitch was
2.6–2.8° std with sustained ±5° grade plateaus); grade plateaus fully removed; the
stale-grade offset at the rides' final long stops (+6.5° flat) eliminated by the
standstill decay. Guarded by `LeanEstimatorTest.steadyClimbReadsAsZeroPitchWithBaro`:
a synthetic constant 3° climb must read < 1° with baro and ≈ 3° without (fallback).

## Alternatives considered

- **Road-grade overlay line on the absolute pitch trace** (my first proposal) —
  rejected by Chris in favor of the cleaner split: elevation as its own graph,
  pitch bike-only everywhere.
- **GPS altitude instead of baro for grade** — GPS vertical accuracy (~1 m per fix,
  uncorrelated) is far noisier than baro over 10-second windows; baro drift
  (weather) cancels in the derivative. Baro chosen; GPS-alt fallback not worth the
  complexity for a throwaway app whose target device has a barometer.

## Notes

- Baro dynamic-pressure error at speed (stagnation in the mount pocket) was a concern;
  empirically the baro-derived grade tracked pitch through accelerations to well
  under 2° on both rides, so no correction is needed at street speeds.
- The analysis sidecar cache bumps to v4 (old rides recompute on next open, gaining
  the elevation trace and bike-only pitch retroactively). *Amended 0.4.1:* the 0.4.0
  build wrote v4 caches with an always-empty elevation trace (Long.MIN_VALUE sentinel
  overflow in the decimation), so the cache is v5 since 0.4.1 and the analyzer path is
  now covered by `RideAnalyzerTest`.
- Raw logging is untouched — the ride file keeps absolute raw sensor data; this is
  display-chain only.
