# mc_ride_analysis — RideLogger

Motorcycle ride telemetry, built data-first: a phone on the bars records research-grade
raw sensor data; calibration and sensor fusion are developed and validated offline in
Python; only then does the validated estimator go back into the app as a **ride
display** — live lean / acceleration / pitch / speed while riding, full per-dimension
analysis after. See `DESIGN.md` for the authoritative spec and `CLAUDE.md` for working
rules.

## Phases (ADR 0005)

1. **✅ Raw logger** (Android app 0.2.x, released): IMU at the device's max rate
   (~400 Hz on the Pixel 8) + GPS/GNSS + barometer → one SQLite file per ride, built to
   never drop or alter a sample. Calibration is solved automatically from riding data —
   no user action (ADR 0004).
2. **▶ Offline fusion** (`analysis/fusion/`, in progress): phone→bike calibration,
   Madgwick + GPS-aided lean estimation, validated against the on-device rotation-vector
   baseline on real rides. Lean is never reported below 18 km/h (bar-mount steering
   coupling).
3. **Ride display** (next app version, spec in `docs/ui-mockup.md`): live view of two
   chosen dimensions as glanceable bars with session high-watermarks, post-ride
   per-dimension traces — safety first, minimal text, zero interaction while moving.
4. *Planned:* a minimal iOS logger writing the same ride-file format, to collect data
   from more bikes and riders.

## Layout

- `app/` — Android app (Kotlin, Compose, min SDK 29)
- `analysis/schema.md` — ride-file schema, the app↔Python contract
- `analysis/validate_ride.py` — ride-file validation (gaps, monotonicity, meta, drops)
- `analysis/calibrate.py` — solves the phone→bike rotation (automatic from ride phases; tagged segments in legacy files)
- `analysis/fusion/` — offline estimators: Madgwick port, GPS-aided lean, causal ride-display candidate
- `docs/adr/` — decision notes when code diverges from the spec
- `docs/ui-mockup.md` — approved UI spec for the next app version (ride display)
- `data/` — local ride files + solved calibrations (gitignored, ~100 MB+ per ride)

## Get the app

Users install the prebuilt APK from the [latest GitHub release](https://github.com/cdr74/mc_ride_analysis/releases/latest)
— no toolchain needed. Full install/calibration/usage instructions in `USER.md`.

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

Releases ship the debug-signed APK (MVP; no Play signing). Build releases from the same
machine so the debug keystore — and thus the APK signature — stays stable across updates.

## Validate an exported ride

```bash
python3 analysis/validate_ride.py ride_20260711T093005Z_0a1b2c3d.db
```
