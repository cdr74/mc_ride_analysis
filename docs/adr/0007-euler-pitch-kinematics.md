# ADR 0007 — Euler pitch kinematics in the live estimator

Date: 2026-07-17 · Status: accepted · Ships in v0.3.3

## Problem

Field review of v0.3.2 on two commute rides (2026-07-17) showed pitch peaks of +33°
and +39° on rides with zero hard braking and no wheelies — always nose-up, regardless
of turn direction, clustering on slow 90° turns.

Root cause (confirmed by replaying the exact estimator chain over both ride files):
`LeanEstimator` integrated the body pitch rate directly (`θ̇ = -wy`). That is only
valid at zero roll. In a leaned turn the yaw rate projects into the bike's y-axis
(`wy = ψ̇·sinφ`), so every corner accumulates ≈ Δheading·sin(lean) of phantom nose-up
pitch — a 90° city turn at 20° lean reads as ~30° of wheelie. The sign works out
positive for both left and right turns, which is why the field symptom was
"nose-up spikes everywhere". The GPS-aided gravity-pitch correction (0.3.2, τ = 5 s)
is too slow to cancel a transient of that size.

## Decision

Integrate the **Euler pitch rate**, using the roll estimate the filter already has:

```
θ̇ = -wy·cosφ + wz·sinφ        (was: θ̇ = -wy)
```

In a coordinated turn (`wy = ψ̇·sinφ`, `wz = ψ̇·cosφ`) the two terms cancel exactly.
Everything else in the pitch channel (GPS-aided gravity pitch, τ = 5 s correction)
is unchanged, as are the lean and accel channels.

Validated by replay on both commute rides:

| ride | max pitch before | max pitch after | samples > 15° |
|---|---|---|---|
| 44f0af93 | +33.6° | +19.5° | 4.2 % → 0.3 % |
| bdc22d42 | +39.0° | +9.6° | 4.1 % → 0.0 % |

The residual +19.5° peak is a slow tight-turn sequence around the 18 km/h lean
cutoff, where the roll estimate itself is least trustworthy — accepted as an inherent
limit of the bar-mounted display estimator.

Guarded by a synthetic unit test (`LeanEstimatorTest.coordinatedTurnProducesNoPitch`):
a steady coordinated 20°-lean turn must stay < 3° pitch (the pre-fix code reads +23°
steady-state). `LeanEstimatorPortTest` (lean channel) is unaffected — the fix does not
touch the roll state — so the committed fixture stays valid.

## Alternatives considered

- **Full quaternion/DCM attitude for the display estimator** — handles all couplings
  at once, but a much bigger port surface for no visible gain on the two channels we
  display; rejected.
- **Gate pitch below the 18 km/h lean cutoff (like lean)** — does not address the
  cause (most spikes happened above the cutoff) and would blank a channel that now
  behaves; rejected.

## Notes

- The offline reference `analysis/fusion/compare_ride.py` contains no causal pitch
  chain (its pitch outputs are Madgwick/rotvec comparisons only), so the reference is
  not affected; the pitch channel remains defined by `LeanEstimator.kt` + this ADR.
- Known, separate divergence found during the same investigation and left as is: the
  estimator's input-rate counter counts accel+gyro events together (~800 Hz instead of
  ~400 Hz), so the 15 Hz anti-alias filters effectively cut at ~7.5 Hz per stream.
  Harmless for the 1.5 Hz fusion band; revisit only if a channel ever needs bandwidth
  above ~7 Hz.
- Pitch remains a v1 channel: the wheelie band (10–40°) is still unvalidated until
  real wheelie data exists (supermoto dataset).
