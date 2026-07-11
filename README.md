# mc_ride_analysis — RideLogger

Throwaway Android app that logs raw IMU (accel/gyro/mag at max rate), GPS/GNSS and
barometer from a bar-mounted phone to one SQLite file per motorcycle ride, for offline
sensor-fusion development in Python. See `DESIGN.md` for the authoritative spec and
`CLAUDE.md` for working rules.

## Layout

- `app/` — Android app (Kotlin, Compose, min SDK 29)
- `analysis/schema.md` — ride-file schema, the app↔Python contract
- `analysis/validate_ride.py` — ride-file validation (gaps, monotonicity, meta, drops)
- `docs/adr/` — decision notes when code diverges from the spec

## Build

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # install on connected device
./gradlew lint testDebugUnitTest # static checks + unit tests
```

## Validate an exported ride

```bash
python3 analysis/validate_ride.py ride_20260711T093005Z_0a1b2c3d.db
```
