# ADR 0003 — Hands-free guided calibration

Date: 2026-07-11 · Status: accepted · Supersedes the manual 3-toggle stepper (DESIGN.md §8 draft 1.0)

Expecting the rider to press start/end buttons around each calibration maneuver is a safety
risk — the acceleration and braking steps happen while riding. The stepper is replaced by a
guided flow: one **Start calibration** press while stationary, after which a state machine
in `CalibrationGuide.kt` detects each phase from **GPS speed** (thresholds in `Config.kt`)
and inserts the same `calib_start`/`calib_end` markers. Phase transitions are announced
with `ToneGenerator` beeps (distinct low buzz on retry) so the rider never looks at the
screen; instructions are also shown live in the UI for the stationary parts.

Decisions and boundaries:

- **GPS speed only, no IMU logic:** simplest robust signal, orientation-independent, and it
  keeps a clear distance from the "no on-device fusion" rule — this is threshold-based UI
  guidance; logged data is never touched.
- **Detection is deliberately coarse.** Markers only bracket maneuvers; `calib_start` is
  backdated 2 s for the 1 Hz latency, and the offline solver extracts exact segments and
  filters short aborted ones. Schema contract updated in `analysis/schema.md`.
- **Retry semantics:** moving during the 10 s hold or a false-start launch closes the open
  segment (markers stay balanced) and silently retries.
- **Calibration is optional per ride** (also decided here, prompted by the same review):
  the app never enforces it. Required after remounting the phone; otherwise the offline
  solver falls back to the most recent calibration at reduced confidence.
