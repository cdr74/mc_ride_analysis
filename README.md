# mc_ride_analysis — RideLogger

Throwaway Android app that logs raw IMU (accel/gyro/mag at max rate), GPS/GNSS and
barometer from a bar-mounted phone to one SQLite file per motorcycle ride, for offline
sensor-fusion development in Python. See `DESIGN.md` for the authoritative spec and
`CLAUDE.md` for working rules.

## Layout

- `app/` — Android app (Kotlin, Compose, min SDK 29)
- `analysis/schema.md` — ride-file schema, the app↔Python contract
- `analysis/validate_ride.py` — ride-file validation (gaps, monotonicity, meta, drops)
- `analysis/calibrate.py` — solves the phone→bike rotation (automatic from ride phases; tagged segments in legacy files)
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
