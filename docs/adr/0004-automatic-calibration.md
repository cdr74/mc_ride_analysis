# ADR 0004 — Automatic calibration from ride phases

Date: 2026-07-12 · Status: accepted · Supersedes ADR 0003 (guided hands-free calibration) for the next app version

The guided calibration flow (ADR 0003) is retired. The phone→bike rotation is solved
**automatically from opportunistic ride data**: the offline solver detects the needed
phases in any normal ride, no rider action, no calibration UI, no `calib_*` markers.

Prompted by the first field dataset (2026-07-12, two rides, 54.6 km, Pixel 8), which
showed the automatic approach is not merely more convenient but **more accurate**:

- An untagged solve — gravity from straight steady-cruise windows, forward axis from
  straight-line acceleration events (net-yaw + GPS-bearing straightness filter) —
  reproduced R across two rides **and a phone remount** to **1.25° total**
  (z 1.15°, x 1.06°).
- The tagged single-maneuver solve carries **~7° azimuth uncertainty** (road crown/slope
  during the one accel run; tagged vs untagged on the *same* ride disagreed by 7.3° in x).
- Averaging many opportunistic accel events showed sub-degree internal spread
  (median 0.3° on the calibration ride).

Solver rules (contract in `analysis/schema.md`):

- **Gravity (bike z) from straight steady-cruise windows, never from stops.** At a stop
  the bars are typically turned (foot down) and the phone sits on the steering assembly —
  a stop-based gravity estimate was 16° off in the field data. Cruise windows: GPS speed
  > 8 m/s, |dv/dt| < 0.3 m/s², heading rate < 1°/s, ≥ 5 s, duration-weighted mean.
- **Forward axis (bike x) from straight-line acceleration events:** GPS dv/dt > 1.2 m/s²
  sustained ≥ 2 s, bearing change < 10°, mean yaw rate about z < 0.05 rad/s; horizontal
  specific-force directions magnitude-weighted and re-orthogonalized against z.
- **Fork dive/squat does not corrupt the solved axes:** pitch-induced gravity leak is
  collinear with x after the horizontal projection — it biases maneuver *magnitudes*
  only (observed: accel 1.55 vs brake 1.95 m/s² asymmetry), which the solver doesn't use.
- **Fallback chain:** solve from this ride's phases → if the ride yields too few usable
  phases (short/slow rides), reuse the most recent solved calibration at reduced
  confidence. A ride with no usable phases is still valid raw data.
- Tagged `calib_*` segments in legacy files (app ≤ 0.2.x) remain supported as a seed /
  cross-check; new files contain none.

Consequences for the app: `CalibrationGuide.kt`, the calibration button, the full-screen
color cues, and marker writing are removed in the next version (see also ADR 0005 — the
marker concept is dropped entirely). The `marker` table stays in schema v1 for
compatibility with existing files; new rides simply write no rows to it.

Related field findings recorded with the same dataset: Pixel 8 accelerometer scale reads
gravity −0.6 % low (9.739 m/s² at a 129 s engine-off stop — scale error, not vibration
rectification; normalize offline), and the SP Connect damped bar mount puts all vibration
energy at 22–105 Hz, ≤ 2.8 % of power below the 10 Hz fusion band (§9 checklist pass).
