# RideLogger — motorcycle ride telemetry from a bar-mounted phone

RideLogger measures **how a motorcycle is actually ridden** — lean angle, acceleration
and braking, pitch (wheelies), speed — using nothing but a phone clamped to the
handlebars. No external sensors, no cloud, no map tracking: a ride is a data series
over time.

The project is built **data-first**: the phone records research-grade raw sensor data;
calibration and sensor fusion are developed and validated *offline* in Python against
those recordings; only the validated estimator is then ported back into the app as a
**ride display** — two chosen dimensions as glanceable bars while riding, full
per-dimension traces afterwards. `DESIGN.md` is the authoritative spec, `CLAUDE.md` has
the working rules, `docs/adr/` records every decision.

## Phases (ADR 0005)

1. **✅ Raw logger** (Android app 0.2.x, released) — never drops or alters a sample.
2. **▶ Offline fusion** (`analysis/`) — calibration + lean estimation, validated on real
   rides; the causal live variant costs 0.35° RMS / 40 ms vs the offline reference.
3. **▶ Ride display** (next app version, spec in `docs/ui-mockup.md`) — live bars with
   session high-watermarks, post-ride analysis in-app, zero interaction while moving.
4. *Parked:* a minimal iOS logger writing the same ride-file format (wheelie data from a
   second bike).

## How it measures

One SQLite file per ride (schema in `analysis/schema.md`), written by a foreground
service built around five data-integrity rules (see `CLAUDE.md`): every sensor event is
logged or its loss is *counted*, timestamps are the monotonic `elapsedRealtimeNanos`
exactly as delivered, values are raw (no filtering, rounding, or unit conversion
on-device), one ride = one immutable file, and the file survives process death (WAL,
≤ 500 ms batches).

What goes into the file:

| stream | source | rate (Pixel 8) | why |
|---|---|---|---|
| accel / gyro / mag | *uncalibrated* variants + factory bias fields | 400 / 400 / 100 Hz | the fusion inputs, free of opaque OS runtime calibration |
| rotation vector | Android's own fusion | 50 Hz | comparison baseline only — never used as input |
| barometer | raw pressure | ~12 Hz | elevation cross-check |
| GPS | fused location: speed + accuracy, position, bearing | 1 Hz | speed is the fusion anchor |
| GNSS raw | pseudorange rates, C/N0 | when supported | future 10 Hz velocity option |

Clock anchors (monotonic ↔ UTC ↔ GPS time, taken at start and stop) make the streams
alignable offline and expose clock drift. `analysis/validate_ride.py` gates every ride:
gap analysis, monotonicity, drop stats, meta completeness — a ride that fails is not
analyzed.

## How the metrics are computed

**Calibration** (`analysis/calibrate.py`, ADR 0004) — the phone can sit on the mount at
any angle, so everything starts with solving the rotation R_phone→bike, automatically
from ride phases: *bike-up* is the mean specific force over straight steady-cruise
windows (never at stops — the phone sits on the steering assembly and bars turn at a
standstill), *bike-forward* is the horizontal specific-force direction during
straight-line accelerations. Measured repeatability: ~1.25° across rides **and phone
remounts**. The app persists the last solution so live metrics work from the first
second of a ride.

**Lean angle** (`analysis/fusion/`) — the hard one. Gravity-based orientation filters
(Madgwick, and Android's own rotation vector) systematically under-read lean in corners:
in a coordinated turn the specific force aligns with the *bike's* vertical — that is why
bikes lean — so gravity looks centered. The information that makes lean observable is
kinematic: a coordinated corner satisfies **φ = atan(v·ψ̇ / g)** (GPS speed × yaw rate).
The production estimator is a complementary filter: integrate the gyro roll rate
(instantaneous), continuously correct toward the kinematic lean (band-limited to 1.5 Hz —
sub-second yaw spikes from bumps are not lean) while moving, toward gravity when nearly
stopped. Lean is **never reported below 18 km/h**, where bar-mount steering coupling
corrupts it. Validation on real rides: four independent estimators agree on max lean
within a few degrees; the causal (real-time-capable) variant tracks the offline
reference to 0.35° RMS with ~40 ms lag.

**Acceleration / braking** — longitudinal specific force in the bike frame (m/s², raw
unit), low-passed below the 22–105 Hz engine-vibration band (measured: < 3 % of
vibration power falls inside the < 10 Hz dynamics band on the damped mount).

**Pitch / wheelie** — gravity/gyro pitch in the bike frame. Fork dive and squat
contaminate the small-pitch range (~2–4°), so only the wheelie band (10–40°) is treated
as signal. Awaits real wheelie data (phase 4).

**Speed** — GPS, with per-fix accuracy logged.

## Repo layout

- `app/` — Android app (Kotlin, Compose, min SDK 29)
- `analysis/schema.md` — ride-file schema, the app↔Python contract
- `analysis/validate_ride.py` — ride-file validation (gaps, monotonicity, meta, drops)
- `analysis/calibrate.py` — automatic phone→bike calibration (tagged segments in legacy files)
- `analysis/fusion/` — offline estimators: Madgwick port, GPS-aided lean, causal ride-display candidate
- `docs/adr/` — decision records
- `docs/ui-mockup.md` — approved UI spec for the ride-display version
- `data/` — local ride files + solved calibrations (gitignored, ~100 MB+ per ride)

## Get the app

Users install the prebuilt APK from the [latest GitHub release](https://github.com/cdr74/mc_ride_analysis/releases/latest)
— no toolchain needed. Full install/usage instructions in `USER.md`.

## Develop

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # install on connected device
./gradlew lint testDebugUnitTest # static checks + unit tests
```

## Cut a release

Bump `versionCode`/`versionName` in `app/build.gradle.kts`, then:

```bash
./gradlew testDebugUnitTest assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ridelogger-<version>.apk
gh release create v<version> ridelogger-<version>.apk --title "RideLogger <version>" --notes "..."
```

Releases ship the debug-signed APK (no Play signing). Build releases from the same
machine so the debug keystore — and thus the APK signature — stays stable across updates.

## Validate an exported ride

```bash
python3 analysis/validate_ride.py ride_20260711T093005Z_0a1b2c3d.db
```
